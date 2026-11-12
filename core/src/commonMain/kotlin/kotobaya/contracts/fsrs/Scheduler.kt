package kotobaya.contracts.fsrs

import kotobaya.contracts.InstantMs
import kotobaya.contracts.Uuid

/** FSRS rating (`review_log.rating`). */
public enum class Rating(
    /** Wire / database value 1–4. */
    public val value: Int,
) {
    /** 1. A failure; counts as a lapse when the card was in [CardState.REVIEW]. */
    AGAIN(1),

    /** 2. */
    HARD(2),

    /** 3. */
    GOOD(3),

    /** 4. */
    EASY(4),
}

/**
 * Button actually pressed (`review_log.button`). Four-button mode uses again/hard/good/easy;
 * jpdb-style five-button mode uses nothing/something/hard/okay/easy (SPEC "Grading").
 */
public enum class Button(
    /** Wire / database value. */
    public val wire: String,
    /** The rating this button means; the database CHECK enforces the pairing. */
    public val rating: Rating,
) {
    /** Four-button Again. */
    AGAIN("again", Rating.AGAIN),

    /** Hard (both modes). */
    HARD("hard", Rating.HARD),

    /** Four-button Good. */
    GOOD("good", Rating.GOOD),

    /** Easy (both modes). */
    EASY("easy", Rating.EASY),

    /** Five-button "Nothing" (no recall); rating Again. */
    NOTHING("nothing", Rating.AGAIN),

    /** Five-button "Something" (partial recall); rating Again, kept for analysis. */
    SOMETHING("something", Rating.AGAIN),

    /** Five-button "Okay"; rating Good. */
    OKAY("okay", Rating.GOOD),
}

/** `card.state` (cache). */
public enum class CardState(
    /** Wire / database value. */
    public val wire: String,
) {
    /** No schedule-feeding review yet, or reset. `due_at`, `stability`, `difficulty`, `step` are null. */
    NEW("new"),

    /** In the preset's learning steps (py-fsrs `Learning`). */
    LEARNING("learning"),

    /** Graduated (py-fsrs `Review`). */
    REVIEW("review"),

    /** In the preset's relearning steps after a lapse (py-fsrs `Relearning`). */
    RELEARNING("relearning"),
}

/** `review_log.review_type`. */
public enum class ReviewType(
    /** Wire / database value. */
    public val wire: String,
    /** True for the types that are in a replay set (SYNC.md 9.1): scheduled, learning, manual. */
    public val feedsSchedule: Boolean,
) {
    /** A due review card answered from the queue. */
    SCHEDULED("scheduled", true),

    /** A new / learning / relearning card answered from the queue. */
    LEARNING("learning", true),

    /** Extra practice (hot words, confusables): logged, never changes a schedule. */
    DRILL("drill", false),

    /** Cramming: logged, never changes a schedule. */
    CRAM("cram", false),

    /** Reschedule or reset from the card browser; no rating. See [ManualAction]. */
    MANUAL("manual", true),
}

/** `review_log.manual_action` for [ReviewType.MANUAL] rows. */
public enum class ManualAction(
    /** Wire / database value. */
    public val wire: String,
) {
    /** Keep the memory state, set `due_at := manual_due_at` (see [Scheduler.manual] for every state). */
    SET_DUE("set_due"),

    /** The card returns to [CardState.NEW]; reps and lapses restart from 0. */
    RESET("reset"),
}

/**
 * The scheduling inputs of one preset (an `fsrs_params` row, after [Scheduler.paramsOf]).
 *
 * Example (the Default preset with no optimizer run): `weights = Scheduler.defaultWeights`,
 * `desiredRetention = 0.9`, `maximumIntervalDays = 36500`, `learningStepsSeconds = [60, 600]`,
 * `relearningStepsSeconds = [600]`, `fuzz = true`, `easyDays = [1,1,1,1,1,1,1]`, `weightsVersion = 0`.
 */
public data class SchedulerParams(
    /** The 21 FSRS-6 weights `w[0]..w[20]`. */
    public val weights: List<Double>,
    /** Target recall probability, `0 < r < 1`. */
    public val desiredRetention: Double,
    /** Upper bound of a review interval in days, `>= 1`. */
    public val maximumIntervalDays: Int,
    /** Learning steps in seconds, in order; empty = a new card graduates on its first review. */
    public val learningStepsSeconds: List<Int>,
    /** Relearning steps in seconds; empty = a lapsed card goes straight back to review. */
    public val relearningStepsSeconds: List<Int>,
    /** Whether review intervals are fuzzed (deterministically, see [Scheduler.review]). */
    public val fuzz: Boolean,
    /** Seven load multipliers, index 0 = Monday … 6 = Sunday; 1 = normal, 0 = avoid. Used only with [fuzz]. */
    public val easyDays: List<Double>,
    /** `fsrs_params.weights_version` the [weights] belong to; stamped into every cache computed with them. */
    public val weightsVersion: Int,
)

