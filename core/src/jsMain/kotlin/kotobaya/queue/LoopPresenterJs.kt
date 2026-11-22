@file:OptIn(ExperimentalJsExport::class)

package kotobaya.queue

import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.LegacyCounts
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.LoopState
import kotobaya.contracts.queue.PendingAnswer
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.UntilStopParts
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.floor

/*
 * C-31 (ADR-051): `LoopPresenter.present` for `@kotobaya/core` (LOOP-31-CORE-B), in the style of
 * `CoreJs.previewIntervals`: JS-friendly DTO classes (the web's core adapter builds them from plain
 * TypeScript objects), wire strings as in the API and instants as epoch ms numbers.
 * - `loop.phase`: `reviewing`, `waiting`, `goal_reached`, `done_for_today`, `empty`.
 * - `QueueItem.kind`: `learning`, `review`, `new`.
 * - `card.state`: `learning`, `review`, `relearning` (`new` = unknown, as the contract says).
 * Unknown wire values and instants that are not non-negative integers throw a JS Error.
 */

/** `UntilStopParts` (the API's `loop.until_stop_parts`): in and out. */
@JsExport
public class JsUntilStopParts(
    /** Learning ready now. */
    public val learning: Int,
    /** Learning later today. */
    public val later: Int,
    /** Reviews. */
    public val review: Int,
    /** New cards. */
    public val new: Int,
)

/** The part of the applied `loop` the presenter reads. */
@JsExport
public class JsLoopInput(
    /** `loop.phase` wire value. */
    public val phase: String,
    /** `loop.until_stop_parts`. */
    public val untilStopParts: JsUntilStopParts,
)

/** `PendingAnswer`. */
@JsExport
public class JsPendingAnswer(
    /** `QueueItem.kind` wire value of the answered card. */
    public val kind: String,
    /** 1 … 4 (others are treated as 1). */
    public val rating: Int,
    /** `card.state` wire value after the answer, or null when unknown. */
    public val resultingState: String?,
    /** `card.due_at` after the answer (epoch ms), or null when unknown. */
    public val dueAtMs: Double?,
)

/** `LoopPresentation`. */
@JsExport
public class JsLoopPresentation(
    /** `LoopScreen.wire`: `card`, `settling`, `waiting`, `goal_reached`, `done_for_today` or `empty`. */
    public val screen: String,
    /** The four header numbers, already projected. */
    public val parts: JsUntilStopParts,
    /** Sum of the four parts (progress bar remainder). */
    public val untilStop: Int,
)

/**
 * `DefaultLoopPresenter.present(state, pending, hasLocalCard, localHeadKind, dayEndsAt)`.
 * [localHeadKind] is the held card's `QueueItem.kind` wire value (null iff nothing is held);
 * [dayEndsAtMs] is the response's `day_ends_at` in epoch ms.
 */
@JsExport
public fun presentLoop(
    state: JsLoopInput,
    pending: Array<JsPendingAnswer>,
    hasLocalCard: Boolean,
    localHeadKind: String?,
    dayEndsAtMs: Double,
): JsLoopPresentation {
    val phase = LoopPhase.entries.firstOrNull { it.wire == state.phase }
        ?: throw IllegalArgumentException("unknown loop phase: ${state.phase}")
    val sp = state.untilStopParts
    val parts = UntilStopParts(sp.learning, sp.later, sp.review, sp.new)
    // The presenter reads only `phase` and `untilStopParts`; the other counts are placeholders.
    val loop = LoopState(
        phase = phase, keepGoing = false, readyLearning = parts.learning, readyReviews = 0, laterToday = parts.later,
        nextDueAt = null, backlog = 0, backlogBeyondLimit = 0, reviewsToday = 0, reviewsLimit = null, newToday = 0,
        newGoal = 0, newAvailable = false, legacy = LegacyCounts(0, 0, 0, false),
        untilStop = parts.learning + parts.review + parts.new, untilStopParts = parts,
    )
    val answers = pending.map {
        PendingAnswer(
            kind = kindOf(it.kind),
            rating = it.rating,
            resultingState = it.resultingState?.let { s ->
                CardState.entries.firstOrNull { c -> c.wire == s } ?: throw IllegalArgumentException("unknown card state: $s")
            },
            dueAt = it.dueAtMs?.let(::instant),
        )
    }
    val p = DefaultLoopPresenter.present(loop, answers, hasLocalCard, localHeadKind?.let(::kindOf), instant(dayEndsAtMs))
    return JsLoopPresentation(
        p.screen.wire,
        JsUntilStopParts(p.parts.learning, p.parts.later, p.parts.review, p.parts.new),
        p.untilStop,
    )
}

private fun kindOf(wire: String): QueueItemKind =
    QueueItemKind.entries.firstOrNull { it.name.lowercase() == wire } ?: throw IllegalArgumentException("unknown queue item kind: $wire")

private fun instant(ms: Double): Long {
    require(ms >= 0.0 && ms <= 9007199254740991.0 && floor(ms) == ms) { "not an instant in ms: $ms" }
    return ms.toLong()
}
