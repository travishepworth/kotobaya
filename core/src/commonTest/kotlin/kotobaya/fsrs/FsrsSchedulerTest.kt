package kotobaya.fsrs

import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.PresetRow
import kotobaya.contracts.fsrs.RatedReview
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.js.JsCardSchedule
import kotobaya.contracts.js.JsPreset
import kotobaya.core.kotobayaCore
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** T107 FSRS tests (contracts/core/README "Tests implementers must write › T107"). Runs on jvm and js. */
class FsrsSchedulerTest {
    private val s = FsrsScheduler
    private val day = Reserved.DAY_MS

    /** The scheduler block of fixtures/fsrs/vectors.json. */
    private val vectorParams = SchedulerParams(
        weights = s.defaultWeights,
        desiredRetention = 0.9,
        maximumIntervalDays = 36500,
        learningStepsSeconds = listOf(60, 600),
        relearningStepsSeconds = listOf(600),
        fuzz = false,
        easyDays = List(7) { 1.0 },
        weightsVersion = 0,
    )

    private class VectorReview(
        val rating: Rating,
        val reviewAt: Long,
        val state: CardState,
        val step: Int?,
        val stability: Double,
        val difficulty: Double,
        val dueAt: Long,
    )

    private fun vectors(): List<List<VectorReview>> = FsrsVectors.sequences.map { seq ->
        seq.split(';').map { r ->
            val f = r.split(',')
            VectorReview(
                rating = Rating.entries.first { it.value == f[0].toInt() },
                reviewAt = f[1].toLong(),
                state = when (f[2]) {
                    "L" -> CardState.LEARNING
                    "R" -> CardState.REVIEW
                    else -> CardState.RELEARNING
                },
                step = if (f[3] == "-") null else f[3].toInt(),
                stability = f[4].toDouble(),
                difficulty = f[5].toDouble(),
                dueAt = f[6].toLong(),
            )
        }
    }

    /** A deterministic review id for sequence [seq], review [i] (varied low 64 bits). */
    private fun reviewId(seq: Int, i: Int): String {
        val z = s.splitMix64(seq.toLong() * 1000L + i)
        val hex = z.toULong().toString(16).padStart(16, '0')
        return "0193a1b2-0001-7000-" + hex.substring(0, 4) + "-" + hex.substring(4)
    }

    private fun rated(id: String, at: Long, rating: Rating, offset: Int? = null) = RatedReview(id, at, rating, offset)

    // ---- golden vectors -----------------------------------------------------------------------

    @Test
    fun goldenVectorsMatchPyFsrs() {
        val seqs = vectors()
        assertEquals(240, seqs.size)
        var count = 0
        for ((si, seq) in seqs.withIndex()) {
            var card = CardSchedule.NEW
            for ((i, v) in seq.withIndex()) {
                card = s.review(card, rated(reviewId(si, i), v.reviewAt, v.rating), vectorParams)
                val where = "seq $si review $i"
                assertEquals(v.state, card.state, where)
                assertEquals(v.step, card.step, where)
                assertEquals(v.dueAt, card.dueAt, where)
                assertTrue(abs(v.stability - (card.stability ?: Double.NaN)) <= 1e-6, "$where stability ${card.stability} vs ${v.stability}")
                assertTrue(abs(v.difficulty - (card.difficulty ?: Double.NaN)) <= 1e-6, "$where difficulty ${card.difficulty} vs ${v.difficulty}")
                assertEquals(v.reviewAt, card.lastReviewAt, where)
                assertEquals(i + 1, card.reps, where)
                if (card.state == CardState.REVIEW) {
                    assertEquals((v.dueAt - v.reviewAt) / day, card.intervalDays!!.toLong(), where)
                } else {
                    assertEquals(0, card.intervalDays, where)
                }
                count++
            }
        }
        assertEquals(3546, count)
    }

    @Test
    fun lapsesCountAgainOnlyFromReview() {
        for ((si, seq) in vectors().withIndex()) {
            var card = CardSchedule.NEW
            var lapses = 0
            for ((i, v) in seq.withIndex()) {
                if (card.state == CardState.REVIEW && v.rating == Rating.AGAIN) lapses++
                card = s.review(card, rated(reviewId(si, i), v.reviewAt, v.rating), vectorParams)
                assertEquals(lapses, card.lapses, "seq $si review $i")
            }
        }
    }

