package kotobaya.fsrs

import kotobaya.contracts.InstantMs
import kotobaya.contracts.Reserved
import kotobaya.contracts.Uuid
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.PortableMath
import kotobaya.contracts.fsrs.PresetRow
import kotobaya.contracts.fsrs.RatedReview
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.RatingOutcome
import kotobaya.contracts.fsrs.Scheduler
import kotobaya.contracts.fsrs.SchedulerParams
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * FSRS-6, a line-by-line port of py-fsrs 6.3.2 `Scheduler.review_card` (see [Scheduler] for the
 * normative rules). Every formula keeps py-fsrs's operand order and parenthesisation; `a ** b` is
 * [PortableMath.pow] and `math.e ** x` is `pow(PortableMath.E, x)` (py-fsrs 6.3.2 calls no
 * `math.exp`/`math.log`). Python's `round()` is [roundHalfEven].
 *
 * Fuzz is seeded by the review id (SplitMix64) and weighted by Easy Days only. **Load balancing is
 * not implemented** (ADR-005: it would make one card's cache depend on other cards; the SPEC
 * wording about load balancing is superseded).
 */
public object FsrsScheduler : Scheduler {
    private val math: PortableMath = FdlibmMath
    private const val E: Double = PortableMath.E

    private const val STABILITY_MIN: Double = 0.001
    private const val MIN_DIFFICULTY: Double = 1.0
    private const val MAX_DIFFICULTY: Double = 10.0

    /** py-fsrs 6.3.2 defaults used by [paramsOf]'s fallbacks. */
    internal const val DEFAULT_DESIRED_RETENTION: Double = 0.9
    internal const val DEFAULT_MAXIMUM_INTERVAL: Int = 36500
    internal val DEFAULT_EASY_DAYS: List<Double> = List(7) { 1.0 }

    /** py-fsrs 6.3.2 `LOWER_BOUNDS_PARAMETERS`. */
    internal val LOWER_BOUNDS: List<Double> = listOf(
        0.001, 0.001, 0.001, 0.001, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0, 0.001,
        0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1,
    )

    /** py-fsrs 6.3.2 `UPPER_BOUNDS_PARAMETERS`. */
    internal val UPPER_BOUNDS: List<Double> = listOf(
        100.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 0.75, 4.5, 0.8, 3.5,
        5.0, 0.25, 0.9, 4.0, 1.0, 6.0, 2.0, 2.0, 0.8, 0.8,
    )

    override val fsrsVersion: String = "6"

