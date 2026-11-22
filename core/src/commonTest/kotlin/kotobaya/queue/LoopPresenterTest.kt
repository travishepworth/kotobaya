package kotobaya.queue

import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.LegacyCounts
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.LoopPresentation
import kotobaya.contracts.queue.LoopScreen
import kotobaya.contracts.queue.LoopState
import kotobaya.contracts.queue.PendingAnswer
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueItemKind.LEARNING
import kotobaya.contracts.queue.QueueItemKind.NEW
import kotobaya.contracts.queue.QueueItemKind.REVIEW
import kotobaya.contracts.queue.UntilStopParts
import kotlin.test.Test
import kotlin.test.assertEquals

/** C-31 `LoopPresenter` worked examples P1 … P10 and the KDoc's edge cases. Parts are `(learning, later, review, new)`; `untilStop` = learning + review + new (ADR-054). */
class LoopPresenterTest {
    private val now = NOW
    private val d = TOKYO_DAY.endMs

    private fun state(phase: LoopPhase, l: Int, la: Int, rv: Int, n: Int) = LoopState(
        phase = phase, keepGoing = false, readyLearning = l, readyReviews = rv, laterToday = la, nextDueAt = null,
        backlog = rv, backlogBeyondLimit = 0, reviewsToday = 0, reviewsLimit = 200, newToday = 0, newGoal = 40,
        newAvailable = n > 0, legacy = LegacyCounts(n, l, rv, false), untilStop = l + rv + n,
        keepGoingAvailable = false, untilStopParts = UntilStopParts(l, la, rv, n),
    )

    private fun reviewing(l: Int, la: Int, rv: Int, n: Int) = state(LoopPhase.REVIEWING, l, la, rv, n)

    private fun pa(kind: QueueItemKind, rating: Int, st: CardState?, due: Long?) = PendingAnswer(kind, rating, st, due)

    private fun present(s: LoopState, pending: List<PendingAnswer> = emptyList(), head: QueueItemKind? = null, held: Boolean = head != null, dayEnd: Long = d) =
        DefaultLoopPresenter.present(s, pending, held, head, dayEnd)

    private fun expect(screen: LoopScreen, l: Int, la: Int, rv: Int, n: Int) = LoopPresentation(screen, UntilStopParts(l, la, rv, n), l + rv + n)

    private val againReview = pa(REVIEW, 1, CardState.RELEARNING, now + 600_000)
    private val newGood = pa(NEW, 3, CardState.LEARNING, now + 600_000)

    @Test
    fun p1NothingPending() {
        val got = present(reviewing(0, 5, 30, 30), head = REVIEW)
        assertEquals(expect(LoopScreen.CARD, 0, 5, 30, 30), got)
        assertEquals(60, got.untilStop)
    }

    @Test
    fun p2AgainOnAReview() {
        val got = present(reviewing(0, 5, 30, 30), listOf(againReview), REVIEW)
        assertEquals(expect(LoopScreen.CARD, 0, 6, 29, 30), got)
        assertEquals(59, got.untilStop)
    }

    @Test
    fun p3NewToLearning() {
        assertEquals(expect(LoopScreen.CARD, 0, 7, 29, 29), present(reviewing(0, 5, 30, 30), listOf(againReview, newGood), REVIEW))
    }

    @Test
    fun p4LearningGraduating() {
        val got = present(reviewing(2, 1, 0, 0), listOf(pa(LEARNING, 3, CardState.REVIEW, now + 172_800_000)), LEARNING)
        assertEquals(expect(LoopScreen.CARD, 1, 1, 0, 0), got)
        assertEquals(1, got.untilStop)
    }

    @Test
    fun p5SiblingBuryMovesOnlyTheAnsweredCard() {
        val got = present(reviewing(0, 5, 30, 30), listOf(pa(REVIEW, 3, CardState.REVIEW, now + 5 * 86_400_000L)), REVIEW)
        assertEquals(expect(LoopScreen.CARD, 0, 5, 29, 30), got)
    }

    @Test
    fun p6DayEnd() {
        val s = reviewing(1, 0, 0, 0)
        assertEquals(expect(LoopScreen.SETTLING, 0, 0, 0, 0), present(s, listOf(pa(LEARNING, 1, CardState.LEARNING, d + 480_000))))
        assertEquals(expect(LoopScreen.GOAL_REACHED, 0, 0, 0, 0), present(state(LoopPhase.GOAL_REACHED, 0, 0, 0, 0)))
        // Had it been due at D − 1: it comes back today.
        assertEquals(expect(LoopScreen.SETTLING, 0, 1, 0, 0), present(s, listOf(pa(LEARNING, 1, CardState.LEARNING, d - 1))))
        assertEquals(expect(LoopScreen.WAITING, 0, 1, 0, 0), present(state(LoopPhase.WAITING, 0, 1, 0, 0)))
    }

