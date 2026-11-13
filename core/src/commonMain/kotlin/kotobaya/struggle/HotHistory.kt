package kotobaya.struggle

import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.hot.HotReview
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayEngine
import kotobaya.contracts.replay.ReplayReview
import kotobaya.replay.DefaultReplayEngine

/** Builds [HotReview]s (with the state each was answered in) from a card's `review_log` rows. */
public object HotHistory {
    /**
     * Replays [reviews] (every row of one card and its merge losers; the engine filters and orders them) and returns
     * one [HotReview] per rated, schedule-feeding, non-undone row, with the card's state just before it.
     */
    public fun hotReviews(
        reviews: List<ReplayReview>,
        params: SchedulerParams,
        engine: ReplayEngine = DefaultReplayEngine,
    ): List<HotReview> {
        var cache = CardCache(CardSchedule.NEW, null, params.weightsVersion)
        val out = ArrayList<HotReview>()
        for (r in DefaultReplayEngine.replaySet(reviews)) {
            val rating = r.rating
            if (r.reviewType != ReviewType.MANUAL && rating != null) {
                out += HotReview(r.cardId, r.reviewedAt, rating, cache.schedule.state)
            }
            cache = engine.fold(cache, r, params)
        }
        return out
    }
}
