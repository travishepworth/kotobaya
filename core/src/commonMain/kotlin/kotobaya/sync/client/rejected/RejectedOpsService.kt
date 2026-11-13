package kotobaya.sync.client.rejected

import kotobaya.contracts.Uuid
import kotobaya.contracts.sync.HlcRules
import kotobaya.contracts.sync.RemintRules
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultRemintRules
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.remint.ClockActionBlock
import kotobaya.sync.client.remint.RemintOutcome
import kotobaya.sync.client.remint.RemintPreview
import kotobaya.sync.client.remint.Reminter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.OpIdSource
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.bookkeeping

/** One rejected row as the sync status lists it (SYNC.md 10.1: what, when, why). */
public data class RejectedEntry(
    /** The row: `op_type`, `entity_type` / `entity_id` (what), `created_at` (when), `last_error` (why). */
    public val row: PendingOpRow,
    /** The code (`last_error` prefix); the UI maps it to an i18n string, unknown codes to a generic one. */
    public val code: String?,
    /** The op id of the rejected root this row is grouped under (SYNC.md 10.2), or null for a root. */
    public val parentOpId: Uuid?,
    /** Retry is offered (not for the clock codes, SYNC.md 10.2). */
    public val retryable: Boolean,
)

/** Result of Retry or Discard. */
public sealed interface RejectedActionResult {
    /** Done for [count] rows. Discard set `rebuild_pending`: the next sync cycle rebuilds (SYNC.md 10.3). */
    public data class Done(
        /** Rows changed. */
        public val count: Int,
    ) : RejectedActionResult

    /** Nothing changed. */
    public data class Unavailable(
        /** Why: a clock precondition, or [NOT_REJECTED] / [NOT_RETRYABLE] for an unsuitable row. */
        public val reason: String,
        /** The clock block when the reason is a clock precondition. */
        public val block: ClockActionBlock? = null,
    ) : RejectedActionResult

    /** Reasons that are not clock blocks. */
    public companion object {
        /** A chosen op id is not a `rejected` row. */
        public const val NOT_REJECTED: String = "not_rejected"

        /** Retry was asked for a clock-rejected row. */
        public const val NOT_RETRYABLE: String = "not_retryable"
    }
}

/**
 * The user actions on rejected ops (SYNC.md 10.1–10.4), pure logic over [DeviceStore]: the list,
 * Retry, Discard and Re-mint (with its MUST-show discard list). Each action is one local
 * transaction; Discard and Re-mint set `rebuild_pending`, and the rebuild (SYNC.md 10.3,
 * [kotobaya.sync.client.rebuild.Rebuild]) runs in the next `DeviceSyncCycle.run`.
 *
 * No silent loss: a rejected row only leaves the outbox through [discard] (the user chose it), or
 * [remint] (re-minted, or listed in the [RemintPreview] the user confirmed); [retry] keeps it.
 * Never call these inside another [DeviceStore.transaction].
 */
public class RejectedOpsService<L : LearningTables>(
    private val store: DeviceStore<L>,
    minter: OpMinter<L>,
    clock: Clock,
    /** Source of the new review ids of Re-mint (UUIDv7). */
    reviewIds: OpIdSource,
    private val hlcRules: HlcRules = DefaultHlcRules,
    remintRules: RemintRules = DefaultRemintRules,
) {
    private val reminter = Reminter(store, minter, clock, reviewIds, hlcRules, remintRules)

    /** Every rejected row, ascending hlc, with its group parent (SYNC.md 10.1 list, 10.2 grouping). */
    public fun list(): List<RejectedEntry> = store.transaction { tx ->
        val rows = tx.outbox.inState(PendingState.REJECTED)
        val parents = ParentIndex(rows)
        rows.map { RejectedEntry(it, it.rejectionCode, parents.parentOf(it.opId), !RejectionCodes.isClock(it.rejectionCode)) }
    }

    /** Number of rejected rows (the sync status MUST show it, SYNC.md 10.1). */
    public fun count(): Int = store.transaction { it.outbox.count(PendingState.REJECTED) }

    /**
     * Retry (SYNC.md 10.2): the rows become `pending` (same `op_id`, same `hlc`) and are pushed in the
     * next cycle. Unavailable (nothing changes) if a row is not rejected or is clock-rejected.
     */
    public fun retry(opIds: Collection<Uuid>): RejectedActionResult = store.transaction { tx ->
        val rows = opIds.distinct().map { tx.outbox.get(it) }
        if (rows.any { it == null || it.state != PendingState.REJECTED }) {
            return@transaction RejectedActionResult.Unavailable(RejectedActionResult.NOT_REJECTED)
        }
        val chosen = rows.filterNotNull()
        if (chosen.any { RejectionCodes.isClock(it.rejectionCode) }) {
            return@transaction RejectedActionResult.Unavailable(RejectedActionResult.NOT_RETRYABLE)
        }
        for (r in chosen) tx.outbox.markPending(r.opId, r.attempts)
        RejectedActionResult.Done(chosen.size)
    }

    /**
     * Discard (SYNC.md 10.2): delete the rows and set `rebuild_pending` in one transaction. If a
     * discarded row was `clock_ahead`, the preconditions of 10.4 apply and the clock is repaired;
     * `remint_offset` is deleted when no clock-rejected row remains. Unavailable (nothing changes) if a
     * row is not rejected or a precondition fails.
     */
    public fun discard(opIds: Collection<Uuid>): RejectedActionResult = store.transaction { tx ->
        val rows = opIds.distinct().map { tx.outbox.get(it) }
        if (rows.isEmpty() || rows.any { it == null || it.state != PendingState.REJECTED }) {
            return@transaction RejectedActionResult.Unavailable(RejectedActionResult.NOT_REJECTED)
        }
        val chosen = rows.filterNotNull()
        val ahead = chosen.any { it.rejectionCode == RejectionCodes.CLOCK_AHEAD }
        if (ahead) {
            ClockRepair.preconditionsBlock(tx)?.let { return@transaction RejectedActionResult.Unavailable(it.name, it) }
        }
        for (r in chosen) tx.outbox.delete(r.opId)
        val bk = tx.bookkeeping
        bk.rebuildPending = true
        if (ahead) ClockRepair.repair(tx, hlcRules)
        if (tx.outbox.inState(PendingState.REJECTED).none { RejectionCodes.isClock(it.rejectionCode) }) bk.remintOffset = null
        RejectedActionResult.Done(chosen.size)
    }

    /** "Discard all" (SYNC.md 10.2 SHOULD): [discard] of every rejected row. */
    public fun discardAll(): RejectedActionResult =
        discard(store.transaction { tx -> tx.outbox.inState(PendingState.REJECTED).map { it.opId } })

    /**
     * The Re-mint preview (SYNC.md 10.4): availability and the full discard list. The UI MUST show
     * the count and let the user see the list before confirming; [remint] only accepts a preview
     * taken after the last change.
     */
    public fun previewRemint(): RemintPreview = reminter.preview()

    /**
     * Re-mint (SYNC.md 10.4 steps 1–7) in one transaction, for a current [preview]. On
     * [RemintOutcome.StalePreview] or [RemintOutcome.Unavailable] nothing changed.
     */
    public fun remint(preview: RemintPreview): RemintOutcome = reminter.remint(preview)
}
