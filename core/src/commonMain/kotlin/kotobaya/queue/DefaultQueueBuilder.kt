package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.Uuid
import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.PendingEntry
import kotobaya.contracts.queue.QueueBuilder
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueDeck
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewQueue

/**
 * [QueueBuilder] following the contract KDoc rules 1–5 literally. Pure: no clock, no randomness;
 * every order is a total order on input keys, so the result does not depend on the order of any
 * input list and is identical on jvm, android and js.
 *
 * Preconditions (the caller's job, not checked here): candidates are live (card and note not
 * deleted, card not a merge loser) and `noteId`/memberships are already resolved through
 * `merged_into_id` (`n:<loser id>` deck items count for the survivor, SYNC.md 8.5); [QueueInput.day]
 * is the study day containing `now` in the user's time zone. Cards appearing twice in
 * `candidates` are queued at most once.
 *
 * Choices the contract leaves open: a null `dueAt` on a LEARNING/RELEARNING/REVIEW card sorts as
 * overdue (`Long.MIN_VALUE`) so the card is shown rather than lost; a membership naming a deck not
 * in [QueueInput.decks] is ignored for the home deck and counts as "not enabled" for the home
 * new-source; inside a deck, a card and a pending entry with equal `(position, itemKey)` order card
 * first; a pending entry whose `entrySeq` was already materialized from another deck is skipped.
 */
public object DefaultQueueBuilder : QueueBuilder {
    private val excludedOverrides = setOf(WordStatus.NEVER_FORGET, WordStatus.BLACKLISTED, WordStatus.IGNORED)
    private val excludedEntryOverrides = excludedOverrides + WordStatus.KNOWN
    private val deckOrder: Comparator<QueueDeck> = compareBy<QueueDeck> { it.priority }.thenBy { it.id }

    override fun build(input: QueueInput): ReviewQueue = Build(input).run()

    /** Where a NEW card is drawn from (rule 4). */
    private sealed interface Home {
        data class Deck(val deck: QueueDeck) : Home

        data object Deckless : Home

        /** Has memberships, none enabled: never introduced. */
        data object None : Home
    }

    private sealed interface NewItem {
        data class Card(val c: QueueCandidate) : NewItem

        data class Entry(val e: PendingEntry) : NewItem
    }

    private class Source(
        val deckId: Uuid?,
        val priority: Int,
        val addKanji: AddKanjiMode,
        var remaining: Int,
        val items: List<NewItem>,
    ) {
        var cursor = 0
    }

    private class Build(private val input: QueueInput) {
        private val limits = input.limits
        private val decksById: Map<Uuid, QueueDeck> = buildMap {
            // Deterministic even with duplicate ids: keep the first in deck order.
            for (d in input.decks.sortedWith(deckOrder)) if (d.id !in this) put(d.id, d)
        }
        private val taken = HashSet<Uuid>()
        private val notesTaken = HashSet<Uuid>()

        private fun homeDeck(deckIds: List<Uuid>): QueueDeck? =
            deckIds.mapNotNull { decksById[it] }.minWithOrNull(deckOrder)

        private fun homeNewSource(deckIds: List<Uuid>): Home {
            if (deckIds.isEmpty()) return Home.Deckless
            val d = deckIds.mapNotNull { decksById[it] }.filter { it.enabled }.minWithOrNull(deckOrder)
            return if (d == null) Home.None else Home.Deck(d)
        }

        private fun QueueCandidate.deckIds(): List<Uuid> = memberships.map { it.deckId }

        private val siblingReviews: Map<Uuid, Set<Uuid>> = input.today
            .filter { it.reviewType == ReviewType.SCHEDULED || it.reviewType == ReviewType.LEARNING }
            .groupBy({ it.noteId }, { it.cardId })
            .mapValues { it.value.toSet() }

        private fun eligible(c: QueueCandidate): Boolean {
            if (c.suspended) return false
            val bu = c.buriedUntil
            if (bu != null && bu > input.now) return false
            if (c.wordOverride in excludedOverrides) return false
            if (limits.burySiblings && (c.state == CardState.NEW || c.state == CardState.REVIEW)) {
                val reviewed = siblingReviews[c.noteId]
                if (reviewed != null && reviewed.any { it != c.cardId }) return false
            }
            return true
        }

        /** Rule 1 within one build: a NEW/REVIEW card whose note already has a NEW/REVIEW item is skipped. */
        private fun available(c: QueueCandidate): Boolean =
            c.cardId !in taken && !(limits.burySiblings && c.noteId in notesTaken)

        private fun take(c: QueueCandidate) {
            taken += c.cardId
            if (limits.burySiblings) notesTaken += c.noteId
        }