    @Test
    fun p7LastCardAnsweredStillInTheOutbox() {
        assertEquals(expect(LoopScreen.SETTLING, 0, 0, 0, 0), present(reviewing(0, 0, 1, 0), listOf(pa(REVIEW, 3, null, null))))
        assertEquals(expect(LoopScreen.DONE_FOR_TODAY, 0, 0, 0, 0), present(state(LoopPhase.DONE_FOR_TODAY, 0, 0, 0, 0)))
    }

    @Test
    fun p8ReviewingWithNothingHeldIsSettling() {
        assertEquals(expect(LoopScreen.SETTLING, 0, 0, 0, 12), present(reviewing(0, 0, 0, 12)))
    }

    @Test
    fun p9HeldCardWithAStaleTerminalState() {
        assertEquals(expect(LoopScreen.CARD, 1, 0, 0, 0), present(state(LoopPhase.GOAL_REACHED, 0, 0, 0, 0), head = LEARNING))
    }

    @Test
    fun p10KeepGoingBatch() {
        val s = reviewing(0, 0, 0, 40).copy(keepGoing = true)
        val got = present(s, List(3) { pa(NEW, 3, CardState.LEARNING, now + 60_000) }, NEW)
        assertEquals(expect(LoopScreen.CARD, 0, 3, 0, 37), got)
        assertEquals(37, got.untilStop)
    }

    @Test
    fun everyPhaseMapsToItsScreenWhenNothingIsPendingOrHeld() {
        val map = mapOf(
            LoopPhase.REVIEWING to LoopScreen.SETTLING, LoopPhase.WAITING to LoopScreen.WAITING,
            LoopPhase.GOAL_REACHED to LoopScreen.GOAL_REACHED, LoopPhase.DONE_FOR_TODAY to LoopScreen.DONE_FOR_TODAY,
            LoopPhase.EMPTY to LoopScreen.EMPTY,
        )
        for ((phase, screen) in map) assertEquals(screen, present(state(phase, 0, 0, 0, 0)).screen, "$phase")
        assertEquals(LoopScreen.entries.size - 1, map.size)
    }

    @Test
    fun pendingAnswersNeverGiveATerminalScreen() {
        // A terminal state with an answer pending (a state newer than the client thinks): SETTLING.
        for (phase in listOf(LoopPhase.GOAL_REACHED, LoopPhase.DONE_FOR_TODAY, LoopPhase.EMPTY, LoopPhase.WAITING)) {
            assertEquals(expect(LoopScreen.SETTLING, 0, 0, 0, 0), present(state(phase, 0, 0, 0, 0), listOf(pa(REVIEW, 3, CardState.REVIEW, d + 1))), "$phase")
        }
    }

    @Test
    fun morePendingAnswersThanCountedStopAtZero() {
        val pending = List(5) { pa(REVIEW, 3, CardState.REVIEW, d + 1) } + List(4) { pa(NEW, 4, CardState.REVIEW, d + 1) } +
            List(3) { pa(LEARNING, 4, CardState.REVIEW, d + 1) }
        assertEquals(expect(LoopScreen.SETTLING, 0, 0, 0, 0), present(reviewing(1, 1, 2, 1), pending))
        assertEquals(expect(LoopScreen.CARD, 0, 0, 1, 0), present(reviewing(1, 1, 2, 1), pending, REVIEW))
    }

    @Test
    fun aLostPartStillGainsLater() {
        // Nothing to take (review 0), the gain applies.
        assertEquals(expect(LoopScreen.SETTLING, 0, 1, 0, 0), present(reviewing(0, 0, 0, 0), listOf(againReview)))
    }

    @Test
    fun learningTakesFromLaterWhenLearningIsZero() {
        assertEquals(expect(LoopScreen.SETTLING, 0, 1, 0, 0), present(reviewing(0, 2, 0, 0), listOf(pa(LEARNING, 3, CardState.REVIEW, d + 1))))
        assertEquals(expect(LoopScreen.SETTLING, 0, 2, 0, 0), present(reviewing(0, 2, 0, 0), listOf(pa(LEARNING, 1, CardState.LEARNING, now + 60_000))))
        assertEquals(expect(LoopScreen.SETTLING, 0, 0, 0, 0), present(reviewing(0, 0, 0, 0), listOf(pa(LEARNING, 3, CardState.REVIEW, d + 1))))
    }

    @Test
    fun duplicatePendingAnswersCountTwice() {
        assertEquals(expect(LoopScreen.CARD, 0, 7, 28, 30), present(reviewing(0, 5, 30, 30), listOf(againReview, againReview), REVIEW))
    }

    @Test
    fun orderDoesNotMatterAwayFromZero() {
        val list = listOf(againReview, newGood, pa(LEARNING, 3, CardState.REVIEW, d + 1), pa(REVIEW, 3, CardState.REVIEW, d + 1))
        val s = reviewing(2, 3, 10, 10)
        assertEquals(present(s, list, REVIEW), present(s, list.reversed(), REVIEW))
        assertEquals(expect(LoopScreen.CARD, 1, 5, 8, 9), present(s, list, REVIEW))
    }

