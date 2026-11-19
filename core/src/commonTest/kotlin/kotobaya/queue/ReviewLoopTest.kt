package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.CardKinds
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.LegacyCounts
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.LoopResult
import kotobaya.contracts.queue.LoopState
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueLimits
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.queue.StudyDay
import kotobaya.contracts.queue.TodayReview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-037: `DefaultReviewLoop` against the golden table of docs/REVIEW-LOOP.md section 10 (A1-A4, B1-B8,
 * M1-M6, E1-E6) and the `ReviewLoop` KDoc examples. Runs on jvm and js; every assertion is a literal, so
 * both platforms are held to the same numbers.
 *
 * Numbering: reviews 1..999 (card n due `past + n`), new cards 1001.., learning 2001.., today's rows
 * 5001.. (notes that no candidate shares, so they bury nothing).
 */
class ReviewLoopTest {
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

    private fun laterLearning(n: Int): List<QueueCandidate> = (2001 until 2001 + n).map { learning(it, NOW + (5 + it - 2001) * minute) }

    /** [reviews] answered `countsAsReview` and [introduced] cards introduced today, all before `now`; introductions last. */
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

    private fun ReviewQueue.nonEmpty(): Boolean = items.isNotEmpty() || toMaterialize.isNotEmpty()

    /** The mode-independent numbers: everything but `phase`, `keepGoing` and `untilStop` (ADR-038). */
    private fun LoopState.counts(): LoopState = copy(phase = LoopPhase.EMPTY, keepGoing = false, untilStop = 0)

    private fun kinds(q: ReviewQueue): String = q.items.joinToString("") { it.kind.name.take(1) }

    /**
     * Answers the served queue's head and moves the clock 30 s on: NEW -> LEARNING due in 10 min,
     * LEARNING -> REVIEW due after today, REVIEW -> REVIEW due after today. Adds the `today` row a
     * loader would pass. Every note is distinct, so nothing sibling-buries.
     */
    private class Sim(var input: QueueInput) {
        var answers = 0
        val served = mutableListOf<QueueItem>()

        fun eval(): LoopResult = DefaultReviewLoop.evaluate(input)

        fun answerHead(r: LoopResult = eval()): QueueItem {
            val item = r.queue.items.first()
            val c = input.candidates.first { it.cardId == item.cardId }
            val now = input.now
            val after = input.day.endMs + 2 * 86_400_000L
            val (next, row) = when (c.state) {
                CardState.NEW -> c.copy(state = CardState.LEARNING, dueAt = now + 600_000L) to
                    TodayReview(c.cardId, c.noteId, ReviewType.LEARNING, true, false, emptyList(), now)
                CardState.LEARNING, CardState.RELEARNING -> c.copy(state = CardState.REVIEW, dueAt = after) to
                    TodayReview(c.cardId, c.noteId, ReviewType.LEARNING, false, false, emptyList(), now)
                CardState.REVIEW -> c.copy(dueAt = after) to
                    TodayReview(c.cardId, c.noteId, ReviewType.SCHEDULED, false, true, emptyList(), now)
            }
            input = input.copy(
                now = now + 30_000L,
                candidates = input.candidates.map { if (it.cardId == c.cardId) next else it },
                today = input.today + row,
            )
            answers++
            served += item
            return item
        }
    }

    // ---- A: reviews_unlimited on ----