/** The columns of an `fsrs_params` row that scheduling reads (`contracts/db/V1__init.sql`). */
public data class PresetRow(
    /** `id`. */
    public val id: Uuid,
    /** `desired_retention`. */
    public val desiredRetention: Double,
    /** `maximum_interval_days`. */
    public val maximumIntervalDays: Int,
    /** `learning_steps_s`. */
    public val learningStepsSeconds: List<Int>,
    /** `relearning_steps_s`. */
    public val relearningStepsSeconds: List<Int>,
    /** `fuzz`. */
    public val fuzz: Boolean,
    /** `easy_days` (7 values, Monday first). */
    public val easyDays: List<Double>,
    /** `fsrs_version`; null iff [weights] is null. */
    public val fsrsVersion: String?,
    /** `weights`; null = never optimized. */
    public val weights: List<Double>?,
    /** `weights_version`. */
    public val weightsVersion: Int,
    /** True iff `deleted_at` is set. */
    public val deleted: Boolean,
)

/**
 * The FSRS part of a card's cache (`card.state … lapses`, without the replay bookkeeping
 * `last_review_id` / `weights_version`, which live in [kotobaya.contracts.replay.CardCache]).
 *
 * Invariants (mirroring the `card` CHECKs):
 * - `state == NEW` ⇔ `dueAt == null`; when NEW, `step`, `stability`, `difficulty`, `intervalDays` are null.
 * - `state` LEARNING/RELEARNING ⇒ `step != null`, `intervalDays == 0`.
 * - `state == REVIEW` ⇒ `step == null`, `intervalDays >= 1` (except after [Scheduler.manual] SET_DUE, `>= 0`).
 * - `stability > 0`, `1 <= difficulty <= 10` whenever non-null.
 */
public data class CardSchedule(
    /** `card.state`. */
    public val state: CardState,
    /** `card.step`: index into the (re)learning steps while LEARNING/RELEARNING, else null. */
    public val step: Int?,
    /** `card.stability` in days. */
    public val stability: Double?,
    /** `card.difficulty`, 1–10. */
    public val difficulty: Double?,
    /** `card.due_at`. */
    public val dueAt: InstantMs?,
    /** `card.interval_days`: whole days scheduled by the last review; 0 in (re)learning; null when NEW. */
    public val intervalDays: Int?,
    /**
     * `card.last_review_at` (C-1b): `reviewed_at` of the last **rated** (scheduled/learning) row
     * folded since the last reset; null if none. Manual SET_DUE rows leave it unchanged; RESET sets it
     * to null. Elapsed time and retrievability are always measured from it.
     */
    public val lastReviewAt: InstantMs?,
    /** `card.reps`: number of folded rated (scheduled/learning) reviews since the last reset. */
    public val reps: Int,
    /** `card.lapses`: number of AGAIN ratings given while the state was REVIEW, since the last reset. */
    public val lapses: Int,
) {
    /** Constants. */
    public companion object {
        /** A card with no schedule-feeding review: state NEW, everything null, counters 0. */
        public val NEW: CardSchedule = CardSchedule(CardState.NEW, null, null, null, null, null, null, 0, 0)
    }
}

/** One rated answer, as the scheduler needs it. */
public data class RatedReview(
    /** `review_log.id`; the only seed of the fuzz (so fuzz is a pure function of stored data). */
    public val id: Uuid,
    /** `review_log.reviewed_at`. */
    public val reviewedAt: InstantMs,
    /** `review_log.rating`. */
    public val rating: Rating,
    /**
     * `review_log.utc_offset_min`; null = unknown. **Deviation, stated on purpose:** a null offset is
     * treated as 0 (UTC) for easy days. The scheduler is pure (no settings, no time-zone database), so
     * it cannot fall back to `scheduler.timezone` as stats do (SCHEMA `utc_offset_min`). jpdb imports
     * have null offsets, so their fuzzed easy-day choice uses UTC weekdays.
     */
    public val utcOffsetMin: Int?,
)

/** What one rating would do, for the answer buttons. */
public data class RatingOutcome(
    /** The rating. */
    public val rating: Rating,
    /** The schedule after answering with [rating]. */
    public val after: CardSchedule,
    /** `after.dueAt − now` in ms (`>= 0`). The UI formats it through i18n ("10m", "3d"). */
    public val intervalMs: Long,
)

