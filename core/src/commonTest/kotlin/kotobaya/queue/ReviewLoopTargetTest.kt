package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.LoopResult
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueLimits
import kotobaya.contracts.queue.TodayReview
import kotobaya.contracts.queue.UntilStopParts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C-31 (ADR-051, ADR-052): the keep-going target in the loop. Worked examples W3, W4, X1, X2, X3 of
 * the `ReviewLoop` KDoc with their numbers, and the `keepGoingAvailable` / `EMPTY` definitions.
 *
 * Numbering as in [ReviewLoopTest]: reviews 1..999, new 1001.., learning 2001.., today's rows 5001...
 */
class ReviewLoopTargetTest {
    private val past = NOW - 2 * HOUR
    private val minute = 60_000L
    private val graduated = TOKYO_DAY.endMs + 3 * DAY_MS

    private fun loopLimits(unlimited: Boolean, newPerDay: Int = 40, batch: Int = 10): QueueLimits =
        QueueLimits(newPerDay, 200, 0, 0, true, 0, false, batch, unlimited)

    private fun newCards(n: Int, from: Int = 1001): List<QueueCandidate> = (from until from + n).map { card(it) }

    private fun doneToday(reviews: Int, introduced: Int): List<TodayReview> {
        var t = TOKYO_DAY.startMs
        val rows = mutableListOf<TodayReview>()
        repeat(reviews) { rows += today(5001 + rows.size, countsAsReview = true).copy(reviewedAt = ++t) }
        repeat(introduced) {
            rows += today(5001 + rows.size, type = ReviewType.LEARNING, introducedNew = true).copy(reviewedAt = ++t)
        }
        return rows
    }

    private fun eval(i: QueueInput): LoopResult = DefaultReviewLoop.evaluate(i)

    private fun QueueInput.press(target: Int?): QueueInput = copy(limits = limits.copy(keepGoing = true, keepGoingNewTarget = target))

    private fun QueueInput.leave(): QueueInput = copy(limits = limits.copy(keepGoing = false, keepGoingNewTarget = null))

    /** The client's target for a press from [r]'s state. */
    private fun nextTarget(r: LoopResult): Int = r.state.newToday + maxOf(1, r.state.newGoal)

    // ---- W3: one goal-sized batch per press ----

    private fun w3(unlimited: Boolean, introduced: Int = 40, pool: Int = 707): QueueInput =
        input(newCards(pool), limits = loopLimits(unlimited), today = doneToday(0, introduced))

    @Test
    fun w3Step1GoalMetOffersKeepGoing() {
        val r = eval(w3(unlimited = true))
        assertEquals(LoopPhase.GOAL_REACHED, r.state.phase)
        assertEquals(0, r.state.untilStop)
        assertEquals(UntilStopParts(0, 0, 0, 0), r.state.untilStopParts)
        assertTrue(r.state.keepGoingAvailable)
        assertEquals(40, r.state.newToday)
        assertEquals(80, nextTarget(r))
    }

    @Test
    fun w3Step2ThePressServesTheBatchAndCountsTheWholeTarget() {
        val r = eval(w3(unlimited = true).press(80))
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals((1001..1010).toList(), r.queue.cards())
        assertEquals(40, r.state.untilStop)
        assertEquals(UntilStopParts(0, 0, 0, 40), r.state.untilStopParts)
        assertFalse(r.state.keepGoingAvailable)
        assertTrue(r.state.keepGoing)
    }

    @Test
    fun w3Step3ADueLearningCardIsTheHeadMidBatch() {
        // 25 of the batch introduced (newToday 65): 1 due now, 3 due in 2 minutes, the rest graduated.
        val learning = listOf(learning(2001, NOW - 1000)) + (2002..2004).map { learning(it, NOW + 2 * minute) }
        val i = input(learning + newCards(682, from = 1026), limits = loopLimits(true), today = doneToday(0, 65)).press(80)
        val r = eval(i)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(listOf(2001) + (1026..1035).toList(), r.queue.cards())
        assertEquals(16, r.state.untilStop)
        assertEquals(UntilStopParts(1, 3, 0, 15), r.state.untilStopParts)
    }