    override val defaultWeights: List<Double> = listOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
        1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )

    override fun paramsOf(row: PresetRow): SchedulerParams {
        val w = row.weights
        val weights = if (w != null && row.fsrsVersion == fsrsVersion && weightsInBounds(w)) w else defaultWeights
        val retention = row.desiredRetention
        val desiredRetention =
            if (retention.isFinite() && retention > 0.0 && retention < 1.0) retention else DEFAULT_DESIRED_RETENTION
        val maximumInterval = if (row.maximumIntervalDays >= 1) row.maximumIntervalDays else DEFAULT_MAXIMUM_INTERVAL
        val easyDays = if (row.easyDays.size == 7 && row.easyDays.all { it.isFinite() && it >= 0.0 && it <= 1.0 }) {
            row.easyDays
        } else {
            DEFAULT_EASY_DAYS
        }
        return SchedulerParams(
            weights = weights,
            desiredRetention = desiredRetention,
            maximumIntervalDays = maximumInterval,
            learningStepsSeconds = row.learningStepsSeconds,
            relearningStepsSeconds = row.relearningStepsSeconds,
            fuzz = row.fuzz,
            easyDays = easyDays,
            weightsVersion = row.weightsVersion,
        )
    }

    /** NaN fails both comparisons, so it is out of bounds. */
    private fun weightsInBounds(w: List<Double>): Boolean =
        w.size == LOWER_BOUNDS.size && w.indices.all { LOWER_BOUNDS[it] <= w[it] && w[it] <= UPPER_BOUNDS[it] }

    override fun review(before: CardSchedule, review: RatedReview, params: SchedulerParams): CardSchedule =
        fold(before, review.reviewedAt, review.rating, review.id, review.utcOffsetMin, params)

    override fun manual(
        before: CardSchedule,
        action: ManualAction,
        manualDueAt: InstantMs?,
        reviewedAt: InstantMs,
    ): CardSchedule = when (action) {
        ManualAction.RESET -> CardSchedule.NEW
        ManualAction.SET_DUE -> {
            requireNotNull(manualDueAt) { "set_due needs manualDueAt" }
            when (before.state) {
                CardState.NEW -> before
                CardState.REVIEW -> before.copy(
                    dueAt = manualDueAt,
                    intervalDays = max(0L, (manualDueAt - reviewedAt).floorDiv(Reserved.DAY_MS)).toInt(),
                )
                CardState.LEARNING, CardState.RELEARNING -> before.copy(dueAt = manualDueAt)
            }
        }
    }

    override fun preview(
        current: CardSchedule,
        now: InstantMs,
        params: SchedulerParams,
        reviewId: Uuid?,
        utcOffsetMin: Int?,
    ): List<RatingOutcome> = Rating.entries.map { rating ->
        val after = fold(current, now, rating, reviewId, utcOffsetMin, params)
        val due = after.dueAt ?: now
        RatingOutcome(rating, after, max(0L, due - now))
    }

    override fun retrievability(schedule: CardSchedule, at: InstantMs, params: SchedulerParams): Double? {
        val stability = schedule.stability ?: return null
        val last = schedule.lastReviewAt ?: return null
        if (schedule.state == CardState.NEW) return null
        return Fsrs(params).retrievability(stability, elapsedDays(last, at))
    }

    /** py-fsrs `timedelta.days` of `to − from` (floor), clamped to `>= 0`; Long arithmetic only. */
    private fun elapsedDays(from: InstantMs, to: InstantMs): Long = max(0L, (to - from).floorDiv(Reserved.DAY_MS))

    /** The body of `review` / `preview`; [fuzzSeed] null = no fuzz. */
    private fun fold(
        before: CardSchedule,
        reviewedAt: InstantMs,
        rating: Rating,
        fuzzSeed: Uuid?,
        utcOffsetMin: Int?,
        params: SchedulerParams,
    ): CardSchedule {
        val f = Fsrs(params)
        val last = before.lastReviewAt
        // py-fsrs `days_since_last_review` (None when there is no last review)
        val days: Long? = if (last == null) null else elapsedDays(last, reviewedAt)
        val sameDay = days != null && days < 1

        var state: CardState
        var step: Int?
        var stability: Double
        var difficulty: Double
        // the next interval: either whole days (REVIEW) or milliseconds ((re)learning)
        var intervalDays = 0
        var stepMs = 0L
        var toReview = false

        val s0 = before.stability
        val d0 = before.difficulty
        when (before.state) {
            CardState.NEW, CardState.LEARNING -> {
                state = CardState.LEARNING
                step = if (before.state == CardState.NEW) 0 else before.step ?: 0
                if (before.state == CardState.NEW || s0 == null || d0 == null) {
                    stability = f.initialStability(rating)
                    difficulty = f.initialDifficulty(rating, clamp = true)
                } else if (sameDay) {
                    stability = f.shortTermStability(s0, rating)
                    difficulty = f.nextDifficulty(d0, rating)
                } else {
                    stability = f.nextStability(d0, s0, retrievabilityAt(f, s0, last, reviewedAt), rating)
                    difficulty = f.nextDifficulty(d0, rating)
                }
                val steps = params.learningStepsSeconds
                val stepIndex = step
                if (steps.isEmpty() || (stepIndex >= steps.size && rating != Rating.AGAIN)) {
                    toReview = true
                } else {
                    when (rating) {
                        Rating.AGAIN -> {
                            step = 0
                            stepMs = seconds(steps[0])
                        }
                        Rating.HARD -> stepMs = hardStepMs(steps, stepIndex)
                        Rating.GOOD -> if (stepIndex + 1 == steps.size) {
                            toReview = true
                        } else {
                            step = stepIndex + 1
                            stepMs = seconds(steps[stepIndex + 1])
                        }
                        Rating.EASY -> toReview = true
                    }
                }
            }
            CardState.REVIEW -> {
                val s = requireNotNull(s0) { "REVIEW card without stability" }
                val d = requireNotNull(d0) { "REVIEW card without difficulty" }
                state = CardState.REVIEW
                step = null
                stability = if (sameDay) {
                    f.shortTermStability(s, rating)
                } else {
                    f.nextStability(d, s, retrievabilityAt(f, s, last, reviewedAt), rating)
                }
                difficulty = f.nextDifficulty(d, rating)
                val steps = params.relearningStepsSeconds
                if (rating == Rating.AGAIN && steps.isNotEmpty()) {
                    state = CardState.RELEARNING
                    step = 0
                    stepMs = seconds(steps[0])
                } else {
                    toReview = true
                }
            }
            CardState.RELEARNING -> {
                val s = requireNotNull(s0) { "RELEARNING card without stability" }
                val d = requireNotNull(d0) { "RELEARNING card without difficulty" }
                state = CardState.RELEARNING
                step = before.step ?: 0
                if (sameDay) {
                    stability = f.shortTermStability(s, rating)
                    difficulty = f.nextDifficulty(d, rating)
                } else {
                    stability = f.nextStability(d, s, retrievabilityAt(f, s, last, reviewedAt), rating)
                    difficulty = f.nextDifficulty(d, rating)
                }
                val steps = params.relearningStepsSeconds
                val stepIndex = step
                if (steps.isEmpty() || (stepIndex >= steps.size && rating != Rating.AGAIN)) {
                    toReview = true
                } else {
                    when (rating) {
                        Rating.AGAIN -> {
                            step = 0
                            stepMs = seconds(steps[0])
                        }
                        Rating.HARD -> stepMs = hardStepMs(steps, stepIndex)
                        Rating.GOOD -> if (stepIndex + 1 == steps.size) {
                            toReview = true
                        } else {
                            step = stepIndex + 1
                            stepMs = seconds(steps[stepIndex + 1])
                        }
                        Rating.EASY -> toReview = true
                    }
                }
            }
        }

        val dueAt: InstantMs
        if (toReview) {
            state = CardState.REVIEW
            step = null
            intervalDays = f.nextInterval(stability)
            if (params.fuzz && fuzzSeed != null) {
                intervalDays = fuzzedInterval(intervalDays, fuzzSeed, reviewedAt, utcOffsetMin, params)
            }
            dueAt = reviewedAt + intervalDays.toLong() * Reserved.DAY_MS
        } else {
            dueAt = reviewedAt + stepMs
        }

        return CardSchedule(
            state = state,
            step = step,
            stability = stability,
            difficulty = difficulty,
            dueAt = dueAt,
            intervalDays = if (state == CardState.REVIEW) intervalDays else 0,
            lastReviewAt = reviewedAt,
            reps = before.reps + 1,
            lapses = if (before.state == CardState.REVIEW && rating == Rating.AGAIN) before.lapses + 1 else before.lapses,
        )
    }

    /** py-fsrs `get_card_retrievability` at the review time (0 when there is no last review). */
    private fun retrievabilityAt(f: Fsrs, stability: Double, last: InstantMs?, at: InstantMs): Double =
        if (last == null) 0.0 else f.retrievability(stability, elapsedDays(last, at))

    private fun seconds(s: Int): Long = s.toLong() * 1000L

    /**
     * py-fsrs Hard in (re)learning: step 0 with one step → `steps[0] * 1.5`; step 0 with two or more →
     * `(steps[0] + steps[1]) / 2.0`; else `steps[step]`. Integer seconds, so both are exact in ms.
     */
    private fun hardStepMs(steps: List<Int>, step: Int): Long = when {
        step == 0 && steps.size == 1 -> steps[0].toLong() * 1500L
        step == 0 -> (steps[0].toLong() + steps[1].toLong()) * 1000L / 2L
        else -> seconds(steps[step])
    }

    /** Contract fuzz (Scheduler KDoc steps 1–4), applied to a REVIEW interval of [ivl] whole days. */
    internal fun fuzzedInterval(
        ivl: Int,
        reviewId: Uuid,
        reviewedAt: InstantMs,
        utcOffsetMin: Int?,
        params: SchedulerParams,
    ): Int {
        if (ivl < 3) return ivl // py-fsrs: `interval_days < 2.5` is not fuzzed
        val (lo, hi) = fuzzRange(ivl, params.maximumIntervalDays)
        val n = hi - lo + 1
        val offsetMs = (utcOffsetMin ?: 0).toLong() * 60_000L
        val raw = DoubleArray(n) { i ->
            val localDay = (reviewedAt + offsetMs + (lo + i).toLong() * Reserved.DAY_MS).floorDiv(Reserved.DAY_MS)
            params.easyDays[isoWeekday(localDay) - 1]
        }
        val weights = if (raw.all { it == 0.0 }) DoubleArray(n) { 1.0 } else raw
        var total = 0.0
        for (w in weights) total += w
        val target = splitMix64Unit(seedOf(reviewId)) * total
        var cumulative = 0.0
        var lastPositive = lo
        for (i in 0 until n) {
            if (weights[i] > 0.0) lastPositive = lo + i
            cumulative += weights[i]
            if (cumulative > target) return lo + i
        }
        // only reachable if u * total rounds up to total; never pick a zero-weight day
        return lastPositive
    }

    /** py-fsrs `_get_fuzz_range`. */
    internal fun fuzzRange(ivl: Int, maximumIntervalDays: Int): Pair<Int, Int> {
        val x = ivl.toDouble()
        var delta = 1.0
        delta += 0.15 * max(min(x, 7.0) - 2.5, 0.0)
        delta += 0.1 * max(min(x, 20.0) - 7.0, 0.0)
        delta += 0.05 * max(min(x, Double.POSITIVE_INFINITY) - 20.0, 0.0)
        var lo = roundHalfEven(x - delta).toInt()
        var hi = roundHalfEven(x + delta).toInt()
        lo = max(2, lo)
        hi = min(hi, maximumIntervalDays)
        lo = min(lo, hi)
        return lo to hi
    }

    /** ISO weekday (1 = Monday … 7 = Sunday) of an epoch day (day 0 = 1970-01-01, a Thursday). */
    internal fun isoWeekday(epochDay: Long): Int = (epochDay + 3).mod(7L).toInt() + 1

    /** The last 16 hex digits of [id] (dashes removed) as an unsigned 64-bit integer. */
    internal fun seedOf(id: Uuid): Long {
        val hex = id.replace("-", "")
        require(hex.length >= 16) { "review id too short: $id" }
        var seed = 0L
        for (c in hex.substring(hex.length - 16)) {
            val digit = c.digitToInt(16)
            seed = (seed shl 4) or digit.toLong()
        }
        return seed
    }

    /** One SplitMix64 output of state [seed], as a double in `[0, 1)`. */
    internal fun splitMix64Unit(seed: Long): Double = (splitMix64(seed) ushr 11).toDouble() * TWO_POW_M53

    /** SplitMix64 (Steele, Lea, Flood 2014): the first output for state [seed]; wrapping arithmetic. */
    internal fun splitMix64(seed: Long): Long {
        var z = seed + -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    private const val TWO_POW_M53: Double = 1.0 / 9007199254740992.0

    /** py-fsrs's per-scheduler formulas for one parameter set. */
    private class Fsrs(params: SchedulerParams) {
        private val w = params.weights
        private val desiredRetention = params.desiredRetention
        private val maximumInterval = params.maximumIntervalDays
        private val decay = -w[20]
        private val factor = math.pow(0.9, 1 / decay) - 1

        fun retrievability(stability: Double, elapsedDays: Long): Double =
            math.pow(1 + factor * elapsedDays.toDouble() / stability, decay)

        fun initialStability(rating: Rating): Double = max(w[rating.value - 1], STABILITY_MIN)

        fun initialDifficulty(rating: Rating, clamp: Boolean): Double {
            val d = w[4] - math.pow(E, w[5] * (rating.value - 1)) + 1
            return if (clamp) clampDifficulty(d) else d
        }

        fun nextInterval(stability: Double): Int {
            val next = (stability / factor) * (math.pow(desiredRetention, 1 / decay) - 1)
            var days = roundHalfEven(next).toInt()
            days = max(days, 1)
            days = min(days, maximumInterval)
            return days
        }

        fun shortTermStability(stability: Double, rating: Rating): Double {
            var increase = math.pow(E, w[17] * (rating.value - 3 + w[18])) * math.pow(stability, -w[19])
            if (rating != Rating.AGAIN) increase = max(increase, 1.0)
            return max(stability * increase, STABILITY_MIN)
        }

        fun nextDifficulty(difficulty: Double, rating: Rating): Double {
            val arg1 = initialDifficulty(Rating.EASY, clamp = false)
            val deltaDifficulty = -(w[6] * (rating.value - 3))
            val arg2 = difficulty + (10.0 - difficulty) * deltaDifficulty / 9.0
            val next = w[7] * arg1 + (1 - w[7]) * arg2
            return clampDifficulty(next)
        }

        fun nextStability(difficulty: Double, stability: Double, retrievability: Double, rating: Rating): Double {
            val next = if (rating == Rating.AGAIN) {
                nextForgetStability(difficulty, stability, retrievability)
            } else {
                nextRecallStability(difficulty, stability, retrievability, rating)
            }
            return max(next, STABILITY_MIN)
        }

        private fun nextForgetStability(difficulty: Double, stability: Double, retrievability: Double): Double {
            val longTerm = w[11] *
                math.pow(difficulty, -w[12]) *
                (math.pow(stability + 1, w[13]) - 1) *
                math.pow(E, (1 - retrievability) * w[14])
            val shortTerm = stability / math.pow(E, w[17] * w[18])
            return min(longTerm, shortTerm)
        }

        private fun nextRecallStability(
            difficulty: Double,
            stability: Double,
            retrievability: Double,
            rating: Rating,
        ): Double {
            val hardPenalty = if (rating == Rating.HARD) w[15] else 1.0
            val easyBonus = if (rating == Rating.EASY) w[16] else 1.0
            return stability * (
                1 +
                    math.pow(E, w[8]) *
                    (11 - difficulty) *
                    math.pow(stability, -w[9]) *
                    (math.pow(E, (1 - retrievability) * w[10]) - 1) *
                    hardPenalty *
                    easyBonus
                )
        }

        private fun clampDifficulty(d: Double): Double = min(max(d, MIN_DIFFICULTY), MAX_DIFFICULTY)
    }
}