    // ---- cross-platform bit identity ----------------------------------------------------------

    /** Fuzz-on pass: non-trivial easy days, a UTC offset and fixed review ids. */
    private val fuzzParams = vectorParams.copy(fuzz = true, easyDays = listOf(1.0, 1.0, 0.5, 1.0, 0.0, 1.0, 0.3))

    private fun foldAll(params: SchedulerParams, offset: Int?, visit: (CardSchedule) -> Unit) {
        for ((si, seq) in vectors().withIndex()) {
            var card = CardSchedule.NEW
            for ((i, v) in seq.withIndex()) {
                card = s.review(card, rated(reviewId(si, i), v.reviewAt, v.rating, offset), params)
                visit(card)
            }
        }
    }

    @Test
    fun crossPlatformDigestOfAllOutputs() {
        val h = Fnv1a64()
        var nanCount = 0
        val visit: (CardSchedule) -> Unit = { c ->
            val st = c.stability ?: Double.NaN
            val df = c.difficulty ?: Double.NaN
            if (st.isNaN() || df.isNaN()) nanCount++
            h.addDouble(st)
            h.addDouble(df)
            h.addLong(c.dueAt ?: -1L)
        }
        foldAll(vectorParams, null, visit)
        foldAll(fuzzParams, 540, visit)
        assertEquals(0, nanCount, "no stability/difficulty may be NaN (or null after a rated review)")
        assertEquals(hex(SCHEDULER_DIGEST), hex(h.value))
    }

    @Test
    fun fuzzChangesSomeIntervalsAndRespectsEasyDays() {
        var changed = 0
        val plain = mutableListOf<CardSchedule>()
        foldAll(vectorParams, null) { plain += it }
        var i = 0
        foldAll(fuzzParams, 540) { c ->
            val p = plain[i++]
            // the due date never feeds back into the memory state
            assertEquals(p.stability, c.stability)
            assertEquals(p.difficulty, c.difficulty)
            if (c.dueAt != p.dueAt) changed++
            if (p.state == CardState.REVIEW && p.intervalDays!! >= 3) {
                // Friday (weight 0) is never chosen: a fuzz range of >= 2 days is never all Fridays
                val localDay = (c.dueAt!! + 540L * 60_000L).floorDiv(day)
                assertNotEquals(5, s.isoWeekday(localDay), "fuzzed onto a weight-0 day")
            } else {
                assertEquals(p, c)
            }
        }
        assertTrue(changed > 100, "fuzz changed only $changed dues")
    }

    // ---- manual rows (C-1b) -------------------------------------------------------------------

    private val t0 = 1_704_099_600_000L // 2024-01-01T09:00Z

    private fun graduated(): CardSchedule {
        var c = s.review(CardSchedule.NEW, rated(reviewId(0, 0), t0, Rating.EASY), vectorParams)
        c = s.review(c, rated(reviewId(0, 1), t0 + 10 * day, Rating.GOOD), vectorParams)
        assertEquals(CardState.REVIEW, c.state)
        return c
    }

    @Test
    fun setDueThenRatedReviewMeasuresElapsedFromLastRatedReview() {
        val c = graduated()
        val lastRated = c.lastReviewAt!!
        val moved = s.manual(c, ManualAction.SET_DUE, lastRated + 30 * day, lastRated + 20 * day)
        assertEquals(lastRated, moved.lastReviewAt)
        assertEquals(lastRated + 30 * day, moved.dueAt)
        assertEquals(10, moved.intervalDays)
        // rated right after the reschedule (same day as the manual row, 20 days after the last rated one)
        val at = lastRated + 20 * day + 60_000L
        val afterMoved = s.review(moved, rated(reviewId(1, 0), at, Rating.GOOD), vectorParams)
        val afterPlain = s.review(c, rated(reviewId(1, 0), at, Rating.GOOD), vectorParams)
        assertEquals(afterPlain, afterMoved)
        val sameDay = s.review(c.copy(lastReviewAt = at - 60_000L), rated(reviewId(1, 0), at, Rating.GOOD), vectorParams)
        assertNotEquals(sameDay.stability, afterMoved.stability, "must not use the same-day formula")
    }

