package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.Uuid
import kotobaya.contracts.WordStatus
import kotobaya.contracts.cards.CardKinds
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.MissingKanji
import kotobaya.contracts.queue.PendingEntry
import kotobaya.contracts.queue.QueueBuilder
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueDeck
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewQueue

/**
 * [QueueBuilder] following the contract KDoc rules 0–6 literally (ADR-037: rule 4 budget = the day's
 * remainder with a batch, rule 5 = the proportional mix; ADR-040..044: kanji-first units, leads,
 * backfill with its half share, the mix on units with "open group first"; C-28c: deck cost, turn
 * order, `N` never reordered). Pure: no clock, no
 * randomness; every order is a total order on input keys, so the result does not depend on the
 * order of any input list and is identical on jvm, android and js.
 *
 * Preconditions (the caller's job, not checked here): candidates are live (card and note not
 * deleted, card not a merge loser) and `noteId`/memberships are already resolved through
 * `merged_into_id` (`n:<loser id>` deck items count for the survivor, SYNC.md 8.5); [QueueInput.day]
 * is the study day containing `now` in the user's time zone; no candidate holds a refused cache
 * (`last_review_id = Reserved.REPLAY_REFUSED_ID`, SYNC.md 9.4): [QueueCandidate] carries no
 * `lastReviewId`, so the loader is the only place that can skip it. Cards appearing twice in
 * `candidates` are queued at most once.
 *
 * Choices the contract leaves open: a null `dueAt` on a LEARNING/RELEARNING/REVIEW card sorts as
 * overdue (`Long.MIN_VALUE`) so the card is shown rather than lost (a LEARNING one counts as due now); a membership naming a deck not
 * in [QueueInput.decks] is ignored for the home deck and counts as "not enabled" for the home
 * new-source; inside a deck, a card and a pending entry with equal `(position, itemKey)` order card
 * first; a pending entry whose `entrySeq` was already materialized from another deck is skipped; a
 * word's required kanji, missing kanji and override are read from the note's candidate with the
 * smallest card id (loaders pass the same values on every card of a note); a NEW card of another
 * base type reached on its turn is a unit of one card; a pending entry that is held (a required
 * kanji buried) is skipped like a held vocab card. Rule 4 "Turn order" (C-28c) replaces the
 * round-robin: `t(S)` counts today's and this build's cards per home source; C-28d: (a) the first
 * source in source order that claims the last drawn kanji goes first, (c) an earlier source that
 * claims a kanji part of the candidate's unit takes the turn; a capped deck's lead ends when the deck's
 * limit is used up; a keep-going share step draws nothing while a source claims the last kanji.
 */
public object DefaultQueueBuilder : QueueBuilder {
    private val excludedOverrides = setOf(WordStatus.NEVER_FORGET, WordStatus.BLACKLISTED, WordStatus.IGNORED)
    private val excludedEntryOverrides = excludedOverrides + WordStatus.KNOWN
    private val notIntroducingOverrides = setOf(WordStatus.BLACKLISTED, WordStatus.IGNORED)
    private val deckOrder: Comparator<QueueDeck> = compareBy<QueueDeck> { it.priority }.thenBy { it.id }

    override fun build(input: QueueInput): ReviewQueue = Build(input).run(probeMoreAvailable = true).queue

    /**
     * One build plus what [DefaultReviewLoop] derives its counts from, without the `moreAvailable`
     * probe (the loop sets it from its own keep-going build, so it runs exactly two builds).
     */
    internal fun buildDetailed(input: QueueInput): Built = Build(input).run(probeMoreAvailable = false)

    /** A build and the numbers [DefaultReviewLoop] needs from the same pass. */
    internal class Built(
        /** The build ([ReviewQueue.moreAvailable] false unless probed). */
        val queue: ReviewQueue,
        /** Rule 4 `availableToday` (normal build); the drawn count `drawn` of a keep-going build. */
        val availableToday: Int,
        /** NEW items + pending entries of the batch-unbounded draw: `LegacyCounts.new` (normal build). */
        val unboundedNewCount: Int,
        /** Eligible LEARNING/RELEARNING cards with `now < dueAt < day.endMs` (distinct ids). */
        val laterToday: Int,
        /** Smallest `dueAt` of those, or null. */
        val nextDueAt: Long?,
        /** Today's `countsAsReview` rows after rule 0. */
        val reviewsToday: Int,
        /** Today's `introducedNew` rows after rule 0. */
        val newToday: Int,
        /** Rule 4 `bToday` (ADR-044), for tests. */
        val backfillToday: Int = 0,
        /** Rule 4 `b` right after the first share step (before the first source turn), for tests. */
        val backfillAfterFirstShare: Int = 0,
        /** The number of parts the first share step drew, for tests. */
        val partsInFirstShare: Int = 0,
    )

