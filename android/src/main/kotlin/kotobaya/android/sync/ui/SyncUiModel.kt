package kotobaya.android.sync.ui

import androidx.annotation.StringRes
import kotobaya.android.R
import kotobaya.android.sync.SyncStatusView
import kotobaya.contracts.Uuid
import kotobaya.sync.client.protocol.SyncPhase
import kotobaya.sync.client.rejected.RejectedEntry
import kotobaya.sync.client.rejected.RejectionCodes
import kotobaya.sync.client.remint.ClockActionBlock
import kotobaya.sync.client.remint.DiscardEntry
import kotobaya.sync.client.remint.DiscardReason
import kotobaya.sync.client.remint.RemintPreview

/** What the Home chip says. */
enum class ChipKind {
    NOT_PAIRED, SYNCING, CLOCK_WRONG, UPDATE_REQUIRED, AUTH_REQUIRED, NEEDS_ATTENTION, REBUILDING,
    OFFLINE, UNREACHABLE, SERVER_ERROR, CLIENT_ERROR, PENDING, SYNCED, NEVER_SYNCED,
}

/** The chip as data: which string, with which argument. Rendering formats [timeMs]. */
data class ChipModel(
    val kind: ChipKind,
    @StringRes val textRes: Int,
    val count: Int? = null,
    val timeMs: Long? = null,
    val httpStatus: Int? = null,
) {
    /** True when the user should act (the chip is drawn in the error colour). */
    val needsAction: Boolean
        get() = kind in ACTION_KINDS

    private companion object {
        val ACTION_KINDS = setOf(
            ChipKind.CLOCK_WRONG, ChipKind.UPDATE_REQUIRED, ChipKind.AUTH_REQUIRED, ChipKind.NEEDS_ATTENTION, ChipKind.CLIENT_ERROR,
        )
    }
}

/**
 * Maps the sync status to the chip. Precedence: not paired, syncing, clock, update required, auth, rejected
 * ("N need attention"), rebuilding, offline, unreachable / server error, client error, pending, synced.
 */
fun chipFor(view: SyncStatusView): ChipModel {
    val s = view.status
    val phase = s.phase
    return when {
        !view.paired -> ChipModel(ChipKind.NOT_PAIRED, R.string.sync_chip_not_paired)
        phase is SyncPhase.Syncing -> ChipModel(ChipKind.SYNCING, R.string.sync_chip_syncing)
        phase is SyncPhase.ClockWrong -> ChipModel(ChipKind.CLOCK_WRONG, R.string.sync_chip_clock)
        phase is SyncPhase.UpgradeRequired -> ChipModel(ChipKind.UPDATE_REQUIRED, R.string.sync_chip_update_required)
        phase is SyncPhase.AuthRequired -> ChipModel(ChipKind.AUTH_REQUIRED, R.string.sync_chip_auth)
        s.rejectedCount > 0 -> ChipModel(ChipKind.NEEDS_ATTENTION, R.string.sync_chip_attention, count = s.rejectedCount)
        phase is SyncPhase.Rebuilding -> ChipModel(ChipKind.REBUILDING, R.string.sync_chip_rebuilding)
        phase is SyncPhase.Offline -> ChipModel(ChipKind.OFFLINE, R.string.sync_chip_offline)
        phase is SyncPhase.ServerUnreachable -> when {
            view.unreachable -> ChipModel(ChipKind.UNREACHABLE, R.string.sync_chip_unreachable)
            phase.httpStatus != null ->
                ChipModel(ChipKind.SERVER_ERROR, R.string.sync_chip_server_error_code, httpStatus = phase.httpStatus)
            else -> ChipModel(ChipKind.SERVER_ERROR, R.string.sync_chip_server_error)
        }
        phase is SyncPhase.ClientError -> ChipModel(ChipKind.CLIENT_ERROR, R.string.sync_chip_client_error)
        s.pendingCount > 0 -> ChipModel(ChipKind.PENDING, R.string.sync_chip_pending, count = s.pendingCount)
        s.lastSyncAt != null -> ChipModel(ChipKind.SYNCED, R.string.sync_chip_synced_at, timeMs = s.lastSyncAt)
        else -> ChipModel(ChipKind.NEVER_SYNCED, R.string.sync_chip_never)
    }
}