    @Test
    fun setDueKeepsMemoryStateAndHandlesEveryState() {
        val c = graduated()
        val m = s.manual(c, ManualAction.SET_DUE, c.lastReviewAt!! - day, c.lastReviewAt!! + day)
        assertEquals(c.copy(dueAt = c.lastReviewAt!! - day, intervalDays = 0), m) // past due, interval clamped to 0
        assertSame(CardSchedule.NEW, s.manual(CardSchedule.NEW, ManualAction.SET_DUE, t0, t0))
        val learning = s.review(CardSchedule.NEW, rated(reviewId(2, 0), t0, Rating.GOOD), vectorParams)
        assertEquals(CardState.LEARNING, learning.state)
        assertEquals(learning.copy(dueAt = t0 + 5 * day), s.manual(learning, ManualAction.SET_DUE, t0 + 5 * day, t0 + day))
        assertFailsWith<IllegalArgumentException> { s.manual(c, ManualAction.SET_DUE, null, t0) }
    }

    @Test
    fun resetClearsLastReviewAtAndCounters() {
        val c = graduated()
        val r = s.manual(c, ManualAction.RESET, null, c.lastReviewAt!! + day)
        assertEquals(CardSchedule.NEW, r)
        assertNull(r.lastReviewAt)
        assertEquals(0, r.reps)
        assertEquals(0, r.lapses)
        // the next rated review starts from scratch (initial stability)
        val next = s.review(r, rated(reviewId(3, 0), t0 + 50 * day, Rating.GOOD), vectorParams)
        assertEquals(s.defaultWeights[2], next.stability)
        assertEquals(1, next.reps)
    }

    // ---- other review edge cases --------------------------------------------------------------

    @Test
    fun reviewDatedBeforeLastReviewIsElapsedZero() {
        val c = graduated()
        val back = s.review(c, rated(reviewId(4, 0), c.lastReviewAt!! - 3 * day, Rating.GOOD), vectorParams)
        val sameDay = s.review(c, rated(reviewId(4, 0), c.lastReviewAt!!, Rating.GOOD), vectorParams)
        assertEquals(sameDay.stability, back.stability) // short-term formula, like elapsed 0
        assertEquals(1.0, s.retrievability(c, c.lastReviewAt!! - 3 * day, vectorParams))
    }

    @Test
    fun retrievabilityIsNullForNewAndDecreasesWithTime() {
        assertNull(s.retrievability(CardSchedule.NEW, t0, vectorParams))
        val c = graduated()
        val r0 = s.retrievability(c, c.lastReviewAt!!, vectorParams)!!
        val r1 = s.retrievability(c, c.lastReviewAt!! + 30 * day, vectorParams)!!
        val r2 = s.retrievability(c, c.lastReviewAt!! + 3000 * day, vectorParams)!!
        assertEquals(1.0, r0)
        assertTrue(r1 < r0 && r2 < r1 && r2 > 0.0)
        // at elapsed = interval the recall probability is close to the desired retention
        val atDue = s.retrievability(c, c.dueAt!!, vectorParams)!!
        assertTrue(abs(atDue - 0.9) < 0.01, "R at due $atDue")
    }

    @Test
    fun emptyStepsGraduateImmediatelyAndLapseStaysInReview() {
        val p = vectorParams.copy(learningStepsSeconds = emptyList(), relearningStepsSeconds = emptyList())
        val c = s.review(CardSchedule.NEW, rated(reviewId(5, 0), t0, Rating.AGAIN), p)
        assertEquals(CardState.REVIEW, c.state)
        assertNull(c.step)
        val lapse = s.review(c, rated(reviewId(5, 1), t0 + 10 * day, Rating.AGAIN), p)
        assertEquals(CardState.REVIEW, lapse.state)
        assertEquals(1, lapse.lapses)
        assertTrue(lapse.intervalDays!! >= 1)
    }

    @Test
    fun hardOnFirstStepUsesPyFsrsStepRules() {
        val one = vectorParams.copy(learningStepsSeconds = listOf(61))
        assertEquals(t0 + 91_500L, s.review(CardSchedule.NEW, rated(reviewId(6, 0), t0, Rating.HARD), one).dueAt)
        val two = vectorParams.copy(learningStepsSeconds = listOf(61, 600))
        assertEquals(t0 + 330_500L, s.review(CardSchedule.NEW, rated(reviewId(6, 0), t0, Rating.HARD), two).dueAt)
    }