/**
 * FSRS-6, identical on server, phone and web (ADR-002). Pure and deterministic: no clock, no
 * randomness except the seeded fuzz below, no I/O.
 *
 * **Reference.** With fuzz off, [review] behaves exactly as `Scheduler.review_card` of
 * **py-fsrs 6.3.2** (FSRS-6) with the same parameters, under this mapping:
 * - [CardState.NEW] = a fresh py-fsrs `Card()` (state Learning, step 0, no stability/difficulty,
 *   no `last_review`); the other states map by name.
 * - Elapsed days = `floor((reviewedAt − lastReviewAt) / 86400000)`, clamped to `>= 0` (py-fsrs
 *   `timedelta.days`); `< 1` uses the same-day (short-term) stability formula.
 * - `dueAt = reviewedAt + interval` where the interval is the step length in seconds in
 *   (re)learning or whole days in review; `intervalDays` = those days in REVIEW, 0 otherwise.
 * - `reps += 1` for every rated review; `lapses += 1` iff the state before was REVIEW and the rating AGAIN.
 *
 * Golden vectors: every review of every sequence of `fixtures/fsrs/vectors.json` (fuzz off,
 * default weights, retention 0.9, steps 60/600 s and 600 s, max 36500) must give `state`, `step`,
 * `dueAt` exactly and `stability`, `difficulty` within 1e-6 of py-fsrs.
 *
 * **Cross-platform determinism (normative).** For the same inputs, every output double is
 * **bit-identical** on jvm, android and js (py-fsrs itself is only matched within 1e-6). Therefore:
 * - one implementation in `commonMain`, no `expect`/`actual` on the scheduling path;
 * - every power and exponential goes through a [PortableMath] (fdlibm port); kotlin.math
 *   transcendentals are banned (list in [PortableMath]). py-fsrs's `a ** b` is `pow(a, b)` and its
 *   `math.e ** x` is `pow(PortableMath.E, x)`; py-fsrs uses no other transcendental;
 * - evaluate each formula with the operand order and parenthesisation of py-fsrs 6.3.2
 *   `scheduler.py` (e.g. `_FACTOR = pow(0.9, 1 / DECAY) − 1` with `DECAY = −w[20]`), no algebraic
 *   rewriting, no fused multiply-add;
 * - elapsed days use `Long` arithmetic, never a double division.
 *
 * **Rounding (normative).** py-fsrs rounds with Python 3 `round()`, which is round-half-to-even on the
 * double. Wherever py-fsrs calls `round` (the review interval `round(stability / FACTOR ·
 * (pow(desiredRetention, 1 / DECAY) − 1))`, and the fuzz bounds below) use
 * `roundHalfEven(x)`: `f = floor(x); d = x − f` (exact); `d > 0.5` → `f + 1`; `d < 0.5` → `f`;
 * `d == 0.5` → `f` if `f` is even, else `f + 1`. Examples: 2.5 → 2, 3.5 → 4, 2.4999999999999996 → 2.
 * The interval is then clamped `max(·, 1)` and `min(·, maximumIntervalDays)` in that order.
 *
 * **Fuzz** (when [SchedulerParams.fuzz]; replaces py-fsrs's `random.random()`, applied only where
 * py-fsrs fuzzes, i.e. to REVIEW intervals of `>= 2.5` days, after the maximum-interval clamp):
 * 1. `delta = 1.0 + Σ factor · max(min(ivl, end) − start, 0)` over py-fsrs `FUZZ_RANGES`
 *    `(2.5, 7, 0.15)`, `(7, 20, 0.10)`, `(20, ∞, 0.05)`;
 *    `lo = max(2, roundHalfEven(ivl − delta))`, `hi = min(roundHalfEven(ivl + delta), maximumIntervalDays)`,
 *    `lo = min(lo, hi)`.
 * 2. Candidates `d = lo..hi`; weight `w(d) = easyDays[isoWeekday(local date of reviewedAt + d days) − 1]`,
 *    local = UTC shifted by `utcOffsetMin` (null → 0). If every weight is 0, every weight is 1.
 * 3. `u` = SplitMix64 of `seed` = the last 16 hex digits of the review id (dashes removed) read as an
 *    unsigned 64-bit integer: `z = seed + 0x9E3779B97F4A7C15; z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9;
 *    z = (z xor (z ushr 27)) * 0x94D049BB133111EB; z = z xor (z ushr 31); u = (z ushr 11) * 2^-53`
 *    (wrapping 64-bit arithmetic).
 * 4. The interval is the first candidate `d` (ascending) whose cumulative weight exceeds `u · Σw`.
 *    With all weights 1 this is `lo + floor(u · (hi − lo + 1))`.
 * Easy days have no effect when fuzz is off. There is no collection-aware load balancing in the
 * cache (it would make the cache depend on other cards; see contracts/core/SUMMARY.md).
 */
public interface Scheduler {
    /** Algorithm identifier, `"6"` (compared with `fsrs_params.fsrs_version`). */
    public val fsrsVersion: String