    @Test
    fun unlimitedNoReadyLaterLearningStartsNew() {
        // A1 (user case): 0 ready, 26 learning later today, 100 new, nothing introduced.
        val later = laterLearning(26)
        val i = input(later + newCards(100), limits = loopLimits(unlimited = true))
        val r = eval(i)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals((1001..1010).toList(), r.queue.cards())
        assertEquals("NNNNNNNNNN", kinds(r.queue))
        assertEquals(
            LoopState(
                phase = LoopPhase.REVIEWING, keepGoing = false, readyLearning = 0, readyReviews = 0,
                laterToday = 26, nextDueAt = NOW + 5 * minute, backlog = 0, backlogBeyondLimit = 0,
                reviewsToday = 0, reviewsLimit = null, newToday = 0, newGoal = 40, newAvailable = true,
                legacy = LegacyCounts(new = 40, learning = 26, review = 0, moreAvailable = false),
                untilStop = 66,
            ),
            r.state,
        )
        // Never waits: answer the head until the day's goal is reached; learning cards that come due
        // are served first, new cards fill every gap.
        val sim = Sim(i)
        while (true) {
            val s = sim.eval()
            assertNotEquals(LoopPhase.WAITING, s.state.phase, "waited after ${sim.answers} answers")
            if (s.state.phase != LoopPhase.REVIEWING) break
            assertTrue(s.queue.items.isNotEmpty())
            sim.answerHead(s)
            assertTrue(sim.answers < 500)
        }
        val end = sim.eval().state
        assertEquals(LoopPhase.GOAL_REACHED, end.phase)
        assertEquals(40, end.newToday)
        assertTrue(end.laterToday > 0, "the last introductions are still in their learning step")
    }

    @Test
    fun unlimitedServesEveryDueReviewMixedWithNew() {
        // A2: 335 due, unlimited.
        val i = input(dueReviews(335) + newCards(100) + listOf(learning(2001, NOW - minute)), limits = loopLimits(unlimited = true))
        val r = eval(i)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(335, r.state.readyReviews)
        assertEquals(335, r.state.backlog)
        assertEquals(0, r.state.backlogBeyondLimit)
        assertNull(r.state.reviewsLimit)
        assertEquals(1, r.state.readyLearning)
        assertEquals(2001, r.queue.cards().first())
        // s = 0, rRem 335, gRem 40: a new card after every 9th review (9 * 40 >= 335).
        assertEquals("L" + "RRRRRRRRRN", kinds(r.queue).take(11))
        assertEquals(1 + 335 + 10, r.queue.items.size)
    }

    @Test
    fun newGoalReachedThenKeepGoingContinues() {
        // A3: 40/40 introduced, no due reviews, new cards remain.
        val i = input(newCards(200), limits = loopLimits(unlimited = true), today = doneToday(0, 40))
        val r = eval(i)
        assertEquals(LoopPhase.GOAL_REACHED, r.state.phase)
        assertTrue(r.queue.items.isEmpty())
        assertTrue(r.state.newAvailable)
        assertEquals(40, r.state.newToday)
        assertTrue(r.state.legacy.moreAvailable)
        assertEquals(0, r.state.legacy.new)

        val kg = eval(i.copy(limits = i.limits.copy(keepGoing = true)))
        assertEquals(LoopPhase.REVIEWING, kg.state.phase)
        assertTrue(kg.state.keepGoing)
        assertEquals((1001..1010).toList(), kg.queue.cards())
        assertEquals(r.state.counts(), kg.state.counts())

        // 60 more introductions through keep going (the learning steps they start are served when due).
        val sim = Sim(i.copy(limits = i.limits.copy(keepGoing = true)))
        var introduced = 0
        while (introduced < 60) {
            val s = sim.eval()
            assertEquals(LoopPhase.REVIEWING, s.state.phase, "after $introduced keep-going introductions")
            if (sim.answerHead(s).kind == QueueItemKind.NEW) introduced++
        }
        val end = sim.eval().state
        assertEquals(LoopPhase.REVIEWING, end.phase)
        assertEquals(100, end.newToday)
        assertEquals(40, end.newGoal)
    }

