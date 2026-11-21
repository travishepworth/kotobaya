package kotobaya.queue

import kotobaya.contracts.queue.LegacyCounts
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.LoopResult
import kotobaya.contracts.queue.LoopState
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewLoop
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.queue.UntilStopParts

/**
 * [ReviewLoop] (ADR-037, phase order ADR-049, C-31) over [DefaultQueueBuilder]: the normal build N and
 * the unbounded keep-going build K∞, plus K_T when keeping going with a `keepGoingNewTarget`, all from
 * the same input with learn-ahead forced to 0. Every count comes from N, K∞ and `input.today`, so only
 * `phase`, `keepGoing`, `keepGoingAvailable`, `untilStop` and `untilStopParts` depend on the caller's
 * keep-going flag and target. `LegacyCounts.new` comes from N's own batch-unbounded draw (the contract
 * allows this instead of a third build N∞).
 */
public object DefaultReviewLoop : ReviewLoop {
    override fun evaluate(input: QueueInput): LoopResult {
        val base = input.limits.copy(learnAheadMs = 0)
        val n = DefaultQueueBuilder.buildDetailed(input.copy(limits = base.copy(keepGoing = false, keepGoingNewTarget = null)))
        val k = DefaultQueueBuilder.buildDetailed(input.copy(limits = base.copy(keepGoing = true, keepGoingNewTarget = null)))
        val kNonEmpty = k.queue.nonEmpty()
        val normal = n.queue.copy(moreAvailable = !n.queue.nonEmpty() && kNonEmpty)
        val keepGoing = input.limits.keepGoing
        // C-31 rule 6 "Target": K_T, the build served while keeping going with a target (K∞ without one).
        val kt = if (keepGoing && input.limits.keepGoingNewTarget != null) {
            DefaultQueueBuilder.buildDetailed(input.copy(limits = base.copy(keepGoing = true)))
        } else {
            k
        }
        val served = if (keepGoing) kt.queue else normal

        val readyLearning = normal.items.count { it.kind == QueueItemKind.LEARNING }
        val readyReviews = normal.items.count { it.kind == QueueItemKind.REVIEW }
        val backlog = k.queue.items.count { it.kind == QueueItemKind.REVIEW }
        // ADR-049: wait for today's learning cards before any stop screen, in both modes. C-31: the stop
        // screens also with keep going once K_T is empty (target reached) and K∞ is not.
        val phase = when {
            served.nonEmpty() -> LoopPhase.REVIEWING
            n.laterToday > 0 -> LoopPhase.WAITING
            kNonEmpty ->
                if (input.limits.reviewsUnlimited) LoopPhase.GOAL_REACHED else LoopPhase.DONE_FOR_TODAY
            else -> LoopPhase.EMPTY
        }
        // ADR-038 / ADR-049: answers left until the stop screen, split as the header shows them; follows the mode.
        val parts = UntilStopParts(
            learning = readyLearning,
            later = n.laterToday,
            review = if (keepGoing) backlog else readyReviews,
            new = if (keepGoing) kt.availableToday else n.availableToday,
        )
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
            newAvailable = k.queue.items.any { it.kind == QueueItemKind.NEW } || k.queue.toMaterialize.isNotEmpty() ||
                k.queue.kanjiToMaterialize.isNotEmpty(),
            legacy = LegacyCounts(
                new = n.unboundedNewCount,
                learning = readyLearning + n.laterToday,
                review = readyReviews,
                moreAvailable = normal.moreAvailable,
            ),
            untilStop = parts.learning + parts.later + parts.review + parts.new,
            // C-31: pressing Keep going would serve a card now (equals `!keepGoing && moreAvailable` without a target).
            keepGoingAvailable = !served.nonEmpty() && kNonEmpty,
            untilStopParts = parts,
        )
        return LoopResult(served, state)
    }

    private fun ReviewQueue.nonEmpty(): Boolean = items.isNotEmpty() || toMaterialize.isNotEmpty() || kanjiToMaterialize.isNotEmpty()
}