    /**
     * One drawn unit of rule 4: a word's unit (whole or a lead), a single card on its own turn, or a
     * backfill part. [size] is what it counts against the budget; [items] is empty when withheld.
     */
    private class DrawnUnit(
        val drawnBefore: Int,
        val size: Int,
        val items: List<QueueItem>,
        val entry: PendingEntry?,
        val claims: List<String>,
        /** The required kanji of the word the unit was built for (rule 5 "open group"); null otherwise. */
        val wordReq: List<String>?,
    )

    private class NewDraw(
        val backfillAfterFirstShare: Int,
        val partsInFirstShare: Int,
        val units: List<DrawnUnit>,
        val materialize: List<PendingEntry>,
        val kanjiToMaterialize: List<String>,
        val availableToday: Int,
        val unboundedNewCount: Int,
    )

    /** Where a NEW card is drawn from (rule 4). */
    private sealed interface Home {
        data class Deck(val deck: QueueDeck) : Home

        data object Deckless : Home

        /** Has memberships, none enabled: never introduced on a turn. */
        data object None : Home
    }

    private sealed interface NewItem {
        data class Card(val c: QueueCandidate) : NewItem

        data class Entry(val e: PendingEntry) : NewItem
    }

    private class Source(
        val deckId: Uuid?,
        val priority: Int,
        val items: List<NewItem>,
    ) {
        var cursor = 0

        /** Stopped by a lead (ADR-043): takes no further turn in this build. */
        var stopped = false
    }

    /** A kanji part: existing NEW kanji cards of one literal, or the cards to mint for it. */
    private sealed interface Part {
        val literal: String
        val size: Int

        class Cards(override val literal: String, val cards: List<QueueCandidate>) : Part {
            override val size: Int get() = cards.size
        }

        class Mint(override val literal: String, override val size: Int) : Part
    }

    private class Build(raw: QueueInput) {
        // Rule 0 (ADR-035): retired candidates and today's reviews of them do not exist for the builder.
        private val retired: Set<Uuid> = raw.candidates
            .filter { CardKinds.isRetired(it.noteBaseType, it.kind, raw.readingEnabled) }
            .mapTo(HashSet()) { it.cardId }
        private val input: QueueInput = if (retired.isEmpty()) {
            raw
        } else {
            raw.copy(
                candidates = raw.candidates.filter { it.cardId !in retired },
                today = raw.today.filter { it.cardId !in retired },
            )
        }
        private val limits = input.limits
        private val decksById: Map<Uuid, QueueDeck> = buildMap {
            // Deterministic even with duplicate ids: keep the first in deck order.
            for (d in input.decks.sortedWith(deckOrder)) if (d.id !in this) put(d.id, d)
        }
        private val taken = HashSet<Uuid>()
        private val notesTaken = HashSet<Uuid>()

        /** Candidates by card id (the first in card-id order for a duplicated id, so input order never matters). */
        private val byId: Map<Uuid, QueueCandidate> = buildMap {
            for (c in input.candidates.sortedWith(compareBy({ it.cardId }, { it.state.ordinal }, { it.noteId }))) {
                if (c.cardId !in this) put(c.cardId, c)
            }
        }

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

        // ---- Kanji facts of the input (rule 4 "Kanji first") ----------------------------------------

        private fun isKanjiCard(c: QueueCandidate): Boolean = c.noteBaseType == NoteBaseType.KANJI && c.kanjiLiteral != null

        /** Every literal with at least one candidate kanji note (any state). */
        private val kanjiWithNote: Set<String> = input.candidates.filter { isKanjiCard(it) }.mapNotNullTo(HashSet()) { it.kanjiLiteral }

        /** Literals with a kanji card in a state other than NEW. */
        private val started: Set<String> = input.candidates
            .filter { isKanjiCard(it) && it.state != CardState.NEW }
            .mapNotNullTo(HashSet()) { it.kanjiLiteral }

        /** Literals whose kanji note has a NEW card that is not suspended but manually buried (ADR-043). */
        private val buriedKanji: Set<String> = input.candidates
            .filter { c ->
                val bu = c.buriedUntil
                isKanjiCard(c) && c.state == CardState.NEW && !c.suspended && bu != null && bu > input.now
            }
            .mapNotNullTo(HashSet()) { it.kanjiLiteral }

        private val kanjiOrder = compareBy<QueueCandidate>({ it.noteCreatedAt }, { it.noteId }, { it.kindOrder }, { it.cardId })