    @Test
    fun dueCardsPrecedeNewUnderKeepGoing() {
        // A4: unlimited + keep going past the goal: due learning, then due reviews, then new.
        val c = newCards(30) + listOf(review(1, past + 1), review(2, past + 2), learning(2001, NOW - minute), learning(2002, NOW + 20 * minute))
        val i = input(c, limits = loopLimits(unlimited = true, keepGoing = true), today = doneToday(0, 40))
        val r = eval(i)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals("L2001 R1 R2 N1001 N1002 N1003 N1004 N1005 N1006 N1007 N1008 N1009 N1010", r.queue.shape())
        // Rule 6: no mix under keep going, whatever has been answered since the last new card.
        val i2 = i.copy(today = doneToday(7, 40))
        assertEquals("L2001 R1 R2", eval(i2).queue.shape().split(" ").take(3).joinToString(" "))
    }

    // ---- B: reviews_unlimited off (capped) ----

    @Test
    fun cappedFirstSessionServesTheCapMostOverdueFirstWithNewMixed() {
        // B1: 335 due, nothing done.
        val i = input(dueReviews(335) + newCards(100), limits = loopLimits(unlimited = false))
        val r = eval(i)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(200, r.state.readyReviews)
        assertEquals(335, r.state.backlog)
        assertEquals(135, r.state.backlogBeyondLimit)
        assertEquals(200, r.state.reviewsLimit)
        assertEquals(1, r.queue.cards().first())
        // M2: 200 ready, 40 available -> RRRRRN repeated; the batch bounds this build to 10 new.
        assertEquals("RRRRRN".repeat(10), kinds(r.queue).take(60))
        assertEquals(210, r.queue.items.size)
    }

    @Test
    fun cappedReviewsDoneServesNewOnlyAndDueLearningFirst() {
        // B2: 200/200 reviews, 12/40 new.
        val c = dueReviews(335).drop(200) + newCards(100) + listOf(learning(2001, NOW - minute))
        val i = input(c, limits = loopLimits(unlimited = false), today = doneToday(200, 12))
        val r = eval(i)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals("L2001 " + (1001..1010).joinToString(" ") { "N$it" }, r.queue.shape())
        assertEquals(0, r.state.readyReviews)
        assertEquals(135, r.state.backlog)
        assertEquals(135, r.state.backlogBeyondLimit)
        assertEquals(200, r.state.reviewsToday)
        assertEquals(1, r.state.readyLearning)
        assertEquals(28, r.state.legacy.new)
        // Answering the learning card does not use up the review cap.
        val sim = Sim(i)
        sim.answerHead()
        assertEquals(200, sim.eval().state.reviewsToday)
    }

    @Test
    fun capThenKeepGoingClearsBacklogFirst() {
        // B3 (user case): 200/200 and 40/40, 135 due left.
        val i = input(dueReviews(335).drop(200) + newCards(100), limits = loopLimits(unlimited = false), today = doneToday(200, 40))
        val b3 = eval(i)
        assertEquals(
            LoopState(
                phase = LoopPhase.DONE_FOR_TODAY, keepGoing = false, readyLearning = 0, readyReviews = 0,
                laterToday = 0, nextDueAt = null, backlog = 135, backlogBeyondLimit = 135,
                reviewsToday = 200, reviewsLimit = 200, newToday = 40, newGoal = 40, newAvailable = true,
                legacy = LegacyCounts(new = 0, learning = 0, review = 0, moreAvailable = true),
                untilStop = 0,
            ),
            b3.state,
        )
        assertFalse(b3.queue.nonEmpty())

        // B4: keep going -> the backlog by (dueAt, cardId), then a batch of new.
        val kgInput = i.copy(limits = i.limits.copy(keepGoing = true))
        val b4 = eval(kgInput)
        assertEquals(LoopPhase.REVIEWING, b4.state.phase)
        assertTrue(b4.state.keepGoing)
        assertEquals((201..335).toList() + (1001..1010).toList(), b4.queue.cards())
        assertEquals(b3.state.counts(), b4.state.counts())
        // ADR-038: 135 backlog + the batch of 10.
        assertEquals(145, b4.state.untilStop)

        // A due learning card leads the keep-going queue.
        val withLearning = eval(kgInput.copy(candidates = kgInput.candidates + learning(2001, NOW - minute)))
        assertEquals(2001, withLearning.queue.cards().first())

        // Answering through: no NEW before the last backlog review.
        val sim = Sim(kgInput)
        var reviewsLeft = 135
        while (reviewsLeft > 0) {
            val item = sim.answerHead()
            if (item.kind == QueueItemKind.REVIEW) reviewsLeft-- else fail("${item.kind} before the backlog ended")
        }
        // B5: backlog cleared -> new cards only, phase still reviewing.
        val b5 = sim.eval()
        assertEquals(LoopPhase.REVIEWING, b5.state.phase)
        assertEquals(0, b5.state.backlog)
        assertEquals("NNNNNNNNNN", kinds(b5.queue))
        assertEquals(335, b5.state.reviewsToday)
    }