    @Test
    fun w3Step4TargetReachedIsTheStopScreenWithKeepGoingOn() {
        val r = eval(w3(unlimited = true, introduced = 80, pool = 667).press(80))
        assertEquals(LoopPhase.GOAL_REACHED, r.state.phase)
        assertTrue(r.state.keepGoing)
        assertEquals(0, r.state.untilStop)
        assertEquals(UntilStopParts(0, 0, 0, 0), r.state.untilStopParts)
        assertTrue(r.state.keepGoingAvailable)
        // Had 2 of the batch still been on their timers: WAITING with the button.
        val waiting = input(
            newCards(667) + (2001..2002).map { learning(it, NOW + 2 * minute) },
            limits = loopLimits(true), today = doneToday(0, 80),
        ).press(80)
        val w = eval(waiting)
        assertEquals(LoopPhase.WAITING, w.state.phase)
        assertEquals(UntilStopParts(0, 2, 0, 0), w.state.untilStopParts)
        assertTrue(w.state.keepGoingAvailable)
    }

    @Test
    fun w3Step5PressingAgainStartsTheNextBatch() {
        val i = w3(unlimited = true, introduced = 80, pool = 667)
        val stop = eval(i.press(80))
        assertEquals(120, nextTarget(stop))
        val r = eval(i.press(nextTarget(stop)))
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(UntilStopParts(0, 0, 0, 40), r.state.untilStopParts)
    }

    @Test
    fun w3Step6PoolExhaustedBeforeTheTargetIsEmptyWithoutTheButton() {
        val start = eval(w3(unlimited = true, pool = 12).press(80))
        assertEquals(12, start.state.untilStop)
        assertEquals(UntilStopParts(0, 0, 0, 12), start.state.untilStopParts)
        val r = eval(input(emptyList(), limits = loopLimits(true), today = doneToday(0, 52)).press(80))
        assertEquals(LoopPhase.EMPTY, r.state.phase)
        assertFalse(r.state.keepGoingAvailable)
    }

    @Test
    fun w3CappedTwinEndsInDoneForToday() {
        assertEquals(LoopPhase.DONE_FOR_TODAY, eval(w3(unlimited = false)).state.phase)
        val step4 = eval(w3(unlimited = false, introduced = 80, pool = 667).press(80))
        assertEquals(LoopPhase.DONE_FOR_TODAY, step4.state.phase)
        assertTrue(step4.state.keepGoing)
        assertTrue(step4.state.keepGoingAvailable)
    }

    @Test
    fun w3PlayedThroughOneBatchPerPress() {
        // Answer Easy (graduates at once) through the batch: exactly 40 new cards, then the stop screen.
        val sim = QueueSim(w3(unlimited = true, pool = 100).press(80))
        var served = 0
        while (sim.eval().state.phase == LoopPhase.REVIEWING) {
            sim.answerHead(Ans.EASY)
            served++
        }
        assertEquals(40, served)
        val r = sim.eval()
        assertEquals(LoopPhase.GOAL_REACHED, r.state.phase)
        assertEquals(80, r.state.newToday)
        assertTrue(r.state.keepGoingAvailable)
    }

    // ---- W4: a card that comes due is next ----

    private fun w4(cDue: Long, extra: List<QueueCandidate> = emptyList(), rows: List<TodayReview> = doneToday(0, 27)): QueueInput =
        input(
            listOf(learning(2001, cDue), review(1, past)) + newCards(13, from = 1008) + extra,
            limits = QueueLimits(20, 200, 0, 0, true, 0, false, 10, false),
            today = rows,
        ).press(40)