        /** Eligible NEW kanji cards per literal, in `kc` order. */
        private val newKanjiByLiteral: Map<String, List<QueueCandidate>> = input.candidates
            .filter { isKanjiCard(it) && it.state == CardState.NEW && eligible(it) }
            .distinctBy { it.cardId }
            .sortedWith(kanjiOrder)
            .groupBy { it.kanjiLiteral ?: "" }

        /** `kc(L)`: the not yet taken eligible NEW cards of L, at most one per note when burying. */
        private fun kc(literal: String): List<QueueCandidate> {
            if (literal in started) return emptyList()
            val out = mutableListOf<QueueCandidate>()
            val notes = HashSet<Uuid>()
            for (c in newKanjiByLiteral[literal].orEmpty()) {
                if (!available(c)) continue
                if (limits.burySiblings && !notes.add(c.noteId)) continue
                out += c
            }
            return out
        }

        private fun reqOf(c: QueueCandidate): List<String> =
            if (c.noteBaseType != NoteBaseType.VOCAB) emptyList() else (c.requiredKanji ?: c.expressionKanji).distinct()

        private fun reqOf(e: PendingEntry): List<String> = (e.requiredKanji ?: e.expressionKanji).distinct()

        /** A vocab note as backfill and `bToday` see it: the note's candidate with the smallest card id speaks for it. */
        private class Word(val noteId: Uuid, val createdAt: Long, val req: List<String>, val missing: List<MissingKanji>)

        /** Vocab notes with a card in a state other than NEW and no BLACKLISTED/IGNORED override, in `(noteCreatedAt, noteId)` order. */
        private val introducedWords: List<Word> = input.candidates
            .filter { it.noteBaseType == NoteBaseType.VOCAB }
            .groupBy { it.noteId }
            .mapNotNull { (noteId, cards) ->
                val first = cards.minWith(compareBy({ it.cardId }, { it.state.ordinal }))
                val introduced = cards.any { it.state != CardState.NEW }
                if (!introduced || first.wordOverride in notIntroducingOverrides) null
                else Word(noteId, first.noteCreatedAt, reqOf(first), first.missingKanji)
            }
            .sortedWith(compareBy({ it.createdAt }, { it.noteId }))

        /** C-28c: every literal some vocab candidate (any state) or pending entry requires. */
        private val requiredLiterals: Set<String> = buildSet {
            for (c in input.candidates) if (c.noteBaseType == NoteBaseType.VOCAB) addAll(reqOf(c))
            for (e in input.pendingEntries) addAll(reqOf(e))
        }

        /** Backfill kanji in part order (first requiring word's age, index in its `req`). */
        private val backfillOrder: List<String> = buildList {
            val seen = HashSet<String>()
            for (w in introducedWords) for (l in w.req) if (l !in started && seen.add(l)) add(l)
        }
        private val backfillSet: Set<String> = backfillOrder.toSet()

        /**
         * Per backfill literal without a kanji note: the mint size from the oldest introduced word that
         * requires it and lists it as missing (built once, so a backfill part is a map lookup).
         */
        private val backfillMintSize: Map<String, Int> = buildMap {
            for (w in introducedWords) {
                for (m in w.missing) {
                    if (m.literal in w.req && m.literal !in this) put(m.literal, maxOf(1, m.newCards))
                }
            }
        }

        /** `bToday` (ADR-044): today's introductions of kanji cards whose literal an earlier-introduced word requires. */
        private val bToday: Int = run {
            val introducedAt = HashMap<Uuid, Long>()
            for (t in input.today) {
                if (!t.introducedNew) continue
                val prev = introducedAt[t.cardId]
                if (prev == null || t.reviewedAt > prev) introducedAt[t.cardId] = t.reviewedAt
            }
            val reqByNote = HashMap<Uuid, List<String>>()
            for (w in input.candidates.filter { it.noteBaseType == NoteBaseType.VOCAB }.groupBy { it.noteId }) {
                val first = w.value.minWith(compareBy({ it.cardId }, { it.state.ordinal }))
                if (first.wordOverride !in notIntroducingOverrides) reqByNote[w.key] = reqOf(first)
            }
            // Per literal: the non-NEW cards of the words requiring it, with their latest introduction today.
            val wordCards = HashMap<String, MutableList<Long?>>()
            for (c in input.candidates) {
                if (c.noteBaseType != NoteBaseType.VOCAB || c.state == CardState.NEW) continue
                val req = reqByNote[c.noteId] ?: continue
                for (l in req) wordCards.getOrPut(l) { mutableListOf() } += introducedAt[c.cardId]
            }
            input.today.count { r ->
                if (!r.introducedNew) return@count false
                val c = byId[r.cardId] ?: return@count false
                val l = c.kanjiLiteral
                if (c.noteBaseType != NoteBaseType.KANJI || l == null) return@count false
                wordCards[l].orEmpty().any { at -> at == null || at < r.reviewedAt }
            }
        }

