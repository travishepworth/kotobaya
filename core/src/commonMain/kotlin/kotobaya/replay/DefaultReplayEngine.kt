package kotobaya.replay

import kotobaya.contracts.HlcString
import kotobaya.contracts.InstantMs
import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.RatedReview
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.fsrs.Scheduler
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayDecision
import kotobaya.contracts.replay.ReplayEngine
import kotobaya.contracts.replay.ReplayKey
import kotobaya.contracts.replay.ReplayReview
import kotobaya.contracts.replay.ReviewLogRules
import kotobaya.contracts.replay.UndoOutcome
import kotobaya.fsrs.FsrsScheduler
import kotobaya.sync.DefaultHlcRules

/**
 * [ReplayEngine] of SYNC.md 9 on top of [FsrsScheduler] (T203).
 *
 * Pure: no clock, no I/O, no state. The only arithmetic is inside [Scheduler.review] /
 * [Scheduler.manual], so the cross-platform bit identity of the scheduler carries over.
 *
 * Validation: a rated row (scheduled/learning) without a rating, a manual row without an action, or
 * two rows with the same id in one replay set violate the `review_log` CHECKs / primary key; they are
 * programming errors and raise [IllegalArgumentException].
 *
 * Known, accepted behaviour (FOLLOWUPS "T005 doc/contract minor"): the order is by `reviewed_at`, so a
 * device whose clock is ahead (within the 24 h tolerance) can put a causally later review of another
 * device *before* it. Every replica sorts the same way, so caches still converge.
 */
public object DefaultReplayEngine : ReplayEngine {
    /**
     * `"fsrs6-2"`: FSRS-6 (py-fsrs 6.3.2 port, [FsrsScheduler.fsrsVersion] `"6"`), replay rules
     * revision 2. Bump the suffix whenever any output of [replay] or [fold] could change for any input
     * (scheduler formulas, fuzz, order, manual-row handling).
     *
     * History: `"fsrs6-1"` (T203) → `"fsrs6-2"` (BUG-6: import graduation, SYNC.md 9.5). The bump makes
     * every replica fully replay every card once (SYNC.md 10.5), which re-derives the caches of cards
     * stuck in (re)learning by imported jpdb history.
     */
    public const val SCHEDULER_VERSION: String = "fsrs6-2"

    /** `review_log.import_source` of imported jpdb history: the only source the import-graduation rule reads (SYNC.md 9.5). */
    public const val JPDB_IMPORT_SOURCE: String = "jpdb"

    private val scheduler: Scheduler = FsrsScheduler

    override val schedulerVersion: String = SCHEDULER_VERSION

    /**
     * `reviewedAt` numerically, then `hlc` by [DefaultHlcRules.compare], then `id`. Canonical HLCs and
     * UUIDs are lowercase ASCII, for which UTF-16 code-unit order equals byte order.
     */
    override fun compare(a: ReplayKey, b: ReplayKey): Int {
        val t = a.reviewedAt.compareTo(b.reviewedAt)
        if (t != 0) return t
        val h = DefaultHlcRules.compare(a.hlc, b.hlc)
        if (h != 0) return h
        return a.id.compareTo(b.id)
    }

    /** Comparator over rows by [compare] of their keys. */
    public val rowOrder: Comparator<ReplayReview> = Comparator { a, b -> compare(a.key, b.key) }

    /** True iff [review] belongs in a replay set: schedule-feeding type and not undone (SYNC.md 9.1). */
    public fun inReplaySet(review: ReplayReview): Boolean =
        review.reviewType.feedsSchedule && review.undoneHlc == null

    /** The replay set of [reviews]: filtered by [inReplaySet], sorted by [compare]. Duplicate ids are rejected. */
    public fun replaySet(reviews: List<ReplayReview>): List<ReplayReview> {
        val set = reviews.filter(::inReplaySet).sortedWith(rowOrder)
        require(set.mapTo(HashSet()) { it.id }.size == set.size) { "duplicate review id in replay set" }
        return set
    }

    override fun replay(reviews: List<ReplayReview>, params: SchedulerParams): CardCache {
        var schedule = CardSchedule.NEW
        var lastId: String? = null
        for (r in replaySet(reviews)) {
            schedule = step(schedule, r, params)
            lastId = r.id
        }
        return CardCache(schedule, lastId, params.weightsVersion)
    }

