package kotobaya.queue

import kotobaya.contracts.queue.DeckMembership
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** ADR-034: `QueueLimits.reviewsUnlimited` (setting `scheduler.reviews_unlimited`). */
class QueueReviewsUnlimitedTest {
    private val past = NOW - 2 * HOUR
    private val tomorrow = TOKYO_DAY.endMs

    private fun reviews(n: Int, members: (Int) -> List<DeckMembership> = { emptyList() }) =
        (1..n).map { review(it, past + it, members(it)) }

    @Test
    fun defaultIsFalse() {
        assertFalse(limits().reviewsUnlimited)
    }

    @Test
    fun threeHundredDueCapTwoHundredOffServesTwoHundredOnServesThreeHundred() {
        val c = reviews(300)
        val off = build(input(c, limits = limits(reviewsPerDay = 200)))
        assertEquals(200, off.cards(QueueItemKind.REVIEW).size)
        assertEquals((1..200).toList(), off.cards(QueueItemKind.REVIEW))
        val on = build(input(c, limits = limits(reviewsPerDay = 200).copy(reviewsUnlimited = true)))
        assertEquals(300, on.cards(QueueItemKind.REVIEW).size)
        assertEquals((1..300).toList(), on.cards(QueueItemKind.REVIEW))
        assertFalse(on.moreAvailable)
    }

    @Test
    fun alreadyReviewedTodayDoesNotLimitWhenOn() {
        val c = reviews(10)
        val done = (90 until 95).map { today(it, countsAsReview = true) }
        val l = limits(reviewsPerDay = 5)
        val off = build(input(c, limits = l, today = done))
        assertEquals("", off.shape())
        val on = build(input(c, limits = l.copy(reviewsUnlimited = true), today = done))
        assertEquals(10, on.cards(QueueItemKind.REVIEW).size)
    }

    @Test
    fun perDeckReviewCapIgnoredWhenOnButNewLimitStillApplies() {
        val revs = reviews(10) { listOf(member(1, it.toLong())) }
        val news = (101..110).map { card(it, members = listOf(member(1, it.toLong()))) }
        val decks = listOf(deck(1, reviewsPerDay = 3, newPerDay = 4))
        val l = limits(reviewsPerDay = 200, newPerDay = 2)
        val off = build(input(revs + news, decks = decks, limits = l))
        assertEquals(3, off.cards(QueueItemKind.REVIEW).size)
        assertEquals(listOf(101, 102), off.cards(QueueItemKind.NEW))
        val on = build(input(revs + news, decks = decks, limits = l.copy(reviewsUnlimited = true)))
        assertEquals(10, on.cards(QueueItemKind.REVIEW).size)
        assertEquals(listOf(101, 102), on.cards(QueueItemKind.NEW), "global new limit still applies")
        // Per-deck new limit still applies.
        val deckNew = build(input(revs + news, decks = listOf(deck(1, reviewsPerDay = 3, newPerDay = 1)), limits = limits(newPerDay = 20).copy(reviewsUnlimited = true)))
        assertEquals(10, deckNew.cards(QueueItemKind.REVIEW).size)
        assertEquals(listOf(101), deckNew.cards(QueueItemKind.NEW))
    }

    @Test
    fun neverStudiesAheadAndStillMixes() {
        val c = listOf(review(1, past), review(2, past + 1), review(3, tomorrow), card(4), learning(5, NOW + HOUR))
        val l = limits(reviewsPerDay = 1, learnAheadMs = 0).copy(reviewsUnlimited = true)
        val q = build(input(c, limits = l))
        // ADR-037 mix: rRem 2, gRem 1 -> R1, then 1 * 1 >= 1 -> N4, then R2.
        assertEquals("R1 N4 R2", q.shape())
        assertEquals(NOW + HOUR, q.nextLearningDueAt)
    }

    @Test
    fun keepGoingUnchangedByTheSetting() {
        val c = listOf(review(1, past), review(2, past + 1), card(3), card(4))
        val kg = limits(reviewsPerDay = 1, newPerDay = 0).copy(keepGoing = true)
        assertEquals(
            build(input(c, limits = kg)),
            build(input(c, limits = kg.copy(reviewsUnlimited = true))),
        )
        assertEquals("R1 R2 N3 N4", build(input(c, limits = kg.copy(reviewsUnlimited = true))).shape())
    }

    @Test
    fun newLimitZeroStillServesAllReviews() {
        val c = listOf(review(1, past), card(2))
        val l = QueueLimits(0, 0, 10, 0, true, 0).copy(reviewsUnlimited = true)
        val q = build(input(c, limits = l))
        assertEquals("R1", q.shape())
        assertFalse(q.moreAvailable, "non-empty queue")
    }
}