        fun run(probeMoreAvailable: Boolean): Built {
            val eligible = input.candidates.filter { eligible(it) }

            // Rule 2: learning, split into due now (`dueAt <= now`) and learn-ahead (C-22, ADR-023).
            val learningAll = eligible.filter { it.state == CardState.LEARNING || it.state == CardState.RELEARNING }
            val horizon = input.now + limits.learnAheadMs
            val learningNow = mutableListOf<QueueItem>()
            val learningAhead = mutableListOf<QueueItem>()
            for (c in learningAll.filter { due(it) <= horizon }.sortedWith(byDueThenId)) {
                if (c.cardId in taken) continue
                taken += c.cardId
                val item = QueueItem(c.cardId, QueueItemKind.LEARNING, homeDeck(c.deckIds())?.id)
                if (due(c) <= input.now) learningNow += item else learningAhead += item
            }
            val nextLearningDueAt = learningAll
                .mapNotNull { it.dueAt }
                .filter { it > horizon && it < input.day.endMs }
                .minOrNull()
            // ADR-037 "later today": the same eligibility, learn-ahead ignored.
            val later = learningAll.filter { c -> c.dueAt.let { it != null && it > input.now && it < input.day.endMs } }

            val reviews = buildReviews(eligible)
            val news = buildNew(eligible)
            val middle = order(reviews, news.units.filter { it.items.isNotEmpty() }, news.availableToday)
            val items = learningNow + middle + learningAhead
            val empty = items.isEmpty() && news.materialize.isEmpty() && news.kanjiToMaterialize.isEmpty()
            val moreAvailable = probeMoreAvailable && !limits.keepGoing && empty &&
                Build(input.copy(limits = limits.copy(keepGoing = true))).run(probeMoreAvailable = false).queue
                    .let { it.items.isNotEmpty() || it.toMaterialize.isNotEmpty() || it.kanjiToMaterialize.isNotEmpty() }
            return Built(
                queue = ReviewQueue(items, news.materialize, nextLearningDueAt, moreAvailable, news.kanjiToMaterialize),
                availableToday = news.availableToday,
                unboundedNewCount = news.unboundedNewCount,
                laterToday = later.mapTo(HashSet()) { it.cardId }.size,
                nextDueAt = later.mapNotNull { it.dueAt }.minOrNull(),
                reviewsToday = input.today.count { it.countsAsReview },
                newToday = input.today.count { it.introducedNew },
                backfillToday = bToday,
                backfillAfterFirstShare = news.backfillAfterFirstShare,
                partsInFirstShare = news.partsInFirstShare,
            )
        }

        private fun literalOf(item: QueueItem): String? = byId[item.cardId]?.takeIf { isKanjiCard(it) }?.kanjiLiteral

        /**
         * Rule 5 (normal build: proportional mix of the ready reviews [r] and the new [units]; the next
         * unit iff `s * gRem >= rRem`) and rule 6 (keep going: reviews before new), both with "open
         * group first": while `s == 0` and the last introduced card is a kanji that the next unit's
         * word requires, that unit comes before any review. Units always keep rule 4 draw order.
         */
        private fun order(r: List<QueueItem>, units: List<DrawnUnit>, availableToday: Int): List<QueueItem> {
            val intros = input.today.filter { it.introducedNew }
            val lastIntro = intros.maxWithOrNull(compareBy({ it.reviewedAt }, { it.cardId }))
            val t = lastIntro?.reviewedAt
            var s = input.today.count { it.countsAsReview && (t == null || it.reviewedAt > t) }.toLong()
            var lastLiteral: String? = lastIntro?.let { byId[it.cardId] }?.takeIf { isKanjiCard(it) }?.kanjiLiteral
            var rRem = r.size.toLong()
            var gRem = availableToday.toLong()
            val n = units.toMutableList()
            val out = ArrayList<QueueItem>(r.size + n.sumOf { it.items.size })
            var ri = 0

            /** C-28c: 0 when the first unit left in `N` is the open group, else -1; `N` is never reordered. */
            fun openGroup(): Int {
                val l = lastLiteral ?: return -1
                if (s != 0L || n.isEmpty()) return -1
                return if (n[0].wordReq?.contains(l) == true) 0 else -1
            }

            fun emit(index: Int) {
                val u = n.removeAt(index)
                out += u.items
                s = 0
                gRem -= u.size
                lastLiteral = literalOf(u.items.last())
            }

            fun review() {
                out += r[ri++]
                s++
                rRem--
            }

            if (limits.keepGoing) {
                while (n.isNotEmpty()) {
                    val og = openGroup()
                    if (og < 0) break
                    emit(og)
                }
                while (ri < r.size) review()
                while (n.isNotEmpty()) emit(0)
                return out
            }
            while (n.isNotEmpty()) {
                val og = openGroup()
                when {
                    og >= 0 -> emit(og)
                    ri >= r.size -> emit(0)
                    s * gRem >= rRem -> emit(0)
                    else -> review()
                }
            }
            while (ri < r.size) review()
            return out
        }

