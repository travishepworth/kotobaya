package kotobaya.queue

import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.LegacyCounts
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.LoopResult
import kotobaya.contracts.queue.LoopState
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
 * ADR-049 (C-30): the loop waits for today's learning cards before any stop screen, in both modes;
 * `keepGoingAvailable` and `untilStopParts`. Worked examples W1 / W2 of the `ReviewLoop` KDoc, the
 * reported case and the edge cases of contracts/core/README "ADR-049".
 *
 * Numbering as in [ReviewLoopTest]: reviews 1..999, new 1001.., learning 2001.., today's rows 5001...
 */
class ReviewLoopWaitTest {
    private val past = NOW - 2 * HOUR
    private val minute = 60_000L

    private fun loopLimits(
        unlimited: Boolean,
        keepGoing: Boolean = false,
        newPerDay: Int = 40,
        reviewsPerDay: Int = 200,
        batch: Int = 10,
    ): QueueLimits = QueueLimits(newPerDay, reviewsPerDay, 0, 0, true, 0, keepGoing, batch, unlimited)

    private fun dueReviews(n: Int): List<QueueCandidate> = (1..n).map { review(it, past + it) }

    private fun newCards(n: Int): List<QueueCandidate> = (1001 until 1001 + n).map { card(it) }

    private fun relearning(n: Int, due: Long): QueueCandidate = card(n, state = CardState.RELEARNING, due = due)

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

    private fun QueueInput.keepGoing(batch: Int = limits.keepGoingNewBatch): QueueInput =
        copy(limits = limits.copy(keepGoing = true, keepGoingNewBatch = batch))

    /** Mode-independent numbers only. */
    private fun LoopState.counts(): LoopState =
        copy(phase = LoopPhase.EMPTY, keepGoing = false, untilStop = 0, keepGoingAvailable = false, untilStopParts = UntilStopParts())

    private fun UntilStopParts.sum(): Int = learning + later + review + new

    /** Graduates card [n] past today (no `today` row needed for the loop's phase). */
    private fun QueueInput.graduate(vararg n: Int): QueueInput {
        val ids = n.map { cardId(it) }.toSet()
        return copy(candidates = candidates.map { if (it.cardId in ids) it.copy(state = CardState.REVIEW, dueAt = day.endMs + 3 * DAY_MS) else it })
    }

    // ---- W1: the reported case (goal 40/40, 9 ready, 14 later, 0 reviews, 707 new) ----

    /** 9 learning cards due (2001..2009), 14 later today within 8 minutes (2010..2023). */
    private fun w1Learning(): List<QueueCandidate> =
        (2001..2009).map { learning(it, NOW - minute + it - 2001) } +
            (2010..2023).map { learning(it, NOW + 30_000L + (it - 2010) * 30_000L) }

    private fun w1(unlimited: Boolean = true): QueueInput =
        input(w1Learning() + newCards(707), limits = loopLimits(unlimited = unlimited), today = doneToday(0, 40))

    @Test
    fun w1Step1ServesTheReadyLearningWithTheFullHeader() {
        val r = eval(w1())
        assertEquals((2001..2009).toList(), r.queue.cards())
        assertEquals(
            LoopState(
                phase = LoopPhase.REVIEWING, keepGoing = false, readyLearning = 9, readyReviews = 0,
                laterToday = 14, nextDueAt = NOW + 30_000L, backlog = 0, backlogBeyondLimit = 0,
                reviewsToday = 0, reviewsLimit = null, newToday = 40, newGoal = 40, newAvailable = true,
                legacy = LegacyCounts(new = 0, learning = 23, review = 0, moreAvailable = false),
                untilStop = 23,
                keepGoingAvailable = false,
                untilStopParts = UntilStopParts(learning = 9, later = 14, review = 0, new = 0),
            ),
            r.state,
        )
    }

    @Test
    fun w1Step2NothingReadyWaitsInsteadOfGoalReached() {
        // The reported instant: none of the 23 is due.
        val i = input(
            (2001..2023).map { learning(it, NOW + 23_000L + (it - 2001) * 20_000L) } + newCards(707),
            limits = loopLimits(unlimited = true),
            today = doneToday(0, 40),
        )
        val r = eval(i)
        assertEquals(LoopPhase.WAITING, r.state.phase)
        assertTrue(r.queue.items.isEmpty())
        assertEquals(0, r.state.readyLearning)
        assertEquals(23, r.state.laterToday)
        assertEquals(NOW + 23_000L, r.state.nextDueAt)
        assertEquals(23, r.state.untilStop)
        assertEquals(UntilStopParts(0, 23, 0, 0), r.state.untilStopParts)
        assertTrue(r.state.keepGoingAvailable)
        assertTrue(r.state.legacy.moreAvailable)

        // Step 3: the same instant with keep going, batch 10: new cards, no learning card served early.
        val kg = eval(i.keepGoing(batch = 10))
        assertEquals(LoopPhase.REVIEWING, kg.state.phase)
        assertEquals((1001..1010).toList(), kg.queue.cards())
        assertTrue(kg.state.keepGoing)
        assertFalse(kg.state.keepGoingAvailable)
        assertEquals(33, kg.state.untilStop)
        assertEquals(UntilStopParts(0, 23, 0, 10), kg.state.untilStopParts)
        assertEquals(r.state.counts(), kg.state.counts())
    }