    @Test
    fun waitingOnlyWhenNothingAnywhere() {
        // B6: no backlog, no new card anywhere, 8 learning later today -> waiting; none later -> empty.
        for (unlimited in listOf(false, true)) {
            for (keepGoing in listOf(false, true)) {
                val l = loopLimits(unlimited = unlimited, keepGoing = keepGoing)
                val r = eval(input(laterLearning(8), limits = l, today = doneToday(200, 40)))
                assertEquals(LoopPhase.WAITING, r.state.phase, "unlimited=$unlimited keepGoing=$keepGoing")
                assertEquals(NOW + 5 * minute, r.state.nextDueAt)
                assertEquals(8, r.state.laterToday)
                assertFalse(r.state.newAvailable)
                val e = eval(input(emptyList(), limits = l, today = doneToday(200, 40)))
                assertEquals(LoopPhase.EMPTY, e.state.phase)
                assertNull(e.state.nextDueAt)
            }
        }
        // One new card anywhere -> never waiting.
        val one = eval(input(laterLearning(8) + newCards(1), limits = loopLimits(unlimited = true), today = doneToday(0, 40)))
        assertEquals(LoopPhase.GOAL_REACHED, one.state.phase)
    }

    @Test
    fun settingChoosesDoneVsGoal() {
        // B7: caps hit, backlog 0, new remain: the setting alone picks the phase.
        val c = newCards(50)
        val t = doneToday(200, 40)
        assertEquals(LoopPhase.DONE_FOR_TODAY, eval(input(c, limits = loopLimits(unlimited = false), today = t)).state.phase)
        assertEquals(LoopPhase.GOAL_REACHED, eval(input(c, limits = loopLimits(unlimited = true), today = t)).state.phase)
    }

    @Test
    fun poolExhaustedBeforeGoalWithBacklogIsDoneForToday() {
        // B8: 25/40 introduced, no new card left, 135 due beyond the cap.
        val r = eval(input(dueReviews(335).drop(200), limits = loopLimits(unlimited = false), today = doneToday(200, 25)))
        assertEquals(LoopPhase.DONE_FOR_TODAY, r.state.phase)
        assertEquals(25, r.state.newToday)
        assertFalse(r.state.newAvailable)
        assertEquals(135, r.state.backlogBeyondLimit)
    }

    // ---- M: the proportional mix (rule 5) ----

    @Test
    fun mixFourReviewsTwoNew() {
        // M1
        val r = eval(input(dueReviews(4) + newCards(2), limits = loopLimits(unlimited = false)))
        assertEquals("R1 R2 N1001 R3 N1002 R4", r.queue.shape())
    }