        // Rule 3.
        private fun buildReviews(eligible: List<QueueCandidate>): List<QueueItem> {
            // Rule 6: keep going lifts the global and per-deck review caps (no deck entry = no limit);
            // so does the `scheduler.reviews_unlimited` setting (ADR-034).
            val uncapped = limits.keepGoing || limits.reviewsUnlimited
            var global = if (uncapped) Int.MAX_VALUE else limits.reviewsPerDay - input.today.count { it.countsAsReview }
            val deckRemaining = HashMap<Uuid, Int>()
            if (!uncapped) for (d in decksById.values) d.reviewsPerDay?.let { deckRemaining[d.id] = it }
            for (t in input.today) {
                if (!t.countsAsReview) continue
                val h = homeDeck(t.deckIds) ?: continue
                deckRemaining[h.id]?.let { deckRemaining[h.id] = it - 1 }
            }
            val out = mutableListOf<QueueItem>()
            // ADR-040: a companion's REVIEW card is out of scope: never taken or counted.
            val due = eligible
                .filter { it.state == CardState.REVIEW && !it.companion && due(it) < input.day.endMs }
                .sortedWith(byDueThenId)
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
        private fun buildNew(eligible: List<QueueCandidate>): NewDraw {
            val keepGoing = limits.keepGoing
            val batch = maxOf(1, limits.keepGoingNewBatch)
            val iToday = input.today.count { it.introducedNew }
            // Normal build (ADR-037): the draw runs without the batch test (which gives `availableToday`);
            // the batch then keeps the units started while fewer than the batch were drawn, a prefix of
            // that draw. Keep going (rule 6): the batch bounds the draw itself, no day test.
            val dayRemaining = if (keepGoing) Long.MAX_VALUE else maxOf(0, limits.newPerDay - iToday).toLong()
            val drawLimit = if (keepGoing) batch else Int.MAX_VALUE
            val firstOfDayPossible = limits.newPerDay >= 1 && iToday == 0
            val share = maxOf(1, limits.newPerDay / 2)
            var drawn = 0
            var b = 0
            val units = mutableListOf<DrawnUnit>()
            val materialized = HashSet<Long>()
            val claimed = LinkedHashSet<String>()

            val introducedPerDeck = HashMap<Uuid, Int>()
            for (t in input.today) {
                if (!t.introducedNew) continue
                val h = homeNewSource(t.deckIds)
                if (h is Home.Deck) introducedPerDeck[h.deck.id] = (introducedPerDeck[h.deck.id] ?: 0) + 1
            }
            // Per-deck new remaining, charged to each card's home new-source (rule 4 per-deck limit,
            // C-8). May go below zero through kanji of units; read clamped. Decks without a limit are absent.
            val deckRemaining = HashMap<Uuid, Int>()
            for (d in decksById.values) {
                if (!d.enabled || keepGoing) continue
                d.newPerDay?.let { deckRemaining[d.id] = it - (introducedPerDeck[d.id] ?: 0) }
            }

            fun remainingOf(s: Source): Int =
                s.deckId?.let { id -> deckRemaining[id]?.let { maxOf(0, it) } } ?: Int.MAX_VALUE

            val deckTaken = HashSet<Uuid>()

            fun charge(deckId: Uuid?) {
                if (deckId == null) return
                deckTaken += deckId
                deckRemaining[deckId]?.let { deckRemaining[deckId] = it - 1 }
            }

            // ---- Turn order (C-28c) ----

            /** `t(S)` by source key (deck id, null = the deckless source). */
            val turnCount = HashMap<Uuid?, Int>()

            fun countTurn(key: Uuid?) {
                turnCount[key] = (turnCount[key] ?: 0) + 1
            }

            /** A deckless kanji card some word requires: drawn for a word or by backfill, not a turn of the deckless source. */
            fun requiredDeckless(c: QueueCandidate?, home: Home): Boolean =
                c != null && home == Home.Deckless && isKanjiCard(c) && c.kanjiLiteral in requiredLiterals

            for (t in input.today) {
                if (!t.introducedNew) continue
                when (val h = homeNewSource(t.deckIds)) {
                    is Home.Deck -> countTurn(h.deck.id)
                    Home.Deckless -> if (!requiredDeckless(byId[t.cardId], h)) countTurn(null)
                    Home.None -> {}
                }
            }

            /** The kanji literal of the last thing drawn (else of today's last introduction), or null. */
            var lastLiteral: String? = input.today.filter { it.introducedNew }
                .maxWithOrNull(compareBy({ it.reviewedAt }, { it.cardId }))
                ?.let { byId[it.cardId] }?.takeIf { isKanjiCard(it) }?.kanjiLiteral

            /** A kanji part "fits the day" (ADR-044): within the remainder, or the day's first new cards. */
            fun partFits(size: Int): Boolean =
                drawn + size.toLong() <= dayRemaining || (firstOfDayPossible && drawn == 0)

            fun shareOk(size: Int): Boolean =
                if (keepGoing) {
                    2L * (bToday.toLong() + b + size) <= iToday.toLong() + drawn + size
                } else {
                    bToday.toLong() + b + size <= share || bToday + b == 0
                }

            /** Takes a NEW card, charged to its own home new-source (C-8 attribution); its item. */
            fun takeNew(c: QueueCandidate): QueueItem {
                val h = homeNewSource(c.deckIds())
                val home = (h as? Home.Deck)?.deck?.id
                take(c)
                charge(home)
                when (h) {
                    is Home.Deck -> countTurn(home)
                    Home.Deckless -> if (!requiredDeckless(c, h)) countTurn(null)
                    Home.None -> {}
                }
                if (isKanjiCard(c) && c.kanjiLiteral in backfillSet) b++
                lastLiteral = if (isKanjiCard(c)) c.kanjiLiteral else null
                return QueueItem(c.cardId, QueueItemKind.NEW, home)
            }

            /** Takes [part]; returns its items (none for a mint part) and records its claim. */
            fun takePart(part: Part, claims: MutableList<String>): List<QueueItem> {
                drawn += part.size
                return when (part) {
                    is Part.Cards -> part.cards.map { takeNew(it) }
                    is Part.Mint -> {
                        claimed += part.literal
                        claims += part.literal
                        lastLiteral = part.literal
                        if (part.literal in backfillSet) b += part.size
                        emptyList()
                    }
                }
            }

            // ---- Backfill parts (ADR-040, ADR-043, ADR-044) ----

            var bfIndex = 0
            var firstShare = true
            var bAfterFirstShare = 0
            var partsFirstShare = 0

            /** The current part of backfill literal [l], or null when it has none now (passed over). */
            fun backfillPart(l: String): Part? {
                if (l in kanjiWithNote) return kc(l).takeIf { it.isNotEmpty() }?.let { Part.Cards(l, it) }
                if (l in claimed) return null
                return backfillMintSize[l]?.let { Part.Mint(l, it) }
            }

            /** C-28d (keep going): set below; true while `lastLiteral` is claimed by a source of any tier. */
            var unitOpen: () -> Boolean = { false }

            fun drawBackfill(withShare: Boolean) {
                while (bfIndex < backfillOrder.size) {
                    if (drawn >= drawLimit) return
                    // Rule 4 "Share step", "Not while a unit is open" (keep-going build only), tested before every part.
                    if (withShare && keepGoing && unitOpen()) return
                    val part = backfillPart(backfillOrder[bfIndex])
                    if (part == null) {
                        bfIndex++
                        continue
                    }
                    if (!keepGoing && !partFits(part.size)) return
                    if (withShare && !shareOk(part.size)) return
                    val before = drawn
                    val claims = mutableListOf<String>()
                    val items = takePart(part, claims)
                    units += DrawnUnit(before, part.size, items, null, claims, null)
                    bfIndex++
                    if (withShare && firstShare) partsFirstShare++
                }
            }

            fun shareStep() {
                drawBackfill(withShare = true)
                if (firstShare) bAfterFirstShare = b
                firstShare = false
            }

            // ---- Sources ----

            val pool = eligible
                .filter { it.state == CardState.NEW && it.wordOverride != WordStatus.KNOWN && !it.companion }
                .distinctBy { it.cardId }
                .associateWith { homeNewSource(it.deckIds()) }

            val cardsByDeck = HashMap<Uuid, MutableList<QueueCandidate>>()
            val deckless = mutableListOf<QueueCandidate>()
            for ((c, h) in pool) {
                when (h) {
                    is Home.Deck -> cardsByDeck.getOrPut(h.deck.id) { mutableListOf() } += c
                    // ADR-044: a deckless NEW card of a backfill kanji has no turn of its own.
                    Home.Deckless -> if (!(isKanjiCard(c) && c.kanjiLiteral in backfillSet)) deckless += c
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
                sources += Source(d.id, d.priority, items.map { it.second })
            }
            val decklessItems = deckless
                .sortedWith { x, y ->
                    compareValues(x.noteCreatedAt, y.noteCreatedAt).takeIf { it != 0 }
                        ?: compareCodePoints(x.kanjiLiteral ?: "", y.kanjiLiteral ?: "").takeIf { it != 0 }
                        ?: compareValues(x.kindOrder, y.kindOrder).takeIf { it != 0 }
                        ?: x.cardId.compareTo(y.cardId)
                }
                .map { NewItem.Card(it) }
            sources += Source(null, input.decklessPriority, decklessItems)

            /**
             * The unit of a word (vocab card [v] or pending entry [e]) with required kanji [req] and
             * [missing] kanji, drawn whole or as a lead (ADR-043) on a turn of [s].
             */
            fun drawWord(s: Source, req: List<String>, missing: List<MissingKanji>, v: QueueCandidate?, e: PendingEntry?) {
                val toMint = LinkedHashMap<String, MissingKanji>()
                for (m in missing) if (m.literal in req && m.literal !in kanjiWithNote && m.literal !in toMint) toMint[m.literal] = m
                val parts = mutableListOf<Part>()
                for (l in req) {
                    val k = kc(l)
                    if (k.isNotEmpty()) {
                        parts += Part.Cards(l, k)
                    } else {
                        val m = toMint[l]
                        if (m != null && l !in claimed) parts += Part.Mint(l, maxOf(1, m.newCards))
                    }
                }
                val g = 1L + parts.sumOf { it.size.toLong() }
                // C-28c "Deck cost": the word plus the kanji cards the drawing deck itself supplies.
                val limited = s.deckId != null && s.deckId in deckRemaining
                fun deckCost(p: Part): Int =
                    if (!limited || p !is Part.Cards) 0 else p.cards.count { (homeNewSource(it.deckIds()) as? Home.Deck)?.deck?.id == s.deckId }
                val dc = 1 + parts.sumOf { deckCost(it) }
                val before = drawn
                val claims = mutableListOf<String>()
                if (drawn + g <= dayRemaining && (!limited || dc <= remainingOf(s))) {
                    val kItems = parts.flatMap { takePart(it, claims) }
                    drawn += 1
                    charge(s.deckId)
                    countTurn(s.deckId)
                    lastLiteral = null
                    val withheld = toMint.isNotEmpty()
                    if (e != null) {
                        materialized += e.entrySeq
                        units += DrawnUnit(before, drawn - before, emptyList(), e, claims, req)
                    } else if (v != null) {
                        take(v)
                        val vItem = QueueItem(v.cardId, QueueItemKind.NEW, s.deckId)
                        units += DrawnUnit(before, drawn - before, if (withheld) emptyList() else kItems + vItem, null, claims, req)
                    }
                    return
                }
                // Lead: the kanji parts that fit the day, in order; the source stops at this word.
                val kItems = mutableListOf<QueueItem>()
                var minted = false
                fun deckFirstOfDay(): Boolean {
                    val id = s.deckId ?: return false
                    val d = decksById[id] ?: return false
                    return (d.newPerDay ?: 0) >= 1 && (introducedPerDeck[id] ?: 0) == 0 && id !in deckTaken
                }
                for (p in parts) {
                    if (!partFits(p.size)) break
                    val cost = deckCost(p)
                    if (cost > 0 && cost > remainingOf(s) && !deckFirstOfDay()) break
                    kItems += takePart(p, claims)
                    if (p is Part.Mint) minted = true
                    // C-28d: the lead ends with the part that uses up the drawing deck's limit (even before cost-0 parts).
                    if (limited && remainingOf(s) == 0) break
                }
                if (drawn > before) units += DrawnUnit(before, drawn - before, if (minted) emptyList() else kItems, null, claims, req)
                s.stopped = true
            }

            fun held(req: List<String>): Boolean = req.any { it !in started && it in buriedKanji }

            /** The item [s]'s next turn would draw (skipping what can no longer be drawn), or null. */
            fun peek(s: Source): NewItem? {
                while (s.cursor < s.items.size) {
                    val item = s.items[s.cursor]
                    val usable = when (item) {
                        is NewItem.Entry -> item.e.wordOverride !in excludedEntryOverrides && item.e.entrySeq !in materialized && !held(reqOf(item.e))
                        is NewItem.Card -> available(item.c) && !(item.c.noteBaseType == NoteBaseType.VOCAB && held(reqOf(item.c)))
                    }
                    if (usable) return item
                    s.cursor++
                }
                return null
            }

            fun canTurn(s: Source): Boolean = !s.stopped && remainingOf(s) > 0 && peek(s) != null

            /**
             * Rule 4 "Claims" (C-28d): [s] can take a turn and its next turn would draw the unit of a word
             * requiring [l], or the NEW kanji card of [l] on its own turn.
             */
            fun claims(s: Source, l: String): Boolean {
                if (!canTurn(s)) return false
                return when (val item = peek(s)) {
                    is NewItem.Entry -> l in reqOf(item.e)
                    is NewItem.Card -> when {
                        item.c.noteBaseType == NoteBaseType.VOCAB -> l in reqOf(item.c)
                        isKanjiCard(item.c) -> item.c.kanjiLiteral == l
                        else -> false
                    }
                    null -> false
                }
            }

            /** The literals of the kanji parts the unit of [s]'s next word would have now (rule (c)); null for a single card. */
            fun nextUnitPartLiterals(s: Source): List<String>? {
                val (req, missing) = when (val item = peek(s)) {
                    is NewItem.Entry -> reqOf(item.e) to item.e.missingKanji
                    is NewItem.Card -> if (item.c.noteBaseType == NoteBaseType.VOCAB) reqOf(item.c) to item.c.missingKanji else return null
                    null -> return null
                }
                return req.filter { l ->
                    kc(l).isNotEmpty() ||
                        (l !in kanjiWithNote && l !in claimed && missing.any { it.literal == l })
                }
            }

            /** One turn of [s] (C-28c: always draws something or stops the source). */
            fun turn(s: Source) {
                val item = peek(s) ?: return
                s.cursor++
                when (item) {
                    is NewItem.Entry -> drawWord(s, reqOf(item.e), item.e.missingKanji, null, item.e)
                    is NewItem.Card -> {
                        val c = item.c
                        if (c.noteBaseType == NoteBaseType.VOCAB) {
                            drawWord(s, reqOf(c), c.missingKanji, c, null)
                        } else if (drawn + 1L <= dayRemaining) {
                            val before = drawn
                            val single = takeNew(c)
                            drawn += 1
                            units += DrawnUnit(before, 1, listOf(single), null, emptyList(), null)
                        } else {
                            s.stopped = true
                        }
                    }
                }
            }

            val inOrder = compareBy<Source>({ it.priority }, { if (it.deckId == null) 1 else 0 }, { it.deckId ?: "" })
            val sorted = sources.sortedWith(inOrder)

            unitOpen = { lastLiteral?.let { l -> sorted.any { claims(it, l) } } ?: false }

            /**
             * Rule 4 "Turn order" (C-28d): (a) the first source in source order that claims `lastLiteral`;
             * (b) else the smallest `t(S)`, ties in source order; (c) while the candidate's next turn draws a
             * word's unit and an earlier source claims one of its kanji parts, the earliest such source.
             * [tier] is in source order.
             */
            fun pick(tier: List<Source>): Source? {
                val able = tier.filter { canTurn(it) }
                if (able.isEmpty()) return null
                val l = lastLiteral
                var candidate = l?.let { lit -> tier.firstOrNull { claims(it, lit) } }
                    ?: able.minBy { turnCount[it.deckId] ?: 0 } // minBy keeps the first on ties
                while (true) {
                    val parts = nextUnitPartLiterals(candidate) ?: break
                    if (parts.isEmpty()) break
                    val idx = tier.indexOf(candidate)
                    val earlier = tier.subList(0, idx).firstOrNull { s -> parts.any { claims(s, it) } } ?: break
                    candidate = earlier
                }
                return candidate
            }

            draw@ for (priority in sorted.map { it.priority }.distinct()) {
                val tier = sorted.filter { it.priority == priority }
                while (true) {
                    if (drawn >= drawLimit) break@draw
                    shareStep()
                    if (drawn >= drawLimit) break@draw
                    val next = pick(tier) ?: break
                    turn(next)
                }
            }
            // Remainder step: with no source left, backfill may use what is left of the day.
            drawBackfill(withShare = false)

            val kept = units.filter { it.drawnBefore < batch }
            return NewDraw(
                backfillAfterFirstShare = bAfterFirstShare,
                partsInFirstShare = partsFirstShare,
                units = kept,
                materialize = kept.mapNotNull { it.entry },
                kanjiToMaterialize = kept.flatMap { it.claims }.distinct(),
                availableToday = drawn,
                unboundedNewCount = units.sumOf { u -> u.items.count { it.kind == QueueItemKind.NEW } + (if (u.entry != null) 1 else 0) },
            )
        }
    }

    /** Compares by Unicode code point (not UTF-16 unit), so a literal outside the BMP sorts the same on every platform. */
    internal fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = codePointAt(a, i)
            val cb = codePointAt(b, j)
            if (ca != cb) return ca.compareTo(cb)
            i += if (ca >= 0x10000) 2 else 1
            j += if (cb >= 0x10000) 2 else 1
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun codePointAt(s: String, i: Int): Int {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
        }
        return c.code
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
}