/** The plain-English reason for a rejection code (SYNC.md 5.3); unknown codes get the generic string. */
@StringRes
fun rejectionReasonRes(code: String?): Int = when (code) {
    null -> R.string.rejected_code_none
    "unknown_reference" -> R.string.rejected_code_unknown_reference
    "unsupported_op" -> R.string.rejected_code_unsupported_op
    "schema_invalid" -> R.string.rejected_code_schema_invalid
    "op_too_large" -> R.string.rejected_code_op_too_large
    "device_mismatch" -> R.string.rejected_code_device_mismatch
    "insufficient_scope" -> R.string.rejected_code_insufficient_scope
    "server_only" -> R.string.rejected_code_server_only
    RejectionCodes.CLOCK_AHEAD -> R.string.rejected_code_clock_ahead
    RejectionCodes.CLOCK_BEHIND -> R.string.rejected_code_clock_behind
    "invalid_value" -> R.string.rejected_code_invalid_value
    "invalid_target" -> R.string.rejected_code_invalid_target
    "immutable" -> R.string.rejected_code_immutable
    else -> R.string.rejected_code_generic
}

@StringRes
fun discardReasonRes(r: DiscardReason): Int = when (r) {
    DiscardReason.CLOCK_EDIT -> R.string.remint_reason_edit
    DiscardReason.DEPENDS_ON_WINDOW_ROW -> R.string.remint_reason_depends
    DiscardReason.UNDO_OF_REMINTED_REVIEW -> R.string.remint_reason_undo
    DiscardReason.UNDONE_REVIEW -> R.string.remint_reason_undone
}

@StringRes
fun clockBlockRes(b: ClockActionBlock): Int = when (b) {
    ClockActionBlock.NOTHING_TO_REMINT -> R.string.remint_block_nothing
    ClockActionBlock.NOT_PAIRED -> R.string.remint_block_not_paired
    ClockActionBlock.REBUILD_PENDING -> R.string.remint_block_rebuild
    ClockActionBlock.PENDING_OPS -> R.string.remint_block_pending
    ClockActionBlock.CLOCK_NOT_CONFIRMED -> R.string.remint_block_clock_not_confirmed
    ClockActionBlock.CLOCK_GUARD -> R.string.remint_block_clock_guard
    ClockActionBlock.MINT_REFUSED -> R.string.remint_block_mint_refused
}

/** A row of a grouped list. */
sealed interface ListRow<out T> {
    /** Heading of a group: the rejected parent, or null for rows with no parent. */
    data class Header(val parentOpId: Uuid?) : ListRow<Nothing>

    /** One item. */
    data class Item<T>(val value: T) : ListRow<T>
}

/**
 * Rejected ops in display order: each root (no parent) followed by its children, hlc order inside each. A
 * child whose parent is not in the list is shown as a root, never hidden. Second = indented (a child).
 */
fun groupRejected(entries: List<RejectedEntry>): List<Pair<RejectedEntry, Boolean>> {
    val ids = entries.map { it.row.opId }.toSet()
    val children = entries.filter { it.parentOpId != null && it.parentOpId in ids }.groupBy { it.parentOpId }
    val roots = entries.filter { it.parentOpId == null || it.parentOpId !in ids }
    return roots.flatMap { r -> listOf(r to false) + children[r.row.opId].orEmpty().map { it to true } }
}

/** The discard list of a preview grouped under its window parent: a header per parent, then its rows. */
fun discardRows(preview: RemintView): List<ListRow<DiscardEntry>> = buildList {
    val groups = preview.groups
    groups[null]?.let { rows ->
        add(ListRow.Header(null))
        rows.forEach { add(ListRow.Item(it)) }
    }
    for ((parent, rows) in groups) {
        if (parent == null) continue
        add(ListRow.Header(parent))
        rows.forEach { add(ListRow.Item(it)) }
    }
}

/** The parts of a [RemintPreview] the screen shows (the preview itself is opaque and only passed back to confirm). */
data class RemintView(
    val blocked: ClockActionBlock?,
    val discards: List<DiscardEntry>,
    val remintedOps: Int,
    val remintedReviews: Int,
) {
    /** The count shown; always the size of the list that is rendered. */
    val count: Int get() = discards.size
    val groups: Map<Uuid?, List<DiscardEntry>> get() = discards.groupBy { it.parentOpId }

    companion object {
        fun of(p: RemintPreview) = RemintView(p.blocked, p.discards, p.remintedOps, p.remintedReviews)
    }
}

/** What the Re-mint screen decides from the preview and the clock flag; pure so it can be tested. */
sealed interface RemintGate {
    /** Re-mint is not offered; show [reasonRes]. No confirm button at all. */
    data class Hidden(@StringRes val reasonRes: Int) : RemintGate

    /** Offered; the confirm button is [confirmEnabled] only once the list has been shown. */
    data class Offered(val confirmEnabled: Boolean) : RemintGate
}

fun remintGate(preview: RemintView, clockBlocked: Boolean, listShown: Boolean): RemintGate {
    val blocked = preview.blocked
    return when {
        clockBlocked -> RemintGate.Hidden(R.string.remint_block_clock_blocked)
        blocked != null -> RemintGate.Hidden(clockBlockRes(blocked))
        else -> RemintGate.Offered(confirmEnabled = listShown || preview.count == 0)
    }
}
