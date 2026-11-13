package kotobaya.replay

import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.RatedReview
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.ReplayReview
import kotobaya.fsrs.FsrsScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * BUG-6 / SYNC.md 9.5: import graduation. A pass (Hard/Good/Easy) on an imported jpdb review of a
 * (re)learning card at least one day after the previous rated review graduates the card to REVIEW;
 * native reviews keep py-fsrs semantics exactly.
 */
class ImportGraduationTest {
    private val e = DefaultReplayEngine

    /** The same rows without `import_source` (a native history). */
    private fun native(rows: List<ReplayReview>): List<ReplayReview> = rows.map { it.copy(importSource = null) }

    /** What the bare scheduler gives for [rows] in order: the pre-BUG-6 / native semantics. */
    private fun plainFsrs(rows: List<ReplayReview>, params: SchedulerParams): CardSchedule =
        rows.fold(CardSchedule.NEW) { s, r -> FsrsScheduler.review(s, RatedReview(r.id, r.reviewedAt, r.rating!!, r.utcOffsetMin), params) }

    private fun id(n: Int) = uuid(0x0b06, 0x1000L + n)

    /** The ticket's history: Again, Good, Hard, Hard spread over days. */
    private val againGoodHardHard: List<ReplayReview> = listOf(
        imported(id(1), T0, Rating.AGAIN),
        imported(id(2), T0 + 1 * DAY, Rating.GOOD),
        imported(id(3), T0 + 3 * DAY, Rating.HARD),
        imported(id(4), T0 + 7 * DAY, Rating.HARD),
    )

    @Test
    fun importedAgainGoodHardHardOverDaysEndsInReview() {
        val c = e.replay(againGoodHardHard, VECTOR_PARAMS)
        assertEquals(CardState.REVIEW, c.schedule.state)
        assertEquals(null, c.schedule.step)
        assertTrue(c.schedule.intervalDays!! >= 1)
        assertEquals(id(4), c.lastReviewId)
        assertEquals(0, c.schedule.lapses, "graduating from learning is not a lapse")
        assertEquals(4, c.schedule.reps)
    }

    @Test
    fun theSameGradesAsNativeReviewsKeepFsrsBehaviour() {
        val rows = native(againGoodHardHard)
        val c = e.replay(rows, VECTOR_PARAMS)
        assertEquals(plainFsrs(rows, VECTOR_PARAMS), c.schedule, "native rows = bare py-fsrs semantics")
        // py-fsrs: Hard in learning repeats the step, so the card never leaves learning
        assertEquals(CardState.LEARNING, c.schedule.state)
        assertEquals(1, c.schedule.step)
    }

    /** The graduating row keeps FSRS's own memory-state update: only state, step, interval and due differ. */
    @Test
    fun graduationKeepsTheFsrsMemoryState() {
        for (n in 1..againGoodHardHard.size) {
            val imp = e.replay(againGoodHardHard.take(n), VECTOR_PARAMS).schedule
            val nat = e.replay(native(againGoodHardHard.take(n)), VECTOR_PARAMS).schedule
            if (n == 2) {
                assertEquals(nat.stability, imp.stability, "row 2 stability")
                assertEquals(nat.difficulty, imp.difficulty, "row 2 difficulty")
                assertEquals(nat.lastReviewAt, imp.lastReviewAt)
                assertEquals(nat.reps, imp.reps)
                assertEquals(CardState.REVIEW, imp.state)
                assertEquals(CardState.LEARNING, nat.state)
            }
        }
        // graduation = py-fsrs with no learning steps, from the same schedule before the row
        val before = e.replay(againGoodHardHard.take(1), VECTOR_PARAMS).schedule
        val r2 = againGoodHardHard[1]
        val graduated = FsrsScheduler.review(
            before,
            RatedReview(r2.id, r2.reviewedAt, r2.rating!!, r2.utcOffsetMin),
            VECTOR_PARAMS.copy(learningStepsSeconds = emptyList(), relearningStepsSeconds = emptyList()),
        )
        assertEquals(graduated, e.replay(againGoodHardHard.take(2), VECTOR_PARAMS).schedule)
    }