    override fun fold(cache: CardCache, review: ReplayReview, params: SchedulerParams): CardCache {
        require(review.reviewType.feedsSchedule) { "fold of a ${review.reviewType.wire} row ${review.id}" }
        require(review.undoneHlc == null) { "fold of an undone row ${review.id}" }
        require(cache.weightsVersion == params.weightsVersion) {
            "fold into a stale cache (weights_version ${cache.weightsVersion} != ${params.weightsVersion})"
        }
        return CardCache(step(cache.schedule, review, params), review.id, params.weightsVersion)
    }

    override fun decide(
        cache: CardCache,
        lastFolded: ReplayKey?,
        review: ReplayReview,
        params: SchedulerParams,
    ): ReplayDecision = when {
        !inReplaySet(review) -> ReplayDecision.NONE
        // 1b: a refused cache (SYNC.md 9.4) is never folded into, before any lookup.
        cache.lastReviewId == Reserved.REPLAY_REFUSED_ID -> ReplayDecision.FULL_REPLAY
        cache.weightsVersion != params.weightsVersion -> ReplayDecision.FULL_REPLAY
        cache.lastReviewId == null -> ReplayDecision.FOLD
        lastFolded == null -> ReplayDecision.FULL_REPLAY
        review.id == cache.lastReviewId -> ReplayDecision.NONE
        compare(review.key, lastFolded) > 0 -> ReplayDecision.FOLD
        else -> ReplayDecision.FULL_REPLAY
    }

    /**
     * True iff [r] (rated, with [rating]) is an import graduation (SYNC.md 9.5): imported jpdb history,
     * a pass, on a (re)learning card whose last rated review is at least one day before it.
     */
    public fun importGraduates(before: CardSchedule, r: ReplayReview, rating: Rating): Boolean {
        if (r.importSource != JPDB_IMPORT_SOURCE || rating == Rating.AGAIN) return false
        if (before.state != CardState.LEARNING && before.state != CardState.RELEARNING) return false
        val last = before.lastReviewAt ?: return false
        return r.reviewedAt - last >= Reserved.DAY_MS
    }

    /**
     * One row: rated → [Scheduler.review] (clamps elapsed to >= 0), with no (re)learning steps for an
     * import graduation ([importGraduates]); manual → [Scheduler.manual].
     */
    private fun step(before: CardSchedule, r: ReplayReview, params: SchedulerParams): CardSchedule =
        when (r.reviewType) {
            ReviewType.SCHEDULED, ReviewType.LEARNING -> {
                val rating = requireNotNull(r.rating) { "rated row ${r.id} without rating" }
                val p = if (importGraduates(before, r, rating)) {
                    params.copy(learningStepsSeconds = emptyList(), relearningStepsSeconds = emptyList())
                } else {
                    params
                }
                scheduler.review(before, RatedReview(r.id, r.reviewedAt, rating, r.utcOffsetMin), p)
            }
            ReviewType.MANUAL -> {
                val action = requireNotNull(r.manualAction) { "manual row ${r.id} without manual_action" }
                scheduler.manual(before, action, r.manualDueAt, r.reviewedAt)
            }
            ReviewType.DRILL, ReviewType.CRAM -> throw IllegalArgumentException("${r.reviewType.wire} row ${r.id} never feeds a schedule")
        }
}

/** [ReviewLogRules] of SYNC.md 7.9 (device side; Postgres enforces the same by trigger). */
public object DefaultReviewLogRules : ReviewLogRules {
    override fun shouldInsert(existing: Boolean): Boolean = !existing

    override fun undo(
        storedUndoneHlc: HlcString?,
        storedUndoneAt: InstantMs?,
        opHlc: HlcString,
        opWallMs: InstantMs,
        rowFeedsSchedule: Boolean,
    ): UndoOutcome {
        val apply = storedUndoneHlc == null || DefaultHlcRules.compare(opHlc, storedUndoneHlc) < 0
        return if (apply) {
            UndoOutcome(
                apply = true,
                undoneHlc = opHlc,
                undoneAt = opWallMs,
                replayTarget = storedUndoneHlc == null && rowFeedsSchedule,
            )
        } else {
            UndoOutcome(apply = false, undoneHlc = storedUndoneHlc, undoneAt = storedUndoneAt, replayTarget = false)
        }
    }
}