    @Test
    fun longIntervalsClampToMaximum() {
        val p = vectorParams.copy(maximumIntervalDays = 5)
        var c = CardSchedule.NEW
        for (i in 0 until 5) c = s.review(c, rated(reviewId(7, i), t0 + i * 100 * day, Rating.EASY), p)
        assertEquals(5, c.intervalDays)
        val fuzzed = s.review(c, rated(reviewId(7, 9), t0 + 900 * day, Rating.EASY), p.copy(fuzz = true))
        assertTrue(fuzzed.intervalDays!! in 2..5)
    }

    // ---- fuzz ---------------------------------------------------------------------------------

    @Test
    fun splitMix64MatchesReferenceOutput() {
        // first output of SplitMix64 with state 0 (reference implementation by S. Vigna)
        assertEquals("e220a8397b1dcdaf", s.splitMix64(0L).toULong().toString(16))
        assertEquals(0x8000000000000001UL.toLong(), s.seedOf("0193a1b2-0001-7000-8000-000000000001"))
        assertEquals(-1L, s.seedOf("00000000-0000-0000-ffff-ffffffffffff"))
    }

    @Test
    fun sameReviewIdGivesFixedIntervalOnEveryPlatform() {
        val p = vectorParams.copy(fuzz = true)
        // computed once on jvm; equal on js because only integer and basic double ops are involved
        val expected = listOf(105, 106, 103, 104, 97, 100)
        val ids = listOf(
            "0193a1b2-0001-7000-8000-000000000001",
            "0193a1b2-0001-7000-8000-000000000002",
            "0193a1b2-0001-7000-9abc-def012345678",
            "01931f3e-0000-7000-8000-0000000000a1",
            "00000000-0000-7000-8000-000000000000",
            "ffffffff-ffff-7fff-bfff-ffffffffffff",
        )
        val got = ids.map { s.fuzzedInterval(100, it, t0, null, p) }
        assertEquals(expected, got)
        assertEquals(got, ids.map { s.fuzzedInterval(100, it, t0, null, p) })
    }

    @Test
    fun allOnesEasyDaysGiveUniformFormula() {
        val p = vectorParams.copy(fuzz = true)
        for (ivl in listOf(3, 5, 8, 21, 100, 365, 4000)) {
            val (lo, hi) = s.fuzzRange(ivl, p.maximumIntervalDays)
            for (k in 0 until 50) {
                val id = reviewId(ivl, k)
                val u = s.splitMix64Unit(s.seedOf(id))
                val formula = lo + floor(u * (hi - lo + 1)).toInt()
                assertEquals(formula, s.fuzzedInterval(ivl, id, t0, 120, p), "ivl $ivl id $id")
            }
        }
    }