    @Test
    fun w4DueLearningThenTheReviewThenNewCards() {
        val r = eval(w4(NOW))
        assertEquals(listOf(2001, 1) + (1008..1017).toList(), r.queue.cards())
        val before = eval(w4(NOW + 1000).copy(now = NOW))
        assertEquals(listOf(1) + (1008..1017).toList(), before.queue.cards())
        assertEquals(1, before.state.laterToday)
    }

    @Test
    fun w4OpenUnitStaysAheadOfTheReviewButNotOfTheLearningCard() {
        // n8's word requires 学; the kanji 学 (card 3001) was the last answer today (s == 0).
        val kanji = card(3001, base = NoteBaseType.KANJI, kanji = "学", state = CardState.LEARNING, due = NOW + 10 * minute)
        val rows = doneToday(0, 26) + today(3001, type = ReviewType.LEARNING, introducedNew = true, at = NOW - 60_000L)
        val i = input(
            listOf(learning(2001, NOW), review(1, past), card(1008, exprKanji = listOf("学")), kanji) + newCards(12, from = 1009),
            limits = QueueLimits(20, 200, 0, 0, true, 0, false, 10, false),
            today = rows,
        ).press(40)
        val r = eval(i)
        assertEquals(listOf(2001, 1008, 1), r.queue.cards().take(3))
        assertEquals(1009, r.queue.cards()[3])
    }

    // ---- X1: exit mid-batch ----

    private fun x1(introduced: Int, learning: List<QueueCandidate> = emptyList()): QueueInput =
        input(newCards(100, from = 1001 + introduced) + learning, limits = loopLimits(true, newPerDay = 20), today = doneToday(0, introduced))

    @Test
    fun x1ExitMidBatchOwesNothing() {
        val home0 = eval(x1(20))
        assertEquals(40, nextTarget(home0))
        val step1 = eval(x1(20).press(40))
        assertEquals(LoopPhase.REVIEWING, step1.state.phase)
        assertEquals(UntilStopParts(0, 0, 0, 20), step1.state.untilStopParts)

        // 7 answered (newToday 27), 2 of them in a step due in 5 minutes; the user leaves.
        val onTimer = (2001..2002).map { learning(it, NOW + 5 * minute) }
        val home = eval(x1(27, onTimer).press(40).leave())
        assertEquals(LoopPhase.WAITING, home.state.phase)
        assertEquals(0, home.state.untilStop) // ADR-054: waiting, the 2 are only the hint
        assertEquals(UntilStopParts(0, 2, 0, 0), home.state.untilStopParts)
        assertTrue(home.state.keepGoingAvailable)

        val graduatedHome = eval(x1(27))
        assertEquals(LoopPhase.GOAL_REACHED, graduatedHome.state.phase)
        assertEquals(UntilStopParts(0, 0, 0, 0), graduatedHome.state.untilStopParts)

        // Step 4: a fresh full batch.
        assertEquals(47, nextTarget(graduatedHome))
        val again = eval(x1(27).press(nextTarget(graduatedHome)))
        assertEquals(LoopPhase.REVIEWING, again.state.phase)
        assertEquals(UntilStopParts(0, 0, 0, 20), again.state.untilStopParts)

        // Step 5: the 2 are due on return, without keep going: served first, nothing new.
        val due = (2001..2002).map { learning(it, NOW - minute) }
        val back = eval(x1(27, due))
        assertEquals(LoopPhase.REVIEWING, back.state.phase)
        assertEquals(listOf(2001, 2002), back.queue.cards())
        assertEquals(UntilStopParts(2, 0, 0, 0), back.state.untilStopParts)
    }

    // ---- X2: target already exceeded ----

    @Test
    fun x2TargetExceededDrawsNoNewCard() {
        val withReviews = input(
            newCards(50) + (1..3).map { review(it, past + it) },
            limits = loopLimits(true, newPerDay = 20), today = doneToday(0, 43),
        ).press(40)
        val r = eval(withReviews)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(listOf(1, 2, 3), r.queue.cards())
        assertEquals(UntilStopParts(0, 0, 3, 0), r.state.untilStopParts)

        val none = eval(input(newCards(50), limits = loopLimits(true, newPerDay = 20), today = doneToday(0, 43)).press(40))
        assertEquals(LoopPhase.GOAL_REACHED, none.state.phase)
        assertTrue(none.state.keepGoingAvailable)
        assertEquals(63, nextTarget(none))
    }