    @Test
    fun importedHardUnderOneDayAfterThePreviousReviewKeepsStepBehaviour() {
        for (gap in listOf(0L, 1L, 10 * 60_000L, DAY - 1)) {
            val rows = listOf(imported(id(1), T0, Rating.AGAIN), imported(id(2), T0 + gap, Rating.HARD, counter = 1))
            val c = e.replay(rows, VECTOR_PARAMS)
            assertEquals(e.replay(native(rows), VECTOR_PARAMS), c, "gap $gap")
            assertEquals(CardState.LEARNING, c.schedule.state, "gap $gap")
        }
        // exactly one day is enough
        val rows = listOf(imported(id(1), T0, Rating.AGAIN), imported(id(2), T0 + DAY, Rating.HARD))
        assertEquals(CardState.REVIEW, e.replay(rows, VECTOR_PARAMS).schedule.state)
    }

    @Test
    fun importedRelearningPassAfterADayGraduatesWithoutAnExtraLapse() {
        val rows = listOf(
            imported(id(1), T0, Rating.GOOD),
            imported(id(2), T0 + 1 * DAY, Rating.GOOD), // last learning step: graduates in py-fsrs too
            imported(id(3), T0 + 10 * DAY, Rating.AGAIN), // lapse → relearning
            imported(id(4), T0 + 12 * DAY, Rating.HARD),
        )
        val imp = e.replay(rows, VECTOR_PARAMS).schedule
        val nat = e.replay(native(rows), VECTOR_PARAMS).schedule
        assertEquals(CardState.REVIEW, imp.state)
        assertEquals(CardState.RELEARNING, nat.state)
        assertEquals(1, imp.lapses)
        assertEquals(nat.lapses, imp.lapses)
        assertEquals(nat.stability, imp.stability)
        assertEquals(nat.difficulty, imp.difficulty)
        // the first three rows are identical either way (the rule only touches passes in (re)learning)
        assertEquals(e.replay(native(rows.take(3)), VECTOR_PARAMS), e.replay(rows.take(3), VECTOR_PARAMS))
    }

    @Test
    fun ruleScopeAgainNewAndReviewAndNativeRowsAreUntouched() {
        // Again on a learning card a day later: unchanged
        val again = listOf(imported(id(1), T0, Rating.AGAIN), imported(id(2), T0 + 2 * DAY, Rating.AGAIN))
        assertEquals(e.replay(native(again), VECTOR_PARAMS), e.replay(again, VECTOR_PARAMS))
        // first review of a new card (no last review): unchanged
        for (r in Rating.entries) {
            val first = listOf(imported(id(1), T0, r))
            assertEquals(e.replay(native(first), VECTOR_PARAMS), e.replay(first, VECTOR_PARAMS), "first $r")
        }
        // a review card: unchanged
        val review = listOf(imported(id(1), T0, Rating.EASY), imported(id(2), T0 + 5 * DAY, Rating.HARD))
        assertEquals(CardState.REVIEW, e.replay(review.take(1), VECTOR_PARAMS).schedule.state)
        assertEquals(e.replay(native(review), VECTOR_PARAMS), e.replay(review, VECTOR_PARAMS))
        // a native Hard after an imported Again, days later: native semantics (the rule reads the row folded)
        val mixed = listOf(imported(id(1), T0, Rating.AGAIN), rated(id(2), T0 + 3 * DAY, Rating.HARD))
        assertEquals(CardState.LEARNING, e.replay(mixed, VECTOR_PARAMS).schedule.state)
        // an import source other than jpdb: native semantics
        val other = againGoodHardHard.map { it.copy(importSource = "anki") }
        assertEquals(e.replay(native(againGoodHardHard), VECTOR_PARAMS), e.replay(other, VECTOR_PARAMS))
        // no learning steps at all: py-fsrs graduates anyway, the rule changes nothing
        val noSteps = VECTOR_PARAMS.copy(learningStepsSeconds = emptyList(), relearningStepsSeconds = emptyList())
        assertEquals(e.replay(native(againGoodHardHard), noSteps), e.replay(againGoodHardHard, noSteps))
    }