    @Test
    fun mixTwoHundredReviewsFortyNew() {
        // M2, batch unbounded so the whole stride is visible in one build.
        val r = eval(input(dueReviews(200) + newCards(100), limits = loopLimits(unlimited = false, batch = Int.MAX_VALUE)))
        val k = kinds(r.queue)
        assertEquals(200, k.count { it == 'R' })
        assertEquals(40, k.count { it == 'N' })
        // RRRRRN repeated; the last four gaps are four reviews, and four reviews follow the 40th new card.
        assertEquals("RRRRRN".repeat(36) + "RRRRN".repeat(4) + "RRRR", k)
        // Same as rebuilding after every answer with the default batch.
        val sim = Sim(input(dueReviews(200) + newCards(100), limits = loopLimits(unlimited = false)))
        val streamed = StringBuilder()
        while (true) {
            val s = sim.eval()
            if (s.queue.items.isEmpty()) break
            streamed.append(sim.answerHead(s).kind.name.take(1))
        }
        // Learning steps of the introduced cards come due (10 min) and are served between: drop them.
        assertEquals(k, streamed.toString().replace("L", ""))
    }

    @Test
    fun mixMoreNewThanReviews() {
        // M3: 9 ready, 28 available.
        val r = eval(input(dueReviews(9) + newCards(28), limits = loopLimits(unlimited = false, batch = Int.MAX_VALUE)))
        assertEquals("RN".repeat(9) + "N".repeat(19), kinds(r.queue))
    }

    @Test
    fun mixInCappedMode() {
        // M5: cap 2, goal 1, 5 due, 3 new.
        val r = eval(input(dueReviews(5) + newCards(3), limits = loopLimits(unlimited = false, reviewsPerDay = 2, newPerDay = 1)))
        assertEquals("R1 N1001 R2", r.queue.shape())
    }

    @Test
    fun legacyNewIsTheDayRemainderNotTheBatch() {
        // M6: goal 40, 12 introduced, batch 10, 100 new -> 28; 3 new cards exist -> 3.
        val r = eval(input(newCards(100), limits = loopLimits(unlimited = false), today = doneToday(0, 12)))
        assertEquals(10, r.queue.items.size)
        assertEquals(28, r.state.legacy.new)
        assertEquals(3, eval(input(newCards(3), limits = loopLimits(unlimited = false), today = doneToday(0, 12))).state.legacy.new)
    }

    @Test
    fun mixResumesFromReviewsAnsweredSinceTheLastNewCard() {
        // s from today's rows: 3 reviews after the last introduction; rRem 4, gRem 1 -> 3 * 1 < 4 -> one more review first.
        val t = doneToday(0, 39) + (1..3).map { today(6000 + it, countsAsReview = true).copy(reviewedAt = TOKYO_DAY.startMs + 1000 + it) }
        val r = eval(input(dueReviews(4) + newCards(5), limits = loopLimits(unlimited = false), today = t))
        assertEquals("R1 N1001 R2 R3 R4", r.queue.shape())
    }

    @Test
    fun legacyCountsFrozen() {
        val c = laterLearning(3) + listOf(learning(2100, NOW - minute)) + dueReviews(5) + newCards(4)
        val i = input(c, limits = loopLimits(unlimited = false, reviewsPerDay = 3, newPerDay = 6))
        val r = eval(i)
        assertEquals(LegacyCounts(new = 4, learning = 4, review = 3, moreAvailable = false), r.state.legacy)
        val old = DefaultQueueBuilder.build(i)
        assertEquals(old.moreAvailable, r.state.legacy.moreAvailable)
        assertEquals(old.nextLearningDueAt, r.state.nextDueAt)
        // Keep going: the same legacy numbers.
        assertEquals(r.state.legacy, eval(i.copy(limits = i.limits.copy(keepGoing = true))).state.legacy)
        // moreAvailable as before: true when only keep going has cards.
        val done = input(dueReviews(5), limits = loopLimits(unlimited = false, reviewsPerDay = 3), today = doneToday(3, 0))
        assertTrue(eval(done).state.legacy.moreAvailable)
        assertTrue(DefaultQueueBuilder.build(done).moreAvailable)
    }

    // ---- E: edge cases ----