    @Test
    fun zeroWeightEasyDayIsNeverChosenUnlessAllAreZero() {
        val ivl = 30
        for (zeroDay in 1..7) {
            val easy = List(7) { if (it == zeroDay - 1) 0.0 else 1.0 }
            val p = vectorParams.copy(fuzz = true, easyDays = easy)
            for (k in 0 until 200) {
                val d = s.fuzzedInterval(ivl, reviewId(zeroDay, k), t0, -300, p)
                val local = (t0 - 300L * 60_000L + d * day).floorDiv(day)
                assertNotEquals(zeroDay, s.isoWeekday(local))
            }
        }
        // only Sunday allowed
        val sundays = vectorParams.copy(fuzz = true, easyDays = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        for (k in 0 until 50) {
            val d = s.fuzzedInterval(ivl, reviewId(9, k), t0, null, sundays)
            assertEquals(7, s.isoWeekday((t0 + d * day).floorDiv(day)))
        }
        // all zero = all ones
        val zeros = vectorParams.copy(fuzz = true, easyDays = List(7) { 0.0 })
        val ones = vectorParams.copy(fuzz = true)
        for (k in 0 until 50) {
            assertEquals(s.fuzzedInterval(ivl, reviewId(10, k), t0, null, ones), s.fuzzedInterval(ivl, reviewId(10, k), t0, null, zeros))
        }
    }

    @Test
    fun fuzzRangeMatchesPyFsrs() {
        assertEquals(2 to 4, s.fuzzRange(3, 36500)) // delta 1.075: round(1.925)=2, round(4.075)=4
        assertEquals(2 to 2, s.fuzzRange(3, 2)) // hi clamped, lo = min(lo, hi)
        // 100: delta = 1 + 0.675 + 1.3 + 4.0 = 6.975 -> [93, 107]
        assertEquals(93 to 107, s.fuzzRange(100, 36500))
        assertEquals(2, s.fuzzedInterval(2, reviewId(0, 0), t0, null, vectorParams.copy(fuzz = true))) // < 2.5 not fuzzed
    }

    @Test
    fun isoWeekdayOfEpochDays() {
        assertEquals(4, s.isoWeekday(0)) // 1970-01-01 Thursday
        assertEquals(3, s.isoWeekday(-1))
        assertEquals(1, s.isoWeekday(t0.floorDiv(day))) // 2024-01-01 Monday
    }

    @Test
    fun nullOffsetIsUtcForEasyDays() {
        val p = vectorParams.copy(fuzz = true, easyDays = listOf(0.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0))
        for (k in 0 until 50) {
            val id = reviewId(11, k)
            assertEquals(s.fuzzedInterval(30, id, t0, 0, p), s.fuzzedInterval(30, id, t0, null, p))
        }
    }

    // ---- preview ------------------------------------------------------------------------------

    @Test
    fun previewEqualsReviewForAllFourRatings() {
        val p = vectorParams.copy(fuzz = true)
        val states = listOf(CardSchedule.NEW, graduated(), s.review(graduated(), rated(reviewId(12, 0), t0 + 40 * day, Rating.AGAIN), p))
        val now = t0 + 60 * day
        for (c in states) {
            for (id in listOf(reviewId(12, 1), reviewId(12, 2))) {
                val previews = s.preview(c, now, p, id, 540)
                assertEquals(Rating.entries, previews.map { it.rating })
                for (o in previews) {
                    val real = s.review(c, rated(id, now, o.rating, 540), p)
                    assertEquals(real, o.after)
                    assertEquals(real.dueAt!! - now, o.intervalMs)
                }
            }
            // null id = unfuzzed
            val unfuzzed = s.preview(c, now, p, null, null)
            for (o in unfuzzed) assertEquals(s.review(c, rated(reviewId(12, 3), now, o.rating), p.copy(fuzz = false)), o.after)
        }
    }

    @Test
    fun coreJsPreviewIntervalsMatchesPreview() {
        val c = graduated()
        val js = JsCardSchedule(
            "review", null, c.stability, c.difficulty, c.dueAt!!.toDouble(), c.intervalDays,
            c.lastReviewAt!!.toDouble(), c.reps, c.lapses,
        )
        val preset = JsPreset(0.9, 36500, arrayOf(60, 600), arrayOf(600), true, Array(7) { 1.0 }, null, null, 0)
        val now = t0 + 60 * day
        val id = reviewId(13, 0)
        val got = kotobayaCore().previewIntervals(js, preset, now.toDouble(), id, 60)
        val expected = s.preview(c, now, vectorParams.copy(fuzz = true), id, 60)
        assertEquals(expected.map { it.rating.value }, got.map { it.rating })
        assertEquals(expected.map { it.after.dueAt!!.toDouble() }, got.map { it.dueAtMs })
        assertEquals(expected.map { it.intervalMs.toDouble() }, got.map { it.intervalMs })
        assertEquals(expected.map { it.after.state.wire }, got.map { it.state })
        assertFailsWith<IllegalArgumentException> { kotobayaCore().previewIntervals(js, preset, 1.5, id, 60) }
    }

    // ---- paramsOf -----------------------------------------------------------------------------

    private fun row(weights: List<Double>?, version: String? = "6") = PresetRow(
        id = Reserved.DEFAULT_PRESET_ID,
        desiredRetention = 0.85,
        maximumIntervalDays = 3650,
        learningStepsSeconds = listOf(60),
        relearningStepsSeconds = emptyList(),
        fuzz = true,
        easyDays = listOf(1.0, 1.0, 1.0, 1.0, 1.0, 0.5, 0.0),
        fsrsVersion = version,
        weights = weights,
        weightsVersion = 7,
        deleted = false,
    )

    @Test
    fun paramsOfUsesWeightsWithinBounds() {
        val w = s.defaultWeights.mapIndexed { i, x -> if (i == 20) 0.2 else x }
        val p = s.paramsOf(row(w))
        assertEquals(w, p.weights)
        assertEquals(0.85, p.desiredRetention)
        assertEquals(3650, p.maximumIntervalDays)
        assertEquals(listOf(60), p.learningStepsSeconds)
        assertEquals(emptyList(), p.relearningStepsSeconds)
        assertTrue(p.fuzz)
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 1.0, 0.5, 0.0), p.easyDays)
        assertEquals(7, p.weightsVersion)
        // the bounds themselves are inside
        assertEquals(s.LOWER_BOUNDS, s.paramsOf(row(s.LOWER_BOUNDS)).weights)
        assertEquals(s.UPPER_BOUNDS, s.paramsOf(row(s.UPPER_BOUNDS)).weights)
    }

    @Test
    fun paramsOfFallsBackToAllDefaultsOnAnyBadWeight() {
        val ulp = 1e-9
        for (i in 0 until 21) {
            val below = s.LOWER_BOUNDS.mapIndexed { j, x -> if (j == i) x - ulp else x }
            val above = s.UPPER_BOUNDS.mapIndexed { j, x -> if (j == i) x + ulp else x }
            val nan = s.defaultWeights.mapIndexed { j, x -> if (j == i) Double.NaN else x }
            for (w in listOf(below, above, nan)) {
                val p = s.paramsOf(row(w))
                assertEquals(s.defaultWeights, p.weights, "index $i")
                assertEquals(7, p.weightsVersion)
            }
        }
        assertEquals(s.defaultWeights, s.paramsOf(row(null, null)).weights)
        assertEquals(s.defaultWeights, s.paramsOf(row(s.defaultWeights.take(20))).weights)
        assertEquals(s.defaultWeights, s.paramsOf(row(s.defaultWeights.map { it }, "5")).weights)
    }

    @Test
    fun paramsOfFallbacksForRetentionIntervalAndEasyDays() {
        for (bad in listOf(0.0, 1.0, -0.1, 1.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(0.9, s.paramsOf(row(null).copy(desiredRetention = bad)).desiredRetention)
        }
        assertEquals(36500, s.paramsOf(row(null).copy(maximumIntervalDays = 0)).maximumIntervalDays)
        assertEquals(1, s.paramsOf(row(null).copy(maximumIntervalDays = 1)).maximumIntervalDays)
        val ones = List(7) { 1.0 }
        for (bad in listOf(List(6) { 1.0 }, List(8) { 1.0 }, ones.take(6) + 1.5, ones.take(6) + -0.1, ones.take(6) + Double.NaN)) {
            assertEquals(ones, s.paramsOf(row(null).copy(easyDays = bad)).easyDays)
        }
    }

    @Test
    fun paramsOfFallbackValuesArePyFsrsDefaults() {
        // py-fsrs 6.3.2 DEFAULT_PARAMETERS, Scheduler(desired_retention=0.9, maximum_interval=36500)
        val pyDefaults = listOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )
        assertEquals(pyDefaults, s.defaultWeights)
        assertEquals(0.9, FsrsScheduler.DEFAULT_DESIRED_RETENTION)
        assertEquals(36500, FsrsScheduler.DEFAULT_MAXIMUM_INTERVAL)
        assertEquals("6", s.fsrsVersion)
        // the defaults are within py-fsrs's own bounds
        assertEquals(pyDefaults, s.paramsOf(row(pyDefaults)).weights)
        assertFalse(s.defaultWeights.any { it.isNaN() })
    }

    // ---- rounding -----------------------------------------------------------------------------

    @Test
    fun roundHalfEvenEdgeCases() {
        assertEquals(2.0, roundHalfEven(2.5))
        assertEquals(4.0, roundHalfEven(3.5))
        assertEquals(0.0, roundHalfEven(0.5))
        assertEquals(2.0, roundHalfEven(2.4999999999999996))
        assertEquals(4503599627370496.0, roundHalfEven(4503599627370495.5))
        assertEquals(-2.0, roundHalfEven(-1.5))
        assertEquals(-2.0, roundHalfEven(-2.5))
        // -0.5 -> +0.0: f = -1 (odd), so f + 1 = +0.0 in IEEE arithmetic. Python's round(-0.5) is -0.0;
        // the sign of zero is irrelevant here because every rounded value is clamped by max(., 1) or max(2, .).
        assertEquals(0.0.toRawBits(), roundHalfEven(-0.5).toRawBits())
    }

    private fun hex(v: Long): String = "0x" + v.toULong().toString(16)

    private companion object {
        // Computed once on jvm (crossPlatformDigestOfAllOutputs); must be equal on js and android.
        const val SCHEDULER_DIGEST: Long = -0x32b4aeff76906898L // 0xcd4b5100896f9768
    }
}