    // ---- X3: newPerDay = 0, one card per press ----

    @Test
    fun x3NoNewGoalGivesOneCardPerPress() {
        val lim = loopLimits(false, newPerDay = 0)
        val start = eval(input((1..5).map { review(it, past + it) } + newCards(30), limits = lim))
        assertEquals((1..5).toList(), start.queue.cards())
        assertEquals(0, start.state.untilStopParts.new)

        val afterReviews = input(
            (1..5).map { review(it, graduated) } + newCards(30), limits = lim, today = doneToday(5, 0),
        )
        val stop = eval(afterReviews)
        assertEquals(LoopPhase.DONE_FOR_TODAY, stop.state.phase)
        assertTrue(stop.state.keepGoingAvailable)
        assertEquals(1, nextTarget(stop))

        val press = eval(afterReviews.press(1))
        assertEquals(LoopPhase.REVIEWING, press.state.phase)
        assertEquals(listOf(1001), press.queue.cards())
        assertEquals(UntilStopParts(0, 0, 0, 1), press.state.untilStopParts)

        val sim = QueueSim(afterReviews.press(1))
        sim.answerHead(Ans.EASY)
        val again = sim.eval()
        assertEquals(LoopPhase.DONE_FOR_TODAY, again.state.phase)
        assertTrue(again.state.keepGoingAvailable)
        assertEquals(2, nextTarget(again))
    }

    // ---- keepGoingAvailable / EMPTY definitions (ADR-051) ----

    @Test
    fun emptyMeansKeepGoingHasNothingAndStopScreensAlwaysOfferIt() {
        for (seed in 1..300) {
            for (kg in listOf(false, true)) {
                val base = QueueRandom.input(seed, keepGoing = false)
                val i = if (kg) base.press(base.today.count { it.introducedNew } + maxOf(1, base.limits.newPerDay)) else base
                val r = eval(i)
                val kInf = build(i.copy(limits = i.limits.copy(keepGoing = true, keepGoingNewTarget = null, learnAheadMs = 0)))
                val kInfNonEmpty = kInf.items.isNotEmpty() || kInf.toMaterialize.isNotEmpty() || kInf.kanjiToMaterialize.isNotEmpty()
                when (r.state.phase) {
                    LoopPhase.EMPTY -> {
                        assertFalse(kInfNonEmpty, "seed $seed: EMPTY but K∞ non-empty")
                        assertFalse(r.state.keepGoingAvailable, "seed $seed")
                    }
                    LoopPhase.GOAL_REACHED, LoopPhase.DONE_FOR_TODAY -> {
                        assertTrue(r.state.keepGoingAvailable, "seed $seed: stop screen without Keep going")
                        val pressed = eval(i.press(r.state.newToday + maxOf(1, r.state.newGoal)))
                        assertEquals(LoopPhase.REVIEWING, pressed.state.phase, "seed $seed: dead end")
                    }
                    else -> {}
                }
            }
        }
        // And the plain cases: the target reached with new cards left vs. nothing left at all.
        val reached = eval(input(newCards(5), limits = loopLimits(true, newPerDay = 2), today = doneToday(0, 4)).press(4))
        assertEquals(LoopPhase.GOAL_REACHED, reached.state.phase)
        assertTrue(reached.state.keepGoingAvailable)
        val nothing = eval(input(emptyList(), limits = loopLimits(false, newPerDay = 2), today = doneToday(0, 4)).press(4))
        assertEquals(LoopPhase.EMPTY, nothing.state.phase)
        assertFalse(nothing.state.keepGoingAvailable)
    }
}