    @Test
    fun reportedCaseAfterTheNineAreAnsweredWaitsForTheFourteen() {
        val sim = QueueSim(w1())
        val first = sim.eval()
        assertEquals(LoopPhase.REVIEWING, first.state.phase)
        // Answer the 9 ready cards; they graduate past today.
        repeat(9) {
            val head = sim.answerHead(Ans.GOOD)
            assertTrue(head in 2001..2009, "served $head")
        }
        val r = sim.eval()
        assertEquals(LoopPhase.WAITING, r.state.phase, "not GOAL_REACHED while 14 learning cards are on their timer")
        assertTrue(r.queue.items.isEmpty())
        assertEquals(0, r.state.readyLearning)
        assertEquals(14, r.state.laterToday)
        assertEquals(NOW + 30_000L, r.state.nextDueAt)
        assertEquals(14, r.state.untilStop)
        assertEquals(UntilStopParts(0, 14, 0, 0), r.state.untilStopParts)
        assertEquals(r.state.untilStop, r.state.untilStopParts.sum())
        assertTrue(r.state.keepGoingAvailable)

        // Answered Again instead: they stay in learning and are later today too (9 + 14 = 23).
        val again = QueueSim(w1())
        repeat(9) { again.answerHead(Ans.AGAIN) }
        val ra = again.eval()
        assertEquals(LoopPhase.WAITING, ra.state.phase)
        assertEquals(23, ra.state.laterToday)
        assertEquals(23, ra.state.untilStop)
        assertEquals(UntilStopParts(0, 23, 0, 0), ra.state.untilStopParts)
    }

    @Test
    fun w1PlayedThroughEndsInGoalReachedOnlyWhenNothingIsLeftForToday() {
        for (unlimited in listOf(true, false)) {
            val sim = QueueSim(w1(unlimited))
            var waits = 0
            var guard = 0
            while (true) {
                val r = sim.eval()
                assertEquals(r.state.untilStop, r.state.untilStopParts.sum())
                when (r.state.phase) {
                    LoopPhase.REVIEWING -> {
                        assertTrue(r.queue.items.all { it.kind == kotobaya.contracts.queue.QueueItemKind.LEARNING })
                        sim.answerHead(Ans.GOOD)
                    }
                    LoopPhase.WAITING -> {
                        waits++
                        assertTrue(r.state.keepGoingAvailable)
                        sim.input = sim.input.copy(now = r.state.nextDueAt ?: error("waiting without nextDueAt"))
                    }
                    else -> break
                }
                assertTrue(++guard < 200)
            }
            val end = sim.eval().state
            assertTrue(waits > 0)
            assertEquals(if (unlimited) LoopPhase.GOAL_REACHED else LoopPhase.DONE_FOR_TODAY, end.phase)
            assertEquals(0, end.laterToday)
            assertEquals(0, end.untilStop)
            assertEquals(UntilStopParts(0, 0, 0, 0), end.untilStopParts)
            assertTrue(end.keepGoingAvailable)
            assertEquals(40, end.newToday)
        }
    }

    // ---- W2: capped, backlog beyond the cap, 2 relearning cards on their timer ----

    @Test
    fun w2WaitsForRelearningBeforeDoneForToday() {
        val c = dueReviews(335).drop(200) + newCards(100) + listOf(relearning(2001, NOW + 6 * minute), relearning(2002, NOW + 6 * minute + 1))
        val i = input(c, limits = loopLimits(unlimited = false), today = doneToday(200, 40))
        val r = eval(i)
        assertEquals(LoopPhase.WAITING, r.state.phase)
        assertTrue(r.state.keepGoingAvailable)
        assertEquals(2, r.state.untilStop)
        assertEquals(UntilStopParts(0, 2, 0, 0), r.state.untilStopParts)
        assertEquals(NOW + 6 * minute, r.state.nextDueAt)
        assertEquals(135, r.state.backlogBeyondLimit)

        val kg = eval(i.keepGoing())
        assertEquals(LoopPhase.REVIEWING, kg.state.phase)
        assertEquals((201..335).toList() + (1001..1010).toList(), kg.queue.cards())
        assertEquals(147, kg.state.untilStop)
        assertEquals(UntilStopParts(0, 2, 135, 10), kg.state.untilStopParts)
        assertFalse(kg.state.keepGoingAvailable)
        assertEquals(r.state.counts(), kg.state.counts())

        // After the 2 graduate: B3.
        val b3 = eval(i.graduate(2001, 2002))
        assertEquals(LoopPhase.DONE_FOR_TODAY, b3.state.phase)
        assertEquals(0, b3.state.untilStop)
        assertEquals(UntilStopParts(0, 0, 0, 0), b3.state.untilStopParts)
        assertTrue(b3.state.keepGoingAvailable)
    }

