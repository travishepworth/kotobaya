package kotobaya.queue

import kotobaya.contracts.InstantMs
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.LoopPresentation
import kotobaya.contracts.queue.LoopPresenter
import kotobaya.contracts.queue.LoopScreen
import kotobaya.contracts.queue.LoopState
import kotobaya.contracts.queue.PendingAnswer
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.UntilStopParts

/**
 * [LoopPresenter] (C-31, ADR-051): the screen rule, the projection of the pending answers and the
 * floor for a held card, exactly as the contract's KDoc states them. Pure; reads no clock.
 */
public object DefaultLoopPresenter : LoopPresenter {
    override fun present(
        state: LoopState,
        pending: List<PendingAnswer>,
        hasLocalCard: Boolean,
        localHeadKind: QueueItemKind?,
        dayEndsAt: InstantMs,
    ): LoopPresentation {
        if (!hasLocalCard && pending.isEmpty()) {
            // Rule 3: the state's own phase and parts.
            val p = state.untilStopParts
            return LoopPresentation(screenOf(state.phase), p, p.learning + p.review + p.new)
        }
        val s = state.untilStopParts
        var learning = maxOf(0, s.learning)
        var later = maxOf(0, s.later)
        var review = maxOf(0, s.review)
        var new = maxOf(0, s.new)
        for (a in pending) {
            when (a.kind) {
                QueueItemKind.NEW -> if (new > 0) new--
                QueueItemKind.REVIEW -> if (review > 0) review--
                QueueItemKind.LEARNING -> if (learning > 0) learning-- else if (later > 0) later--
            }
            if (returnsToday(a, dayEndsAt)) later++
        }
        if (hasLocalCard) {
            // The floor: a held card is always counted.
            when (localHeadKind ?: QueueItemKind.REVIEW) {
                QueueItemKind.NEW -> if (new == 0) new = 1
                QueueItemKind.REVIEW -> if (review == 0) review = 1
                QueueItemKind.LEARNING -> if (learning == 0) {
                    learning = 1
                    if (later > 0) later--
                }
            }
        }
        val parts = UntilStopParts(learning, later, review, new)
        val screen = if (hasLocalCard) LoopScreen.CARD else LoopScreen.SETTLING
        // ADR-054 (P-I3, P-I5): `later` is the hint, not part of the total.
        return LoopPresentation(screen, parts, learning + review + new)
    }

    private fun screenOf(phase: LoopPhase): LoopScreen = when (phase) {
        LoopPhase.REVIEWING -> LoopScreen.SETTLING
        LoopPhase.WAITING -> LoopScreen.WAITING
        LoopPhase.GOAL_REACHED -> LoopScreen.GOAL_REACHED
        LoopPhase.DONE_FOR_TODAY -> LoopScreen.DONE_FOR_TODAY
        LoopPhase.EMPTY -> LoopScreen.EMPTY
    }

    /** "Returns today" of the contract's table; `NEW` as a result and out-of-range ratings as it says. */
    private fun returnsToday(a: PendingAnswer, dayEndsAt: InstantMs): Boolean {
        val due = a.dueAt
        return when (a.resultingState) {
            CardState.LEARNING, CardState.RELEARNING -> due == null || due < dayEndsAt
            CardState.REVIEW -> false
            CardState.NEW, null -> {
                val rating = if (a.rating in 1..4) a.rating else 1
                when (a.kind) {
                    QueueItemKind.NEW -> true
                    QueueItemKind.REVIEW -> rating < 2
                    QueueItemKind.LEARNING -> rating < 3
                }
            }
        }
    }
}
