package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.QueueLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C-31 rule 6 "Target" (ADR-052) of `QueueBuilder`: a keep-going build with `keepGoingNewTarget = T`
 * draws new cards against `targetRemaining = max(0, T − iToday)`; a normal build ignores T.
 */
class QueueKeepGoingTargetTest {
    private fun lim(target: Int?, keepGoing: Boolean = true, newPerDay: Int = 2, batch: Int = 10): QueueLimits =
        QueueLimits(newPerDay, 200, 0, 0, true, 0, keepGoing = keepGoing, keepGoingNewBatch = batch, keepGoingNewTarget = target)

    private fun introduced(n: Int): List<kotobaya.contracts.queue.TodayReview> =
        (1..n).map { today(5000 + it, type = ReviewType.LEARNING, introducedNew = true, at = TOKYO_DAY.startMs + it) }

    private fun fiveNew(): List<kotobaya.contracts.queue.QueueCandidate> = (1..5).map { card(it) }

    @Test
    fun targetExampleDrawsUpToTheTargetThenNothing() {
        // `newPerDay` 2 with two `introducedNew` today, batch 10, new N1 … N5, no reviews.
        val i = input(fiveNew(), limits = lim(4), today = introduced(2))
        assertEquals("N1 N2", build(i).shape())
        assertEquals(2, DefaultQueueBuilder.buildDetailed(i).availableToday)
        val sim = QueueSim(i)
        sim.answer(1, Ans.EASY) // iToday 3
        assertEquals("N2", sim.build().shape())
        sim.answer(2, Ans.EASY) // iToday 4
        assertTrue(sim.build().items.isEmpty(), "N3 … N5 exist but the target is reached")
    }

    @Test
    fun targetBelowTodaysIntroductionsDrawsNothing() {
        assertTrue(build(input(fiveNew(), limits = lim(4), today = introduced(5))).items.isEmpty())
    }

    @Test
    fun targetRemainingZeroDrawsNothing() {
        assertTrue(build(input(fiveNew(), limits = lim(2), today = introduced(2))).items.isEmpty())
        assertTrue(build(input(fiveNew(), limits = lim(-3), today = introduced(0))).items.isEmpty())
    }

    @Test
    fun noTargetIsTheEndlessStream() {
        assertEquals("N1 N2 N3 N4 N5", build(input(fiveNew(), limits = lim(null), today = introduced(2))).shape())
    }

    @Test
    fun aDueReviewComesBeforeTheNewCardsOfTheBatch() {
        val i = input(fiveNew() + review(9, NOW - HOUR), limits = lim(4), today = introduced(2))
        assertEquals("R9 N1 N2", build(i).shape())
    }

    @Test
    fun theBatchStillBoundsTheItemsButNotAvailableToday() {
        val i = input(fiveNew(), limits = lim(6, batch = 2), today = introduced(1))
        assertEquals("N1 N2", build(i).shape())
        assertEquals(5, DefaultQueueBuilder.buildDetailed(i).availableToday, "min(targetRemaining 5, pool 5), not the batch")
    }

    @Test
    fun aUnitOfThreeWithOneLeftOfTheTargetLeadsWithOneKanji() {
        // Word 学校 (card 1) requires 学 and 校, both NEW kanji notes (cards 101, 102): a unit of 3.
        val cands = listOf(
            card(1, created = 5, exprKanji = listOf("学", "校")),
            card(101, base = NoteBaseType.KANJI, kanji = "学", created = 1),
            card(102, base = NoteBaseType.KANJI, kanji = "校", created = 2),
        )
        val q = build(input(cands, limits = lim(3), today = introduced(2)))
        assertEquals("N101", q.shape())
        val whole = build(input(cands, limits = lim(5), today = introduced(2)))
        assertEquals("N101 N102 N1", whole.shape())
    }

    @Test
    fun aNormalBuildIgnoresTheTarget() {
        val cands = fiveNew() + review(9, NOW - HOUR) + learning(20, NOW - 1000) +
            card(30, created = 5, exprKanji = listOf("学")) + card(101, base = NoteBaseType.KANJI, kanji = "学")
        fun q(t: Int?): Any = build(input(cands, limits = lim(t, keepGoing = false, newPerDay = 4), today = introduced(1)))
        val expected = q(null)
        for (t in listOf(-5, 0, 1, 3, 100, Int.MAX_VALUE)) assertEquals(expected, q(t), "target $t")
    }

    @Test
    fun reachedTargetLeavesReviewsAndLearning() {
        val cands = fiveNew() + review(9, NOW - HOUR) + card(20, state = CardState.LEARNING, due = NOW - 1000)
        assertEquals("L20 R9", build(input(cands, limits = lim(2), today = introduced(2))).shape())
    }
}
