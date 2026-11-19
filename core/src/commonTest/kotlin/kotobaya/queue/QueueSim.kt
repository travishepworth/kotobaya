package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.LoopResult
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.queue.TodayReview

internal const val DAY_MS: Long = 86_400_000L

/** A rating, reduced to what the queue sees. */
internal enum class Ans { AGAIN, GOOD, EASY }

/**
 * Plays the loop the way a server or device does (CORE-28 scenario tests): answer a card (its state and
 * the `today` row a loader would pass), materialize a build (step A kanji notes, step B entries, C-27)
 * and roll the study day over. Minted cards get ids from [mintFrom] on; a minted kanji note is deckless
 * and created "now", an entry's note has the entry's deck membership.
 */
internal class QueueSim(var input: QueueInput, private var mintFrom: Int = 700) {
    /** Literal → card number of the kanji notes minted so far. */
    val minted = LinkedHashMap<String, Int>()

    /** Entry seq → card number of the vocab notes materialized so far. */
    val entries = LinkedHashMap<Long, Int>()

    fun eval(): LoopResult = DefaultReviewLoop.evaluate(input)

    fun build(): ReviewQueue = DefaultQueueBuilder.build(input)

    private fun later(): Long = input.day.endMs + 3 * DAY_MS

    /** Answers card [n] (a card number of the test support ids). */
    fun answer(n: Int, ans: Ans) {
        val id = cardId(n)
        val c = input.candidates.first { it.cardId == id }
        val now = input.now
        val (next, type, intro, review) = when (c.state) {
            CardState.NEW -> Quad(
                when (ans) {
                    Ans.AGAIN -> c.copy(state = CardState.LEARNING, dueAt = now + 60_000L)
                    Ans.GOOD -> c.copy(state = CardState.LEARNING, dueAt = now + 600_000L)
                    Ans.EASY -> c.copy(state = CardState.REVIEW, dueAt = later())
                },
                ReviewType.LEARNING, true, false,
            )
            CardState.REVIEW -> Quad(
                if (ans == Ans.AGAIN) c.copy(state = CardState.RELEARNING, dueAt = now + 600_000L) else c.copy(dueAt = later()),
                ReviewType.SCHEDULED, false, true,
            )
            CardState.LEARNING, CardState.RELEARNING -> Quad(
                if (ans == Ans.AGAIN) c.copy(dueAt = now + 60_000L) else c.copy(state = CardState.REVIEW, dueAt = later()),
                ReviewType.LEARNING, false, false,
            )
        }
        input = input.copy(
            now = now + 1000L,
            candidates = input.candidates.map { if (it.cardId == id) next else it },
            today = input.today + TodayReview(c.cardId, c.noteId, type, intro, review, c.memberships.map { it.deckId }, now),
        )
    }

    /** Answers the served head; returns its card number. */
    fun answerHead(ans: Ans = Ans.GOOD): Int {
        val n = eval().queue.cards().first()
        answer(n, ans)
        return n
    }

    /**
     * Materializes the served build: step A (a deckless kanji note per literal, one card) then step B
     * (a vocab note per entry, in its deck). Every word's `missingKanji` loses the minted literals.
     */
    fun materialize(q: ReviewQueue = eval().queue) {
        val now = input.now
        val newCards = mutableListOf<QueueCandidate>()
        for (l in q.kanjiToMaterialize) {
            if (l in minted) continue
            val n = mintFrom++
            minted[l] = n
            newCards += card(n, note = n, base = NoteBaseType.KANJI, kanji = l, created = now)
        }
        val mintedSet = q.kanjiToMaterialize.toSet()
        for (e in q.toMaterialize) {
            if (e.entrySeq in entries) continue
            val n = mintFrom++
            entries[e.entrySeq] = n
            newCards += card(
                n, note = n, created = now, members = listOf(DeckMemberships.of(e.deckId, e.position, e.itemKey)),
                exprKanji = e.expressionKanji, req = e.requiredKanji, missing = e.missingKanji.filter { it.literal !in mintedSet },
            )
        }
        val done = q.toMaterialize.map { it.entrySeq }.toSet()
        input = input.copy(
            candidates = input.candidates.map { c -> c.copy(missingKanji = c.missingKanji.filter { it.literal !in mintedSet }) } + newCards,
            pendingEntries = input.pendingEntries.filter { it.entrySeq !in done }
                .map { e -> e.copy(missingKanji = e.missingKanji.filter { it.literal !in mintedSet }) },
        )
    }

    /** The next study day, one second after it starts; `today` is empty. */
    fun nextDay() {
        val d = input.day.copy(startMs = input.day.endMs, endMs = input.day.endMs + DAY_MS)
        input = input.copy(day = d, now = d.startMs + 1000L, today = emptyList())
    }

    private data class Quad(val c: QueueCandidate, val type: ReviewType, val intro: Boolean, val review: Boolean)
}

internal object DeckMemberships {
    fun of(deck: String, position: Long, itemKey: String) = kotobaya.contracts.queue.DeckMembership(deck, position, itemKey)
}

/** Card number of the kanji card of [literal] among [q]'s candidates (test lookup). */
internal fun QueueInput.cardOf(literal: String): Int =
    candidates.first { it.kanjiLiteral == literal }.cardId.takeLast(12).toInt()