    // ---- Worked examples gain parts and keepGoingAvailable ----

    @Test
    fun workedExamplesPartsAndKeepGoingAvailable() {
        // A1
        val laterLearning = (2001 until 2027).map { learning(it, NOW + (5 + it - 2001) * minute) }
        val a1 = eval(input(laterLearning + newCards(100), limits = loopLimits(unlimited = true))).state
        assertEquals(UntilStopParts(0, 26, 0, 40), a1.untilStopParts)
        assertFalse(a1.keepGoingAvailable)
        // A3
        val a3 = eval(input(newCards(200), limits = loopLimits(unlimited = true), today = doneToday(0, 40))).state
        assertEquals(LoopPhase.GOAL_REACHED, a3.phase)
        assertEquals(UntilStopParts(0, 0, 0, 0), a3.untilStopParts)
        assertTrue(a3.keepGoingAvailable)
        // B3 / B4
        val b3i = input(dueReviews(335).drop(200) + newCards(100), limits = loopLimits(unlimited = false), today = doneToday(200, 40))
        val b3 = eval(b3i).state
        assertEquals(UntilStopParts(0, 0, 0, 0), b3.untilStopParts)
        assertTrue(b3.keepGoingAvailable)
        val b4 = eval(b3i.keepGoing()).state
        assertEquals(UntilStopParts(0, 0, 135, 10), b4.untilStopParts)
        assertFalse(b4.keepGoingAvailable)
        // U1
        val u1Later = (2001 until 2006).map { learning(it, NOW + (5 + it - 2001) * minute) }
        val u1 = eval(input(dueReviews(30) + u1Later + newCards(200), limits = loopLimits(unlimited = false), today = doneToday(0, 10))).state
        assertEquals(UntilStopParts(0, 5, 30, 30), u1.untilStopParts)
        assertFalse(u1.keepGoingAvailable)
        // B6: waiting, nothing new, no backlog.
        for (kgo in listOf(false, true)) {
            val b6 = eval(input(laterLearning.take(8), limits = loopLimits(unlimited = false, keepGoing = kgo), today = doneToday(200, 40))).state
            assertEquals(LoopPhase.WAITING, b6.phase)
            assertFalse(b6.keepGoingAvailable)
            assertEquals(UntilStopParts(0, 8, 0, 0), b6.untilStopParts)
        }
    }

    // ---- Edge cases ----

    @Test
    fun learningDueAtDayEndDoesNotHoldTheDayOpen() {
        val base = input(newCards(5), limits = loopLimits(unlimited = true), today = doneToday(0, 40))
        val atEnd = eval(base.copy(candidates = base.candidates + learning(2001, TOKYO_DAY.endMs)))
        assertEquals(LoopPhase.GOAL_REACHED, atEnd.state.phase)
        assertEquals(0, atEnd.state.laterToday)
        assertEquals(null, atEnd.state.nextDueAt)
        assertEquals(0, atEnd.state.untilStop)
        assertEquals(0, atEnd.state.legacy.learning)
        assertTrue(atEnd.state.keepGoingAvailable)

        val before = eval(base.copy(candidates = base.candidates + learning(2001, TOKYO_DAY.endMs - 1)))
        assertEquals(LoopPhase.WAITING, before.state.phase)
        assertEquals(1, before.state.laterToday)
        assertEquals(TOKYO_DAY.endMs - 1, before.state.nextDueAt)
        assertEquals(1, before.state.untilStop)
        assertEquals(UntilStopParts(0, 1, 0, 0), before.state.untilStopParts)
        assertTrue(before.state.keepGoingAvailable)

        // Keep going never serves the day-end card early.
        val kg = eval(base.copy(candidates = base.candidates + learning(2001, TOKYO_DAY.endMs)).keepGoing())
        assertFalse(cardId(2001) in kg.queue.items.map { it.cardId })
    }