    /** Good on the last learning step and Easy graduate in py-fsrs already: the rule gives the same cache. */
    @Test
    fun passesThatGraduateAnywayAreUnchanged() {
        val good = listOf(imported(id(1), T0, Rating.GOOD), imported(id(2), T0 + 2 * DAY, Rating.GOOD))
        assertEquals(e.replay(native(good), VECTOR_PARAMS), e.replay(good, VECTOR_PARAMS))
        val easy = listOf(imported(id(1), T0, Rating.AGAIN), imported(id(2), T0 + 2 * DAY, Rating.EASY))
        assertEquals(e.replay(native(easy), VECTOR_PARAMS), e.replay(easy, VECTOR_PARAMS))
    }

    /** Fuzz is seeded by the graduating row's id like any REVIEW interval. */
    @Test
    fun graduationIntervalIsFuzzedLikeAnyReviewInterval() {
        val rows = listOf(
            imported(id(1), T0, Rating.AGAIN),
            imported(id(2), T0 + 20 * DAY, Rating.GOOD),
        )
        val noFuzz = e.replay(rows, VECTOR_PARAMS).schedule
        val fuzzed = e.replay(rows, P0).schedule
        assertEquals(CardState.REVIEW, fuzzed.state)
        assertEquals(noFuzz.stability, fuzzed.stability)
        val (lo, hi) = FsrsScheduler.fuzzRange(noFuzz.intervalDays!!, P0.maximumIntervalDays)
        assertTrue(fuzzed.intervalDays!! in lo..hi, "fuzzed ${fuzzed.intervalDays} in $lo..$hi")
        assertEquals(rows[1].reviewedAt + fuzzed.intervalDays!! * DAY, fuzzed.dueAt)
    }

    /**
     * The worked example of SYNC.md 12.8 (default weights, steps 1m/10m, relearning 10m, retention
     * 0.9, fuzz off): the values printed there.
     */
    @Test
    fun workedExampleOfSync12_8() {
        val steps = againGoodHardHard.indices.map { n -> e.replay(againGoodHardHard.take(n + 1), VECTOR_PARAMS).schedule }
        val nativeSteps = againGoodHardHard.indices.map { n -> e.replay(native(againGoodHardHard.take(n + 1)), VECTOR_PARAMS).schedule }
        val got = steps.map { "${it.state} ${it.step} ${fmt(it.stability!!)} ${fmt(it.difficulty!!)} ${it.intervalDays} ${(it.dueAt!! - T0)}" }
        val gotNative = nativeSteps.map { "${it.state} ${it.step} ${fmt(it.stability!!)} ${fmt(it.difficulty!!)} ${it.intervalDays} ${(it.dueAt!! - T0)}" }
        assertEquals(WORKED_IMPORT, got)
        assertEquals(WORKED_NATIVE, gotNative)
        assertNotEquals(got.last(), gotNative.last())
    }

    /** Four decimals, deterministic on jvm and js (no platform formatting). */
    private fun fmt(x: Double): String {
        val scaled = kotlin.math.floor(x * 10_000.0 + 0.5).toLong()
        val whole = scaled / 10_000
        val frac = (scaled % 10_000).toString().padStart(4, '0')
        return "$whole.$frac"
    }

    private companion object {
        // state step stability difficulty interval_days due_at−T0 (ms)
        val WORKED_IMPORT = listOf(
            "LEARNING 0 0.2120 6.4133 0 60000",
            "REVIEW null 1.8868 6.4021 2 259200000",
            "REVIEW null 4.5223 7.5968 5 691200000",
            "REVIEW null 8.0705 8.3899 8 1296000000",
        )
        val WORKED_NATIVE = listOf(
            "LEARNING 0 0.2120 6.4133 0 60000",
            "LEARNING 1 1.8868 6.4021 0 87000000",
            "LEARNING 1 4.5223 7.5968 0 259800000",
            "LEARNING 1 8.0705 8.3899 0 605400000",
        )
    }
}