    @Test
    fun goalThirtyNineOfFortyAndOnlyAThreeCardGroupLeft() {
        // E1 (ADR-043 replaces "goal_reached at 39/40"): the unit [食, 物, 食物] leads with its first kanji.
        // The kanji cards live in a deck with newPerDay 0: never drawn alone, but they join the unit.
        val c = listOf(
            card(1001, exprKanji = listOf("食", "物")),
            card(1002, note = 3002, base = NoteBaseType.KANJI, kanji = "食", members = listOf(member(2, 1))),
            card(1003, note = 3003, base = NoteBaseType.KANJI, kanji = "物", members = listOf(member(2, 2))),
        )
        val i = input(c, decks = listOf(deck(2, newPerDay = 0)), limits = loopLimits(unlimited = true), today = doneToday(0, 39))
        val r = eval(i)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals("N1002", r.queue.shape())
        assertEquals(1, r.state.untilStop)
        assertEquals(1, r.state.legacy.new)
        // After it: 40/40, phase 2, never overshot.
        val sim = Sim(i)
        sim.answerHead()
        val after = sim.eval()
        assertEquals(LoopPhase.GOAL_REACHED, after.state.phase)
        assertEquals(40, after.state.newToday)
        assertEquals("N1003 N1001", eval(sim.input.copy(limits = sim.input.limits.copy(keepGoing = true))).queue.shape())
        // A batch of 1 still draws the whole unit when it fits the day.
        val fits = eval(i.copy(today = doneToday(0, 30), limits = i.limits.copy(keepGoingNewBatch = 1)))
        assertEquals("N1002 N1003 N1001", fits.queue.shape())
    }

    @Test
    fun deckNewCapBindsBeforeTheGoal() {
        // E2
        val c = (1001..1010).map { card(it, members = listOf(member(1, it.toLong()))) }
        val t = doneToday(0, 2).map { it.copy(deckIds = listOf(deckId(1))) }
        val r = eval(input(c, decks = listOf(deck(1, newPerDay = 2)), limits = loopLimits(unlimited = false), today = t))
        assertEquals(LoopPhase.DONE_FOR_TODAY, r.state.phase)
        assertEquals(2, r.state.newToday)
        assertTrue(r.state.newAvailable)
        val a = eval(input(c, decks = listOf(deck(1, newPerDay = 2)), limits = loopLimits(unlimited = true), today = t))
        assertEquals(LoopPhase.GOAL_REACHED, a.state.phase)
    }

    @Test
    fun newPerDayZero() {
        // E3: reviews first; phase 2 as soon as they are done.
        val l = loopLimits(unlimited = true, newPerDay = 0)
        val r = eval(input(dueReviews(2) + newCards(5), limits = l))
        assertEquals("R1 R2", r.queue.shape())
        assertEquals(0, r.state.newGoal)
        val done = eval(input(newCards(5), limits = l, today = doneToday(2, 0)))
        assertEquals(LoopPhase.GOAL_REACHED, done.state.phase)
    }

    @Test
    fun reviewsPerDayZero() {
        // E4
        val r = eval(input(dueReviews(7), limits = loopLimits(unlimited = false, reviewsPerDay = 0)))
        assertEquals(0, r.state.readyReviews)
        assertEquals(7, r.state.backlog)
        assertEquals(7, r.state.backlogBeyondLimit)
        assertEquals(0, r.state.reviewsLimit)
        assertEquals(LoopPhase.DONE_FOR_TODAY, r.state.phase)
        // Negative counts as 0.
        assertEquals(0, eval(input(dueReviews(7), limits = loopLimits(unlimited = false, reviewsPerDay = -3))).state.reviewsLimit)
    }