    @Test
    fun dueAtNowIsReadyAndNowPlusOneIsLater() {
        val base = input(newCards(5), limits = loopLimits(unlimited = true), today = doneToday(0, 40))
        val ready = eval(base.copy(candidates = base.candidates + learning(2001, NOW)))
        assertEquals(LoopPhase.REVIEWING, ready.state.phase)
        assertEquals(1, ready.state.readyLearning)
        assertEquals(UntilStopParts(1, 0, 0, 0), ready.state.untilStopParts)
        assertFalse(ready.state.keepGoingAvailable)
        val later = eval(base.copy(candidates = base.candidates + learning(2001, NOW + 1)))
        assertEquals(LoopPhase.WAITING, later.state.phase)
        assertEquals(NOW + 1, later.state.nextDueAt)
    }

    @Test
    fun lastWaitingCardSuspendedOrBuriedEndsTheWait() {
        val base = input(newCards(5) + learning(2001, NOW + minute), limits = loopLimits(unlimited = false), today = doneToday(0, 40))
        assertEquals(LoopPhase.WAITING, eval(base).state.phase)
        fun QueueInput.edit(f: (QueueCandidate) -> QueueCandidate) =
            copy(candidates = candidates.map { if (it.cardId == cardId(2001)) f(it) else it })
        val suspended = eval(base.edit { it.copy(suspended = true) }).state
        assertEquals(LoopPhase.DONE_FOR_TODAY, suspended.phase)
        assertEquals(0, suspended.laterToday)
        val buried = eval(base.edit { it.copy(buriedUntil = TOKYO_DAY.endMs) }).state
        assertEquals(LoopPhase.DONE_FOR_TODAY, buried.phase)
        // Nothing for keep going either: rule 4.
        val alone = input(listOf(learning(2001, NOW + minute)), limits = loopLimits(unlimited = false), today = doneToday(0, 40))
        assertEquals(LoopPhase.EMPTY, eval(alone.edit { it.copy(suspended = true) }).state.phase)
    }

    @Test
    fun keepGoingRunsDryWhileLearningIsOnItsTimer() {
        val i = input(listOf(learning(2001, NOW + minute)), limits = loopLimits(unlimited = true, keepGoing = true), today = doneToday(0, 40))
        val r = eval(i).state
        assertEquals(LoopPhase.WAITING, r.phase)
        assertTrue(r.keepGoing)
        assertFalse(r.keepGoingAvailable)
        assertEquals(UntilStopParts(0, 1, 0, 0), r.untilStopParts)
        // The keep-going visit ends during a wait: same phase, Keep going offered only when K has a card.
        val off = eval(i.copy(limits = i.limits.copy(keepGoing = false))).state
        assertEquals(LoopPhase.WAITING, off.phase)
        assertFalse(off.keepGoingAvailable)
        val withNew = eval(i.copy(candidates = i.candidates + newCards(1), limits = i.limits.copy(keepGoing = false))).state
        assertEquals(LoopPhase.WAITING, withNew.phase)
        assertTrue(withNew.keepGoingAvailable)
    }

    @Test
    fun newPerDayZeroWithRelearningOnItsTimerWaits() {
        val l = loopLimits(unlimited = true, newPerDay = 0)
        val r = eval(input(newCards(5) + relearning(2001, NOW + 10 * minute), limits = l, today = doneToday(2, 0))).state
        assertEquals(LoopPhase.WAITING, r.phase)
        assertTrue(r.keepGoingAvailable)
        assertEquals(1, r.untilStop)
        val done = eval(input(newCards(5), limits = l, today = doneToday(2, 0))).state
        assertEquals(LoopPhase.GOAL_REACHED, done.phase)
    }

    @Test
    fun toMaterializeStaysReviewingWithLearningLater() {
        val pending = (1..3).map { entry(1, 100L + it, it.toLong()) }
        val i = input(listOf(learning(2001, NOW + minute)), decks = listOf(deck(1)), pending = pending, limits = loopLimits(unlimited = false))
        val r = eval(i)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertTrue(r.queue.items.isEmpty())
        assertFalse(r.state.keepGoingAvailable)
        assertEquals(UntilStopParts(0, 1, 0, 3), r.state.untilStopParts)
        assertEquals(4, r.state.untilStop)
    }

    @Test
    fun learningComingDueDuringWaitingIsServedThenStopScreen() {
        val i = input(newCards(5) + learning(2001, NOW + minute), limits = loopLimits(unlimited = true), today = doneToday(0, 40))
        assertEquals(LoopPhase.WAITING, eval(i).state.phase)
        val sim = QueueSim(i.copy(now = NOW + minute))
        val r = sim.eval()
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals("L2001", r.queue.shape())
        sim.answer(2001, Ans.GOOD)
        assertEquals(LoopPhase.GOAL_REACHED, sim.eval().state.phase)
    }
}