        private fun due(c: QueueCandidate): Long = c.dueAt ?: Long.MIN_VALUE

        private val byDueThenId = compareBy<QueueCandidate>({ due(it) }, { it.cardId })

        fun run(): ReviewQueue {
            val eligible = input.candidates.filter { eligible(it) }

            // Rule 2: learning.
            val learningAll = eligible.filter { it.state == CardState.LEARNING || it.state == CardState.RELEARNING }
            val horizon = input.now + limits.learnAheadMs
            val learning = mutableListOf<QueueItem>()
            for (c in learningAll.filter { due(it) <= horizon }.sortedWith(byDueThenId)) {
                if (c.cardId in taken) continue
                taken += c.cardId
                learning += QueueItem(c.cardId, QueueItemKind.LEARNING, homeDeck(c.deckIds())?.id)
            }
            val nextLearningDueAt = learningAll
                .mapNotNull { it.dueAt }
                .filter { it > horizon && it < input.day.endMs }
                .minOrNull()

            val reviews = buildReviews(eligible)
            val news = buildNew(eligible)
            return ReviewQueue(learning + interleave(reviews, news.first), news.second, nextLearningDueAt)
        }

        // Rule 3.
        private fun buildReviews(eligible: List<QueueCandidate>): List<QueueItem> {
            var global = limits.reviewsPerDay - input.today.count { it.countsAsReview }
            val deckRemaining = HashMap<Uuid, Int>()
            for (d in decksById.values) d.reviewsPerDay?.let { deckRemaining[d.id] = it }
            for (t in input.today) {
                if (!t.countsAsReview) continue
                val h = homeDeck(t.deckIds) ?: continue
                deckRemaining[h.id]?.let { deckRemaining[h.id] = it - 1 }
            }
            val out = mutableListOf<QueueItem>()
            val due = eligible.filter { it.state == CardState.REVIEW && due(it) < input.day.endMs }.sortedWith(byDueThenId)
            for (c in due) {
                if (global <= 0) break
                if (!available(c)) continue
                val home = homeDeck(c.deckIds())
                val rem = home?.let { deckRemaining[it.id] }
                if (rem != null && rem <= 0) continue
                take(c)
                global--
                if (home != null && rem != null) deckRemaining[home.id] = rem - 1
                out += QueueItem(c.cardId, QueueItemKind.REVIEW, home?.id)
            }
            return out
        }