    @Test
    fun dayRolloverRecomputesEverything() {
        // E5: B3 at the end of the day; the next day starts from an empty `today`.
        val c = dueReviews(335).drop(200) + newCards(100)
        val b3 = eval(input(c, limits = loopLimits(unlimited = false), today = doneToday(200, 40)))
        assertEquals(LoopPhase.DONE_FOR_TODAY, b3.state.phase)
        val day2 = StudyDay(TOKYO_DAY.endMs, TOKYO_DAY.endMs + 86_400_000L)
        val r = eval(input(c, limits = loopLimits(unlimited = false), now = day2.startMs + HOUR, day = day2))
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(0, r.state.reviewsToday)
        assertEquals(0, r.state.newToday)
        assertEquals(135, r.state.readyReviews)
        assertEquals(0, r.state.backlogBeyondLimit)
        assertEquals(40, r.state.legacy.new)
    }

    @Test
    fun retiredCardsAreInNoCount() {
        // E6: vocab reading cards while cards.reading_enabled is off.
        fun QueueCandidate.reading() = copy(kind = CardKinds.READING)
        val c = listOf(
            review(1, past).reading(),
            learning(2001, NOW - minute).reading(),
            learning(2002, NOW + minute).reading(),
            card(1001).reading(),
        )
        val t = listOf(today(1, countsAsReview = true).copy(reviewedAt = NOW - 1, cardId = cardId(1)))
        val r = eval(input(c + review(9, past, note = 9), limits = loopLimits(unlimited = false), today = t))
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals("R9", r.queue.shape())
        assertEquals(0, r.state.readyLearning)
        assertEquals(1, r.state.readyReviews)
        assertEquals(1, r.state.backlog)
        assertEquals(0, r.state.laterToday)
        assertEquals(0, r.state.reviewsToday)
        assertFalse(r.state.newAvailable)
        assertEquals(LegacyCounts(0, 0, 1, false), r.state.legacy)
        // With reading cards on they all count.
        val on = eval(input(c + review(9, past, note = 9), limits = loopLimits(unlimited = false), today = t).copy(readingEnabled = true))
        assertEquals(1, on.state.readyLearning)
        assertEquals(1, on.state.laterToday)
        assertEquals(1, on.state.reviewsToday)
    }

    @Test
    fun learningCardComingDueDuringGoalReachedReviewsThenReturns() {
        val i = input(newCards(5) + learning(2001, NOW + minute), limits = loopLimits(unlimited = true), today = doneToday(0, 40))
        assertEquals(LoopPhase.GOAL_REACHED, eval(i).state.phase)
        val due = i.copy(now = NOW + minute)
        val r = eval(due)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(1, r.state.readyLearning)
        assertEquals("L2001", r.queue.shape())
        val sim = Sim(due)
        sim.answerHead(r)
        assertEquals(LoopPhase.GOAL_REACHED, sim.eval().state.phase)
    }

    @Test
    fun pendingEntriesOnlyCountAsReviewingAndOnlyTheServedBuildMaterializes() {
        val decks = listOf(deck(1))
        val pending = (1..30).map { entry(1, 100L + it, it.toLong()) }
        val i = input(emptyList(), decks = decks, pending = pending, limits = loopLimits(unlimited = false))
        val r = eval(i)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertTrue(r.queue.items.isEmpty())
        assertEquals((101L..110L).toList(), r.queue.toMaterialize.map { it.entrySeq })
        assertEquals(30, r.state.legacy.new)
        assertTrue(r.state.newAvailable)
        // At the goal: N materializes nothing, K would.
        val atGoal = eval(i.copy(today = doneToday(0, 40)))
        assertEquals(LoopPhase.DONE_FOR_TODAY, atGoal.state.phase)
        assertTrue(atGoal.queue.toMaterialize.isEmpty())
    }

    @Test
    fun learnAheadIsForcedToZero() {
        val c = listOf(learning(2001, NOW + minute))
        val l = loopLimits(unlimited = false).copy(learnAheadMs = 20 * minute)
        val r = eval(input(c, limits = l))
        assertEquals(LoopPhase.WAITING, r.state.phase)
        assertEquals(NOW + minute, r.state.nextDueAt)
        assertEquals(NOW + minute, r.queue.nextLearningDueAt)
        assertEquals(LegacyCounts(0, 1, 0, false), r.state.legacy)
    }

