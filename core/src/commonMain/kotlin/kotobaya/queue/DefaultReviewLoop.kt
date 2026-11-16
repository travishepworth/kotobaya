package kotobaya.queue

import kotobaya.contracts.queue.LegacyCounts
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.LoopResult
import kotobaya.contracts.queue.LoopState
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewLoop
import kotobaya.contracts.queue.ReviewQueue

/**
 * [ReviewLoop] (ADR-037) over [DefaultQueueBuilder]: exactly two builds per evaluation, the normal
 * build N and the keep-going build K, both from the same input with learn-ahead forced to 0. Every
 * count comes from N, K and `input.today`, so only `phase` and `keepGoing` depend on the caller's
 * keep-going flag. `LegacyCounts.new` comes from N's own batch-unbounded draw (the contract allows
 * this instead of a third build N∞).
 */
public object DefaultReviewLoop : ReviewLoop {
    override fun evaluate(input: QueueInput): LoopResult {
        val base = input.limits.copy(learnAheadMs = 0)
        val n = DefaultQueueBuilder.buildDetailed(input.copy(limits = base.copy(keepGoing = false)))
        val k = DefaultQueueBuilder.buildDetailed(input.copy(limits = base.copy(keepGoing = true)))
        val kNonEmpty = k.queue.nonEmpty()
        val normal = n.queue.copy(moreAvailable = !n.queue.nonEmpty() && kNonEmpty)
        val keepGoing = input.limits.keepGoing
        val served = if (keepGoing) k.queue else normal

        val readyLearning = normal.items.count { it.kind == QueueItemKind.LEARNING }
        val readyReviews = normal.items.count { it.kind == QueueItemKind.REVIEW }
        val backlog = k.queue.items.count { it.kind == QueueItemKind.REVIEW }
        val phase = when {
            served.nonEmpty() -> LoopPhase.REVIEWING
            !keepGoing && kNonEmpty ->
                if (input.limits.reviewsUnlimited) LoopPhase.GOAL_REACHED else LoopPhase.DONE_FOR_TODAY
            n.laterToday > 0 -> LoopPhase.WAITING
            else -> LoopPhase.EMPTY
        }
        val state = LoopState(
            phase = phase,
            keepGoing = keepGoing,
            readyLearning = readyLearning,
            readyReviews = readyReviews,
            laterToday = n.laterToday,
            nextDueAt = n.nextDueAt,
            backlog = backlog,
            backlogBeyondLimit = backlog - readyReviews,
            reviewsToday = n.reviewsToday,
            reviewsLimit = if (input.limits.reviewsUnlimited) null else maxOf(0, input.limits.reviewsPerDay),
            newToday = n.newToday,
            newGoal = maxOf(0, input.limits.newPerDay),
            newAvailable = k.queue.items.any { it.kind == QueueItemKind.NEW } || k.queue.toMaterialize.isNotEmpty(),
            legacy = LegacyCounts(
                new = n.unboundedNewCount,
                learning = readyLearning + n.laterToday,
                review = readyReviews,
                moreAvailable = normal.moreAvailable,
            ),
        )
        return LoopResult(served, state)
    }

    private fun ReviewQueue.nonEmpty(): Boolean = items.isNotEmpty() || toMaterialize.isNotEmpty()
}
