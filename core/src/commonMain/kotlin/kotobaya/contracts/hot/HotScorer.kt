package kotobaya.contracts.hot

import kotobaya.contracts.InstantMs
import kotobaya.contracts.Uuid
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.Rating

/** A rated, non-undone, schedule-feeding review of one of a note's cards, with the state it was answered in. */
public data class HotReview(
    /** The card (resolved to the survivor if merged). */
    public val cardId: Uuid,
    /** `reviewed_at`. */
    public val reviewedAt: InstantMs,
    /** `rating`. */
    public val rating: Rating,
    /** Card state immediately before this review (from replay). */
    public val stateBefore: CardState,
)

/** A live card of the note, current cache. */
public data class HotCard(
    /** `card.id`. */
    public val cardId: Uuid,
    /** `card.difficulty`; null when NEW. */
    public val difficulty: Double?,
    /** `card.reps`. */
    public val reps: Int,
    /** `card.lapses`. */
    public val lapses: Int,
)

/** Tunable constants. [DEFAULT] is what every replica uses in v1. */
public data class HotConfig(
    /** Window of reviews considered, days (spec: 14). */
    public val windowDays: Int,
    /** Half-life of a failure's weight, days. */
    public val halfLifeDays: Double,
    /** Weight of a failure. */
    public val failureWeight: Double,
    /** Extra weight of a failure answered in REVIEW or RELEARNING (a relearning failure). */
    public val relearningFailureWeight: Double,
    /** Weight of normalised difficulty `(D − 1) / 9`. */
    public val difficultyWeight: Double,
    /** Weight of lapse rate `Σlapses / max(1, Σreps)`. */
    public val lapseRateWeight: Double,
    /** Score at or above which a note is hot. */
    public val threshold: Double,
    /** Successful answers (rating ≥ HARD) of the failed card after its last failure that mean "recovered". */
    public val recoveryStreak: Int,
) {
    /** Constants. */
    public companion object {
        /** v1 values: 14, 7.0, 2.0, 1.0, 1.5, 1.0, 2.5, 2. */
        public val DEFAULT: HotConfig = HotConfig(14, 7.0, 2.0, 1.0, 1.5, 1.0, 2.5, 2)
    }
}

/** Result for one note or kanji. */
public data class HotScore(
    /** The score (≥ 0). Sort key of `GET /words/hot`, descending, ties by id. */
    public val score: Double,
    /** True iff hot (shown on the hot-words screen, mnemonic shown on the card). */
    public val hot: Boolean,
    /** Σ of decayed failure weights in the window (diagnostic and kanji roll-up input). */
    public val decayedFailures: Double,
)

/**
 * Hot words and hot kanji (SPEC "Leeches and hot words"): recent struggle, not lifetime. Pure.
 *
 * For a note at [now], over [HotReview]s with `now − windowDays·DAY < reviewedAt <= now`:
 * - `w(r) = 0.5 ^ ((now − r.reviewedAt) / DAY / halfLifeDays)`;
 * - `F = Σ w` over failures (rating AGAIN); `RF = Σ w` over failures with `stateBefore` REVIEW or RELEARNING;
 * - `D = max difficulty` over [HotCard]s (1 if none), `d = (D − 1) / 9`;
 * - `L = Σ lapses / max(1, Σ reps)`;
 * - `score = failureWeight·F + relearningFailureWeight·RF + (F > 0 ? difficultyWeight·d + lapseRateWeight·L : 0)`.
 * `hot = F > 0 && score >= threshold && !recovered`, where **recovered** = every card that failed
 * in the window has, after its last failure, at least `recoveryStreak` reviews and all of them
 * have rating ≥ HARD. A recovered note keeps its score but is not hot ("words drop off once you recover").
 * Example: one Again today (w = 1) in review state, D = 7 → F = 1, RF = 1, d = 2/3, L = 1/5 for 1
 * lapse in 5 reps → score = 2 + 1 + 1 + 0.2 = 4.2 → hot.
 */
public interface HotScorer {
    /** Score of one note from its reviews and live cards. */
    public fun scoreNote(reviews: List<HotReview>, cards: List<HotCard>, now: InstantMs, config: HotConfig): HotScore

    /**
     * Hot kanji: failures rolled up across every live note containing the kanji (vocab notes whose
     * expression contains it, plus the kanji's own kanji note). `score = Σ decayedFailures` of
     * those notes (each note's [HotScore.decayedFailures] computed with the same [now] and
     * [config]); `hot = score >= threshold` and at least two distinct notes contributed a failure.
     */
    public fun scoreKanji(noteScores: List<HotScore>, config: HotConfig): HotScore
}
