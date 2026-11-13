package kotobaya.struggle

import kotobaya.contracts.InstantMs
import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.hot.HotCard
import kotobaya.contracts.hot.HotConfig
import kotobaya.contracts.hot.HotReview
import kotobaya.contracts.hot.HotScore
import kotobaya.contracts.hot.HotScorer
import kotlin.math.max
import kotlin.math.pow

/** [HotScorer] as defined by its contract (SPEC "Leeches and hot words"). Pure. */
public object DefaultHotScorer : HotScorer {
    override fun scoreNote(reviews: List<HotReview>, cards: List<HotCard>, now: InstantMs, config: HotConfig): HotScore {
        val windowMs = config.windowDays * Reserved.DAY_MS
        val inWindow = reviews.filter { it.reviewedAt > now - windowMs && it.reviewedAt <= now }
        val failures = inWindow.filter { it.rating == Rating.AGAIN }

        fun weight(r: HotReview): Double = 0.5.pow((now - r.reviewedAt).toDouble() / Reserved.DAY_MS / config.halfLifeDays)

        val f = failures.sumOf(::weight)
        val rf = failures.filter { it.stateBefore == CardState.REVIEW || it.stateBefore == CardState.RELEARNING }.sumOf(::weight)
        if (f <= 0.0) return HotScore(0.0, false, 0.0)

        val d = ((cards.mapNotNull { it.difficulty }.maxOrNull() ?: 1.0) - 1.0) / 9.0
        val l = cards.sumOf { it.lapses }.toDouble() / max(1, cards.sumOf { it.reps })
        val score = config.failureWeight * f + config.relearningFailureWeight * rf +
            config.difficultyWeight * d + config.lapseRateWeight * l

        // Recovered: every card that failed in the window has since had >= recoveryStreak reviews, none failing.
        val recovered = failures.map { it.cardId }.distinct().all { card ->
            val lastFailure = failures.filter { it.cardId == card }.maxOf { it.reviewedAt }
            val after = inWindow.filter { it.cardId == card && it.reviewedAt > lastFailure }
            after.size >= config.recoveryStreak && after.all { it.rating != Rating.AGAIN }
        }
        return HotScore(score, score >= config.threshold && !recovered, f)
    }

    override fun scoreKanji(noteScores: List<HotScore>, config: HotConfig): HotScore {
        val contributing = noteScores.count { it.decayedFailures > 0.0 }
        val score = noteScores.sumOf { it.decayedFailures }
        return HotScore(score, score >= config.threshold && contributing >= 2, score)
    }
}

/** Leech rule: `lapses >= scheduler.leech_threshold`. */
public object Leeches {
    /** Default of `scheduler.leech_threshold`. */
    public const val DEFAULT_THRESHOLD: Int = 8

    /** True iff [lapses] reaches [threshold]. */
    public fun isLeech(lapses: Int, threshold: Int = DEFAULT_THRESHOLD): Boolean = lapses >= threshold
}
