package kotobaya.queue

import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.LoopScreen
import kotobaya.contracts.queue.PendingAnswer
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueLimits
import kotobaya.contracts.queue.TodayReview
import kotobaya.contracts.queue.UntilStopParts
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR-054 (C-34, LOOP-34): `untilStop` counts only the cards doable right now
 * (`learning + review + new`); `later` is only the later-today hint.
 *
 * Numbering as in [ReviewLoopTest]: new 1001.., learning 2001.., today's rows 5001...
 */
class ReviewLoopReadyNowTest {
    private val minute = 60_000L

    private fun doneToday(introduced: Int): List<TodayReview> {
        var t = TOKYO_DAY.startMs
        return (0 until introduced).map {
            today(5001 + it, type = ReviewType.LEARNING, introducedNew = true).copy(reviewedAt = ++t)
        }
    }

    /**
     * The user report of 2026-11-22: Home "4 to review", header "of 15"; live state ready 2,
     * later_today 13, goal met. The header total must be 2, not 15.
     */
    @Test
    fun reportedCaseTwoReadyThirteenLaterGoalMetCountsTwo() {
        val cards = (2001..2002).map { learning(it, NOW - minute + it - 2001) } +
            (2003..2015).map { learning(it, NOW + 30_000L + (it - 2003) * 30_000L) } +
            (1001..1100).map { card(it) }
        val limits = QueueLimits(40, 200, 0, 0, true, 0, false, 10, true)
        val r = DefaultReviewLoop.evaluate(input(cards, limits = limits, today = doneToday(40)))
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(2, r.state.readyLearning)
        assertEquals(13, r.state.laterToday)
        assertEquals(UntilStopParts(learning = 2, later = 13, review = 0, new = 0), r.state.untilStopParts)
        assertEquals(2, r.state.untilStop)

        // Both answered Good (they graduate): nothing doable now, the loop waits, the total is 0.
        val sim = QueueSim(input(cards, limits = limits, today = doneToday(40)))
        repeat(2) { sim.answerHead(Ans.GOOD) }
        val w = sim.eval()
        assertEquals(LoopPhase.WAITING, w.state.phase)
        assertEquals(13, w.state.laterToday)
        assertEquals(0, w.state.untilStop)

        // The presenter on the waiting state shows 0 too; `later` stays in the hint.
        val pres = DefaultLoopPresenter.present(w.state, emptyList(), false, null, TOKYO_DAY.endMs)
        assertEquals(LoopScreen.WAITING, pres.screen)
        assertEquals(0, pres.untilStop)
        assertEquals(13, pres.parts.later)
    }

    /**
     * ADR-054 "one number everywhere": Home (no card held, nothing pending) and the review header at done = 0 (the
     * head card held, nothing pending) show the same total, `state.untilStop`, also with new cards and later ones.
     */
    @Test
    fun homeNumberEqualsHeaderTotalAtDoneZero() {
        val cards = (2001..2002).map { learning(it, NOW - minute + it - 2001) } +
            (2003..2015).map { learning(it, NOW + 30 * minute) } +
            (1..3).map { review(it, NOW - 2 * HOUR + it) } +
            (1001..1100).map { card(it) }
        val r = DefaultReviewLoop.evaluate(input(cards, limits = QueueLimits(10, 200, 0, 0, true, 0, false, 10, true), today = doneToday(4)))
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(2 + 3 + 6, r.state.untilStop)
        val home = DefaultLoopPresenter.present(r.state, emptyList(), false, null, TOKYO_DAY.endMs)
        val header = DefaultLoopPresenter.present(r.state, emptyList(), true, r.queue.items.first().kind, TOKYO_DAY.endMs)
        assertEquals(r.state.untilStop, home.untilStop)
        assertEquals(home.untilStop, header.untilStop)
        assertEquals(home.parts, header.parts)
    }

    /** Acceptance: a pending answer whose card returns today moves it into `later` and out of `untilStop`. */
    @Test
    fun pendingAnswerThatReturnsTodayLeavesUntilStop() {
        val state = DefaultReviewLoop.evaluate(
            input(
                (2001..2002).map { learning(it, NOW - minute + it - 2001) } + (2003..2015).map { learning(it, NOW + 30 * minute) },
                limits = QueueLimits(40, 200, 0, 0, true, 0, false, 10, true),
                today = doneToday(40),
            ),
        ).state
        assertEquals(2, state.untilStop)
        val again = PendingAnswer(QueueItemKind.LEARNING, 1, CardState.LEARNING, NOW + 10 * minute)
        // One answered Again (returns today), the other still held.
        val held = DefaultLoopPresenter.present(state, listOf(again), true, QueueItemKind.LEARNING, TOKYO_DAY.endMs)
        assertEquals(LoopScreen.CARD, held.screen)
        assertEquals(UntilStopParts(learning = 1, later = 14, review = 0, new = 0), held.parts)
        assertEquals(1, held.untilStop)
        // Both answered Again, nothing held: settling with 0, never a stale "2".
        val both = DefaultLoopPresenter.present(state, listOf(again, again), false, null, TOKYO_DAY.endMs)
        assertEquals(LoopScreen.SETTLING, both.screen)
        assertEquals(UntilStopParts(learning = 0, later = 15, review = 0, new = 0), both.parts)
        assertEquals(0, both.untilStop)
    }
}
