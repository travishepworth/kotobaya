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
    /** `card.last_review_at`: `reviewed_at` of the last row folded (rated or manual); null if none. */
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
    /** `review_log.utc_offset_min`; null = unknown, treated as 0 (UTC) for easy days. */
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
 * `dueAt` exactly and `stability`, `difficulty` within 1e-6.
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
     * `row.fsrsVersion == fsrsVersion` and it has exactly 21 finite values; otherwise
     * [defaultWeights]. `weightsVersion = row.weightsVersion` in both cases (so a cache stamped with
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
     * Folds one `manual` row:
     * - [ManualAction.RESET] → [CardSchedule.NEW] with `lastReviewAt = reviewedAt`.
     * - [ManualAction.SET_DUE] on a NEW card → unchanged except `lastReviewAt = reviewedAt`
     *   (a new card has no schedule to move; the CHECK `state = new ⇔ due_at IS NULL` forbids a due date).
     * - SET_DUE otherwise → state, step, stability, difficulty, reps, lapses kept; `dueAt = manualDueAt`
     *   (may be in the past = due now); `lastReviewAt = reviewedAt`; in REVIEW
     *   `intervalDays = max(0, floor((manualDueAt − reviewedAt) / 86400000))`, otherwise unchanged.
     *   The next rated review measures elapsed time from this `lastReviewAt`.
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
     * Probability of recall at [at] (FSRS-6 forgetting curve with `w[20]` decay; elapsed days as in
     * py-fsrs `get_card_retrievability`: whole days, `>= 0`). Null for a NEW card or one without
     * stability. In `0..1`.
     */
    public fun retrievability(schedule: CardSchedule, at: InstantMs, params: SchedulerParams): Double?
}