    @Test
    fun dayEndsAtInThePastMakesEveryLearningOutcomeLeave() {
        val past = now - HOUR
        val got = present(reviewing(1, 0, 1, 1), listOf(pa(NEW, 1, CardState.LEARNING, now + 60_000), pa(REVIEW, 1, CardState.RELEARNING, now + 600_000)), dayEnd = past)
        assertEquals(expect(LoopScreen.SETTLING, 1, 0, 0, 0), got)
    }

    @Test
    fun dueAtNullWithALearningResultReturnsToday() {
        assertEquals(expect(LoopScreen.SETTLING, 0, 1, 0, 0), present(reviewing(0, 0, 1, 0), listOf(pa(REVIEW, 1, CardState.RELEARNING, null))))
    }

    @Test
    fun unknownOutcomePerKindAndRating() {
        val s = reviewing(1, 0, 1, 1)
        fun one(kind: QueueItemKind, rating: Int) = present(s, listOf(pa(kind, rating, null, null))).parts
        // NEW: always returns today.
        for (rt in 1..4) assertEquals(UntilStopParts(1, 1, 1, 0), one(NEW, rt), "NEW $rt")
        // REVIEW: Again returns, Hard/Good/Easy leave.
        assertEquals(UntilStopParts(1, 1, 0, 1), one(REVIEW, 1))
        for (rt in 2..4) assertEquals(UntilStopParts(1, 0, 0, 1), one(REVIEW, rt), "REVIEW $rt")
        // LEARNING: Again/Hard return, Good/Easy leave.
        for (rt in 1..2) assertEquals(UntilStopParts(0, 1, 1, 1), one(LEARNING, rt), "LEARNING $rt")
        for (rt in 3..4) assertEquals(UntilStopParts(0, 0, 1, 1), one(LEARNING, rt), "LEARNING $rt")
    }

    @Test
    fun ratingsOutsideOneToFourAreAgain() {
        val s = reviewing(1, 0, 1, 1)
        for (rt in listOf(0, 5, -3, 99)) {
            assertEquals(UntilStopParts(1, 1, 0, 1), present(s, listOf(pa(REVIEW, rt, null, null))).parts, "REVIEW $rt")
            assertEquals(UntilStopParts(0, 1, 1, 1), present(s, listOf(pa(LEARNING, rt, null, null))).parts, "LEARNING $rt")
        }
    }

    @Test
    fun resultingStateNewIsTreatedAsUnknown() {
        val s = reviewing(1, 0, 1, 1)
        assertEquals(present(s, listOf(pa(REVIEW, 3, null, null))), present(s, listOf(pa(REVIEW, 3, CardState.NEW, now + 1))))
        assertEquals(present(s, listOf(pa(REVIEW, 1, null, null))), present(s, listOf(pa(REVIEW, 1, CardState.NEW, d + 1))))
    }

    @Test
    fun floorCountsTheHeldCard() {
        // Held NEW / REVIEW with that part at 0.
        assertEquals(expect(LoopScreen.CARD, 0, 0, 0, 1), present(reviewing(0, 0, 0, 0), head = NEW))
        assertEquals(expect(LoopScreen.CARD, 0, 0, 1, 0), present(reviewing(0, 0, 0, 0), head = REVIEW))
        // A held learning card came due: it moves from later to learning.
        assertEquals(expect(LoopScreen.CARD, 1, 1, 0, 0), present(reviewing(0, 2, 0, 0), head = LEARNING))
        // The floor is only for a held card, and only when the part is 0.
        assertEquals(expect(LoopScreen.CARD, 2, 2, 0, 0), present(reviewing(2, 2, 0, 0), head = LEARNING))
        assertEquals(expect(LoopScreen.SETTLING, 0, 2, 0, 0), present(reviewing(0, 2, 0, 0)))
    }

    @Test
    fun floorAppliesAfterTheProjection() {
        // The last review answered Good, the next held card is a review the state did not count.
        assertEquals(expect(LoopScreen.CARD, 0, 0, 1, 0), present(reviewing(0, 0, 1, 0), listOf(pa(REVIEW, 3, CardState.REVIEW, d + 1)), REVIEW))
    }

    @Test
    fun heldCardWithANullKindIsCountedAsAReview() {
        assertEquals(expect(LoopScreen.CARD, 0, 0, 1, 0), present(state(LoopPhase.EMPTY, 0, 0, 0, 0), head = null, held = true))
    }

    @Test
    fun heldCardWithATerminalStateAndNoPendingShowsTheCard() {
        for (phase in listOf(LoopPhase.GOAL_REACHED, LoopPhase.DONE_FOR_TODAY, LoopPhase.EMPTY, LoopPhase.WAITING)) {
            assertEquals(expect(LoopScreen.CARD, 0, 0, 0, 1), present(state(phase, 0, 0, 0, 0), head = NEW), "$phase")
        }
    }
}