    @Test
    fun emptyInputIsEmpty() {
        val r = eval(input(emptyList(), limits = loopLimits(unlimited = false)))
        assertEquals(
            LoopState(
                LoopPhase.EMPTY, false, 0, 0, 0, null, 0, 0, 0, 200, 0, 40, false,
                LegacyCounts(0, 0, 0, false),
            ),
            r.state,
        )
    }

    @Test
    fun goldenFingerprintIsIdenticalOnEveryPlatform() {
        // A literal over many scenarios: jvm and js must both reproduce it.
        var h = 17L
        for (n in 0 until 40) {
            val i = input(
                dueReviews(n * 3 % 23) + newCards(n * 7 % 31) + laterLearning(n % 5),
                limits = loopLimits(unlimited = n % 2 == 0, keepGoing = n % 3 == 0, newPerDay = n % 13, reviewsPerDay = n % 11, batch = 1 + n % 4),
                today = doneToday(n % 6, n % 4),
            )
            val r = eval(i)
            for (c in r.queue.shape() + "|" + r.state.toString()) h = h * 31 + c.code
        }
        assertEquals(GOLDEN_FINGERPRINT, h)
    }

    // ---- ADR-038: untilStop ----

    @Test
    fun untilStopWorkedExamples() {
        // A1 66, B3 0, B4 145 are asserted above; U1: goal 40, 10 introduced, 30 due under the cap, 5 later.
        val u1 = input(dueReviews(30) + laterLearning(5) + newCards(200), limits = loopLimits(unlimited = false), today = doneToday(0, 10))
        val r = eval(u1)
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(65, r.state.untilStop)
        assertEquals(47, eval(u1.copy(candidates = dueReviews(30) + laterLearning(5) + newCards(12))).state.untilStop)
        // Again on a review keeps it (moves from ready to later), Good on a new card keeps it, Good on a review lowers it.
        val sim = QueueSim(u1)
        sim.answer(1, Ans.AGAIN)
        val again = sim.eval().state
        assertEquals(29, again.readyReviews)
        assertEquals(6, again.laterToday)
        assertEquals(65, again.untilStop)
        sim.answer(1001, Ans.GOOD)
        assertEquals(65, sim.eval().state.untilStop)
        sim.answer(2, Ans.GOOD)
        assertEquals(64, sim.eval().state.untilStop)
    }

    @Test
    fun untilStopIsEqualBeforeAndAfterMaterialization() {
        val words = listOf(
            card(1001, created = 1, exprKanji = listOf("学", "校"), missing = mks("学", "校")),
            card(1002, created = 2),
        )
        val pending = listOf(entry(1, 77, 0, exprKanji = listOf("先"), missing = mks("先")))
        val sim = QueueSim(input(words + dueReviews(3), decks = listOf(deck(1)), limits = loopLimits(unlimited = false), pending = pending))
        val before = sim.eval()
        assertEquals(LoopPhase.REVIEWING, before.state.phase)
        assertEquals(listOf("先", "学", "校"), before.queue.kanjiToMaterialize)
        sim.materialize(before.queue)
        val after = sim.eval()
        assertEquals(before.state.untilStop, after.state.untilStop)
        assertEquals(3 + 2 + 3 + 1, after.state.untilStop)
        assertEquals(after.state.untilStop, after.queue.items.size)
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)

    private companion object {
        // CORE-28: re-recorded only because `LoopState.toString()` now carries `untilStop` (ADR-038); with
        // that field stripped the hash is still the pre-CORE-28 5076156702524499125 (queues unchanged).
        const val GOLDEN_FINGERPRINT: Long = 398548625834242320L
    }
}