    /** The 21 FSRS-6 default weights (py-fsrs 6.3.2 `DEFAULT_PARAMETERS`, listed in `fixtures/fsrs/vectors.json`). */
    public val defaultWeights: List<Double>

    /**
     * Scheduling parameters of a preset row. `weights` are used iff `row.weights != null`,
     * `row.fsrsVersion == fsrsVersion`, it has exactly 21 values, and every `w[i]` lies within the
     * py-fsrs 6.3.2 bounds `LOWER_BOUNDS_PARAMETERS[i] <= w[i] <= UPPER_BOUNDS_PARAMETERS[i]`
     * (the same bounds the `fsrs_params.published` schema enforces at push, C-1f; NaN fails); otherwise
     * [defaultWeights] — all 21 together, never per weight. Bounds by index:
     * `0–3: [0.001, 100]`, `4: [1, 10]`, `5: [0.001, 4]`, `6: [0.001, 4]`, `7: [0.001, 0.75]`,
     * `8: [0, 4.5]`, `9: [0, 0.8]`, `10: [0.001, 3.5]`, `11: [0.001, 5]`, `12: [0.001, 0.25]`,
     * `13: [0.001, 0.9]`, `14: [0, 4]`, `15: [0, 1]`, `16: [1, 6]`, `17: [0, 2]`, `18: [0, 2]`,
     * `19: [0, 0.8]`, `20: [0.1, 0.8]`. Within bounds, stability stays `>= 0.001` (py-fsrs
     * `STABILITY_MIN` clamp) and is never NaN.
     * `desiredRetention` outside `(0, 1)` or non-finite falls back to 0.9, `maximumIntervalDays < 1` to
     * 36500, and an `easyDays` that is not 7 finite values in `[0, 1]` to all ones. `weightsVersion = row.weightsVersion` in both cases (so a cache stamped with
     * it is current either way). The caller passes the **effective preset** (SYNC.md 9.1: the card's
     * preset row, or the Default preset if that row is tombstoned).
     */
    public fun paramsOf(row: PresetRow): SchedulerParams

    /**
     * Folds one rated review into [before]. [before] may be [CardSchedule.NEW]. A review dated before
     * `before.lastReviewAt` is treated as elapsed 0 (never negative). Does not validate that
     * [before] satisfies its invariants beyond what the algorithm needs.
     */
    public fun review(before: CardSchedule, review: RatedReview, params: SchedulerParams): CardSchedule

    /**
     * Folds one `manual` row (C-1b: manual rows never move `lastReviewAt` forward):
     * - [ManualAction.RESET] → [CardSchedule.NEW] (`lastReviewAt = null`, reps and lapses 0).
     * - [ManualAction.SET_DUE] on a NEW card → unchanged (a new card has no schedule to move; the
     *   CHECK `state = new ⇔ due_at IS NULL` forbids a due date).
     * - SET_DUE otherwise → state, step, stability, difficulty, reps, lapses and `lastReviewAt` kept;
     *   `dueAt = manualDueAt` (may be in the past = due now); in REVIEW
     *   `intervalDays = max(0, floor((manualDueAt − reviewedAt) / 86400000))`, otherwise unchanged.
     *   The next rated review measures elapsed time (and retrievability) from the last **rated**
     *   review, so a review right after a reschedule is not treated as same-day.
     *
     * [reviewedAt] is the manual row's `reviewed_at`; it only enters `intervalDays`.
     *
     * @throws IllegalArgumentException if [action] is SET_DUE and [manualDueAt] is null.
     */
    public fun manual(
        before: CardSchedule,
        action: ManualAction,
        manualDueAt: InstantMs?,
        reviewedAt: InstantMs,
    ): CardSchedule

    /**
     * The four answer outcomes at [now], in rating order AGAIN, HARD, GOOD, EASY: each is
     * `review(current, RatedReview(reviewId ?: <none>, now, rating, utcOffsetMin), params)`.
     * When [reviewId] is the id the client will use for the answer, the previews equal the real
     * results including fuzz; when null, fuzz is not applied (intervals unfuzzed).
     */
    public fun preview(
        current: CardSchedule,
        now: InstantMs,
        params: SchedulerParams,
        reviewId: Uuid?,
        utcOffsetMin: Int?,
    ): List<RatingOutcome>

    /**
     * Probability of recall at [at] (FSRS-6 forgetting curve with `w[20]` decay, through
     * [PortableMath.pow]; elapsed days as in py-fsrs `get_card_retrievability`: whole days since
     * `schedule.lastReviewAt` (the last rated review), `>= 0`). Null for a NEW card or one without
     * stability or `lastReviewAt`. In `0..1`.
     */
    public fun retrievability(schedule: CardSchedule, at: InstantMs, params: SchedulerParams): Double?
}