        // Rule 4.
        private fun buildNew(eligible: List<QueueCandidate>): Pair<List<QueueItem>, List<PendingEntry>> {
            var budget = maxOf(
                0,
                minOf(
                    limits.newPerDay - input.today.count { it.introducedNew },
                    limits.newPerSession - limits.newShownThisSession,
                ),
            )
            val out = mutableListOf<QueueItem>()
            val materialize = mutableListOf<PendingEntry>()
            if (budget == 0) return out to materialize

            val pool = eligible
                .filter { it.state == CardState.NEW && it.wordOverride != WordStatus.KNOWN }
                .associateWith { homeNewSource(it.deckIds()) }

            val introducedPerDeck = HashMap<Uuid, Int>()
            for (t in input.today) {
                if (!t.introducedNew) continue
                val h = homeNewSource(t.deckIds)
                if (h is Home.Deck) introducedPerDeck[h.deck.id] = (introducedPerDeck[h.deck.id] ?: 0) + 1
            }

            val cardsByDeck = HashMap<Uuid, MutableList<QueueCandidate>>()
            val deckless = mutableListOf<QueueCandidate>()
            for ((c, h) in pool) {
                when (h) {
                    is Home.Deck -> cardsByDeck.getOrPut(h.deck.id) { mutableListOf() } += c
                    Home.Deckless -> deckless += c
                    Home.None -> {}
                }
            }
            val sources = mutableListOf<Source>()
            for (d in decksById.values.filter { it.enabled }) {
                val items = mutableListOf<Pair<DeckKey, NewItem>>()
                for (c in cardsByDeck[d.id].orEmpty()) {
                    val m = c.memberships.filter { it.deckId == d.id }
                        .minWithOrNull(compareBy({ it.position }, { it.itemKey })) ?: continue
                    items += DeckKey(m.position, m.itemKey, 0, c.kindOrder, c.cardId, 0L) to NewItem.Card(c)
                }
                for (e in input.pendingEntries) {
                    if (e.deckId != d.id) continue
                    items += DeckKey(e.position, e.itemKey, 1, 0, "", e.entrySeq) to NewItem.Entry(e)
                }
                items.sortWith { a, b -> a.first.compareTo(b.first) }
                val limit = d.newPerDay?.let { maxOf(0, it - (introducedPerDeck[d.id] ?: 0)) } ?: Int.MAX_VALUE
                sources += Source(d.id, d.priority, d.addKanji, limit, items.map { it.second })
            }
            val decklessItems = deckless
                .sortedWith(compareBy({ it.noteCreatedAt }, { it.kindOrder }, { it.cardId }))
                .map { NewItem.Card(it) }
            sources += Source(null, input.decklessPriority, input.decklessAddKanji, Int.MAX_VALUE, decklessItems)

            // Kanji cards that add_kanji may pull: NEW, eligible, with some home new-source.
            val kanjiByLiteral: Map<String, List<QueueCandidate>> = pool
                .filter { (c, h) -> h != Home.None && c.noteBaseType == NoteBaseType.KANJI && c.kanjiLiteral != null }
                .keys
                .sortedWith(compareBy({ it.kindOrder }, { it.cardId }))
                .groupBy { it.kanjiLiteral ?: "" }

            val materialized = HashSet<Long>()

            fun takeNew(s: Source, c: QueueCandidate) {
                take(c)
                budget--
                s.remaining--
                out += QueueItem(c.cardId, QueueItemKind.NEW, s.deckId)
            }

            fun drawKanji(s: Source, word: QueueCandidate) {
                for (k in word.expressionKanji) {
                    for (kc in kanjiByLiteral[k].orEmpty()) {
                        if (budget <= 0 || s.remaining <= 0) return
                        if (available(kc)) takeNew(s, kc)
                    }
                }
            }

            /** One turn of [s]; false if the source is exhausted. */
            fun turn(s: Source): Boolean {
                if (s.remaining <= 0) return false
                while (s.cursor < s.items.size) {
                    val item = s.items[s.cursor++]
                    when (item) {
                        is NewItem.Entry -> {
                            val e = item.e
                            if (e.wordOverride in excludedEntryOverrides || e.entrySeq in materialized) continue
                            materialized += e.entrySeq
                            materialize += e
                            budget--
                            s.remaining--
                            return true
                        }
                        is NewItem.Card -> {
                            val c = item.c
                            if (!available(c)) continue
                            val withKanji = c.noteBaseType == NoteBaseType.VOCAB && c.expressionKanji.isNotEmpty()
                            when {
                                withKanji && s.addKanji == AddKanjiMode.BEFORE -> {
                                    drawKanji(s, c)
                                    if (!available(c)) {
                                        // defensive: a drawn kanji card shared the word's note
                                    } else if (budget > 0 && s.remaining > 0) {
                                        takeNew(s, c)
                                    } else {
                                        s.cursor-- // not drawn; the source is at its limit or the budget is gone
                                    }
                                }
                                withKanji && s.addKanji == AddKanjiMode.ALONGSIDE -> {
                                    takeNew(s, c)
                                    drawKanji(s, c)
                                }
                                else -> takeNew(s, c)
                            }
                            return true
                        }
                    }
                }
                return false
            }

            val inOrder = compareBy<Source>({ it.priority }, { if (it.deckId == null) 1 else 0 }, { it.deckId ?: "" })
            val sorted = sources.sortedWith(inOrder)
            for (priority in sorted.map { it.priority }.distinct()) {
                val tier = sorted.filter { it.priority == priority }
                while (true) {
                    var progressed = false
                    for (s in tier) {
                        if (budget <= 0) return out to materialize
                        if (turn(s)) progressed = true
                    }
                    if (!progressed) break
                }
            }
            return out to materialize
        }
    }

    /** Sort key of an item inside a deck: `(position, itemKey, card-before-entry, kindOrder, cardId, entrySeq)`. */
    private data class DeckKey(
        val position: Long,
        val itemKey: String,
        val rank: Int,
        val kindOrder: Int,
        val cardId: String,
        val entrySeq: Long,
    ) : Comparable<DeckKey> {
        override fun compareTo(other: DeckKey): Int = compareValuesBy(
            this,
            other,
            { it.position },
            { it.itemKey },
            { it.rank },
            { it.kindOrder },
            { it.cardId },
            { it.entrySeq },
        )
    }

    // Rule 5.
    private fun interleave(r: List<QueueItem>, n: List<QueueItem>): List<QueueItem> {
        if (r.isEmpty()) return n
        if (n.isEmpty()) return r
        val total = r.size + n.size
        val k = maxOf(1, total / n.size)
        val out = ArrayList<QueueItem>(total)
        var ri = 0
        var ni = 0
        for (i in 0 until total) {
            val newHere = ni < n.size && ((i + 1) % k == 0 || ri >= r.size)
            if (newHere) out += n[ni++] else out += r[ri++]
        }
        return out
    }
}
