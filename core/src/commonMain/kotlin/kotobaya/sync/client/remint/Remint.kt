package kotobaya.sync.client.remint

import kotobaya.contracts.InstantMs
import kotobaya.contracts.Reserved
import kotobaya.contracts.Uuid
import kotobaya.contracts.sync.ClockGuardResult
import kotobaya.contracts.sync.HlcRules
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.contracts.sync.RemintItem
import kotobaya.contracts.sync.RemintRules
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultRemintRules
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.rejected.ClockRepair
import kotobaya.sync.client.rejected.OpFacts
import kotobaya.sync.client.rejected.ParentIndex
import kotobaya.sync.client.rejected.rejectionCode
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.OpIdSource
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.SyncBookkeeping
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.bookkeeping

/** Why a clock action (Re-mint, Discard of `clock_ahead` rows) is unavailable (SYNC.md 10.4 "Preconditions"). */
public enum class ClockActionBlock {
    /** No row is clock-rejected: there is nothing to re-mint. */
    NOTHING_TO_REMINT,

    /** The device is not paired. */
    NOT_PAIRED,

    /** A rebuild (SYNC.md 10.3) is in progress; minting is blocked until it finishes. */
    REBUILD_PENDING,

    /** Some `pending_op` row is still `pending`: sync first, so every op minted with the wrong clock is answered. */
    PENDING_OPS,

    /** No response yet, or the last one showed `|server_time − pt| > 5 min`: "fix your device clock, then sync". */
    CLOCK_NOT_CONFIRMED,

    /** Guard G1 or G2 (SYNC.md 3.4) holds after the clock repair: "fix your device clock, then sync". */
    CLOCK_GUARD,

    /** The minter refused a re-minted op (e.g. the 3.4 clock block is still set); nothing changed. */
    MINT_REFUSED,
}

/** Why a row is on the Re-mint discard list (SYNC.md 10.4 "Discard list (MUST)"). */
public enum class DiscardReason {
    /** In `C`, not re-minted: an edit of a row the server already has (or of a shared register) made with the wrong clock. */
    CLOCK_EDIT,

    /** In `D`: depends on a window row but writes a row other replicas can have. */
    DEPENDS_ON_WINDOW_ROW,

    /** In `U`: the undo of a review that is re-minted with a new id (or dropped). */
    UNDO_OF_REMINTED_REVIEW,

    /** In `R` but dropped in step 4: the user undid the review, so it is not re-logged. */
    UNDONE_REVIEW,
}

/** One row Re-mint deletes without minting it again. */
public data class DiscardEntry(
    /** The `pending_op` row (what: `op_type` / entity; when: `created_at`; why: `last_error`). */
    public val row: PendingOpRow,
    /** The rejection code (`last_error` prefix), or null for an undo that was not rejected itself. */
    public val code: String?,
    /** Why Re-mint drops it. */
    public val reason: DiscardReason,
    /** The op id of the rejected root it is grouped under (the window parent), or null for a root. */
    public val parentOpId: Uuid?,
)

/**
 * What Re-mint will do, shown before the user confirms (SYNC.md 10.4 "Discard list (MUST)"). Obtain
 * with `RejectedOpsService.previewRemint`; pass to `RejectedOpsService.remint`, which refuses a
 * preview taken before the last change of the outbox or of the Re-mint inputs.
 */
public class RemintPreview internal constructor(
    /** null = Re-mint is available; otherwise why not. */
    public val blocked: ClockActionBlock?,
    /** The rows Re-mint deletes without minting (`C`, `D`, `U` minus `E` and `R`, plus undone reviews), ascending hlc. */
    public val discards: List<DiscardEntry>,
    /** Ops of `E` that are re-minted (rows; a doubled removal counts once). */
    public val remintedOps: Int,
    /** Reviews of `R` that are re-minted (undone ones excluded). */
    public val remintedReviews: Int,
    internal val fingerprint: String,
) {
    /** True iff [RejectedOpsService.remint][kotobaya.sync.client.rejected.RejectedOpsService.remint] can run. */
    public val available: Boolean get() = blocked == null

    /** How many rows Re-mint deletes without minting. */
    public val discardCount: Int get() = discards.size

    /** [discards] grouped by [DiscardEntry.parentOpId] (null key = roots), each group in ascending hlc. */
    public val groups: Map<Uuid?, List<DiscardEntry>> get() = discards.groupBy { it.parentOpId }
}

/** Result of a Re-mint. */
public sealed interface RemintOutcome {
    /** Done, in one transaction; `rebuild_pending` is set, so the next sync cycle pushes and rebuilds. */
    public data class Reminted(
        /** The minted ops in mint order (creations and window ops first, then reviews). */
        public val minted: List<Op>,
        /** Op ids of every deleted row. */
        public val deleted: Set<Uuid>,
        /** Op ids of the deleted rows that were not re-minted: the preview's discard list, ascending hlc. */
        public val discarded: List<Uuid>,
        /** The shift `delta` of step 5. */
        public val delta: Long,
        /** Original review id → the new id it was re-minted with (step 6b). */
        public val reviewIds: Map<Uuid, Uuid>,
    ) : RemintOutcome

    /** The preview is stale (something changed since it was taken): take a new one and show it again. Nothing changed. */
    public data object StalePreview : RemintOutcome

    /** Not available; nothing changed. */
    public data class Unavailable(
        /** Why. */
        public val reason: ClockActionBlock,
        /** The minter's refusal when [reason] is [ClockActionBlock.MINT_REFUSED]. */
        public val refusal: MintRefusal? = null,
    ) : RemintOutcome
}

/**
 * Re-mint of SYNC.md 10.4, steps 1–7, in one local transaction. Used through
 * [kotobaya.sync.client.rejected.RejectedOpsService]; must not be called inside another
 * [DeviceStore.transaction] (a refusal rolls back by throwing out of the transaction).
 */
public class Reminter<L : LearningTables>(
    private val store: DeviceStore<L>,
    private val minter: OpMinter<L>,
    private val clock: Clock,
    /** Source of the new review ids (UUIDv7, step 6b). */
    private val reviewIds: OpIdSource,
    private val hlcRules: HlcRules = DefaultHlcRules,
    private val remintRules: RemintRules = DefaultRemintRules,
) {
    /** The preview: selection, discard list, availability. Changes nothing. */
    public fun preview(): RemintPreview = store.transaction { tx ->
        val plan = plan(tx)
        RemintPreview(plan.blocked, plan.discards, plan.selection.remint.size, plan.kept.size, plan.fingerprint)
    }

    /** Runs Re-mint for [preview] (which must be current). */
    public fun remint(preview: RemintPreview): RemintOutcome = try {
        store.transaction { tx -> run(tx, preview) }
    } catch (abort: Abort) {
        RemintOutcome.Unavailable(abort.reason, abort.refusal)
    }

    private class Abort(val reason: ClockActionBlock, val refusal: MintRefusal? = null) : RuntimeException(reason.name)

    private class Plan(
        val selection: RemintSelection,
        val kept: List<PendingOpRow>,
        val dropped: List<PendingOpRow>,
        val discards: List<DiscardEntry>,
        val blocked: ClockActionBlock?,
        val fingerprint: String,
    )

    private fun plan(tx: StoreTransaction<L>): Plan {
        val bk = tx.bookkeeping
        val rows = allRows(tx)
        val sel = RemintSelection.of(rows)
        val (kept, dropped) = sel.reviews.partition { !isUndone(tx.learning, it) }
        val parents = ParentIndex(rows.filter { it.state == PendingState.REJECTED || it.opId in sel.deleted })
        val inER = sel.remint.mapTo(HashSet()) { it.row.opId }.also { s -> sel.reviews.forEach { s += it.opId } }
        val discards = ArrayList<DiscardEntry>()
        val dIds = sel.dependents.mapTo(HashSet()) { it.opId }
        val uIds = sel.undos.mapTo(HashSet()) { it.opId }
        val seen = HashSet<Uuid>()
        for (row in (sel.clock + sel.dependents + sel.undos).sortedBy { it.hlc }) {
            if (row.opId in inER || !seen.add(row.opId)) continue
            val reason = when (row.opId) {
                in dIds -> DiscardReason.DEPENDS_ON_WINDOW_ROW
                in uIds -> DiscardReason.UNDO_OF_REMINTED_REVIEW
                else -> DiscardReason.CLOCK_EDIT
            }
            discards += DiscardEntry(row, row.rejectionCode.takeIf { row.state == PendingState.REJECTED }, reason, parents.parentOf(row.opId))
        }
        for (row in dropped) discards += DiscardEntry(row, row.rejectionCode, DiscardReason.UNDONE_REVIEW, parents.parentOf(row.opId))
        discards.sortBy { it.row.hlc }
        val blocked = blockedBy(tx, bk, rows, sel)
        return Plan(sel, kept, dropped, discards, blocked, fingerprint(tx, rows, sel))
    }

    private fun blockedBy(tx: StoreTransaction<L>, bk: SyncBookkeeping, rows: List<PendingOpRow>, sel: RemintSelection): ClockActionBlock? {
        if (sel.clock.isEmpty()) return ClockActionBlock.NOTHING_TO_REMINT
        if (bk.deviceId == null) return ClockActionBlock.NOT_PAIRED
        if (bk.rebuildPending) return ClockActionBlock.REBUILD_PENDING
        ClockRepair.preconditionsBlock(tx)?.let { return it }
        val hlcLast = if (sel.needsClockRepair) ClockRepair.repairedHlcLast(bk, rows.filter { it.opId !in sel.deleted }, hlcRules) else bk.hlcLast
        if (hlcRules.guard(clock.nowMs(), hlcLast) != ClockGuardResult.OK) return ClockActionBlock.CLOCK_GUARD
        return null
    }

    private fun run(tx: StoreTransaction<L>, preview: RemintPreview): RemintOutcome {
        val plan = plan(tx)
        if (plan.fingerprint != preview.fingerprint) return RemintOutcome.StalePreview
        plan.blocked?.let { return RemintOutcome.Unavailable(it) }
        val sel = plan.selection
        val bk = tx.bookkeeping
        val now = clock.nowMs()
        // step 4 inputs are read before anything is deleted (card rows are untouched anyway)
        val reviewOps = plan.kept.map { OpFacts.decode(it) ?: error("selected review ${it.opId} does not decode") }
        // step 2: discard C, D, E, R, U; clock repair
        for (id in sel.deleted) tx.outbox.delete(id)
        if (sel.needsClockRepair) ClockRepair.repair(tx, hlcRules)
        // step 3: G1 / G2 after the repair
        if (hlcRules.guard(now, bk.hlcLast) != ClockGuardResult.OK) throw Abort(ClockActionBlock.CLOCK_GUARD)
        // step 4: batch items
        val items = ArrayList<RemintItem>()
        for (e in sel.remint) items += RemintItem(hlcRules.wall(e.row.hlc), Reserved.CLOCK_FLOOR_MS)
        for (r in reviewOps) items += RemintItem(r.long("reviewed_at") ?: error("review ${r.opId} without reviewed_at"), lowerBound(tx, r, sel.window, now))
        // step 5
        val shift = remintRules.shift(items, bk.remintOffset ?: 0L, now)
        // step 6a
        val minted = ArrayList<Op>()
        val planned = sel.planned()
        for ((i, mints) in planned.withIndex()) {
            val at = shift.times[i]
            minted += mint(mints.map { MintRequest(it.type, it.payload) }, at)
        }
        // step 6b
        val newIds = LinkedHashMap<Uuid, Uuid>()
        for ((j, r) in reviewOps.withIndex()) {
            val t = shift.times[planned.size + j]
            val payload = remintedReview(r, t, shift.delta, now)
            minted += mint(listOf(MintRequest(OpType.REVIEW_LOGGED, payload)), null)
            val old = r.str("id")
            val new = (payload.members["id"] as? JsonString)?.value
            if (old != null && new != null) newIds[old] = new
        }
        // step 7
        bk.remintOffset = null
        bk.rebuildPending = true
        return RemintOutcome.Reminted(minted, sel.deleted, plan.discards.map { it.row.opId }, shift.delta, newIds)
    }

    /** Mints through the ordinary minter (section 4); a refusal aborts the whole transaction. */
    private fun mint(requests: List<MintRequest>, at: InstantMs?): List<Op> =
        when (val r = minter.mintAll(requests, at)) {
            is MintResult.Minted -> r.ops
            is MintResult.Refused -> throw Abort(ClockActionBlock.MINT_REFUSED, r.reason)
        }

    /** `L` of step 4: FLOOR for a card in `W`, else `min(card.created_at, now)` (FLOOR if the device has no such card). */
    private fun lowerBound(tx: StoreTransaction<L>, review: Op, window: Set<Uuid>, now: InstantMs): InstantMs {
        val cardId = review.str("card_id") ?: return Reserved.CLOCK_FLOOR_MS
        if (cardId in window) return Reserved.CLOCK_FLOOR_MS
        val created = (tx.learning.get(LearningTable.CARD, listOf(JsonString(cardId)))?.get("created_at") as? JsonNumber)?.value?.toLong()
            ?: return Reserved.CLOCK_FLOOR_MS
        return minOf(created, now)
    }

    /** Step 6b payload: a new id, `reviewed_at = t'`, `manual_due_at + delta` if non-null; all else unchanged. */
    private fun remintedReview(r: Op, t: InstantMs, delta: Long, now: InstantMs): JsonObject {
        val m = LinkedHashMap<String, JsonValue>(r.payload.members)
        m["id"] = JsonString(reviewIds.newOpId(now))
        m["reviewed_at"] = JsonNumber(t.toDouble())
        val due = m["manual_due_at"]
        if (due is JsonNumber) m["manual_due_at"] = JsonNumber((due.value.toLong() + delta).toDouble())
        return JsonObject(m)
    }

    private fun isUndone(t: LearningTables, row: PendingOpRow): Boolean {
        val op = OpFacts.decode(row) ?: return false
        val id = op.str("id") ?: return false
        val stored = t.get(LearningTable.REVIEW_LOG, listOf(JsonString(id))) ?: return false
        return (stored["undone_hlc"] ?: JsonNull) != JsonNull
    }

    /**
     * What a preview depends on, so that any later change makes it stale: every outbox row (id,
     * state, error, hlc, attempts), every `sync_state` key this client knows (a sync cycle in between
     * changes `last_sync_at`), and the undo state of the selected reviews.
     */
    private fun fingerprint(tx: StoreTransaction<L>, rows: List<PendingOpRow>, sel: RemintSelection): String = buildString {
        for (r in rows.sortedBy { it.opId }) {
            append(r.opId).append('|').append(r.state.wire).append('|').append(r.hlc).append('|')
                .append(r.attempts).append('|').append(r.lastError).append('\n')
        }
        for (key in FINGERPRINT_KEYS) append(key).append('=').append(tx.syncState.get(key)).append('\n')
        for (r in sel.reviews) append(r.opId).append(if (isUndone(tx.learning, r)) ":undone" else ":live").append('\n')
    }

    private fun allRows(tx: StoreTransaction<L>): List<PendingOpRow> =
        PendingState.entries.flatMap { tx.outbox.inState(it) }.sortedBy { it.hlc }

    private companion object {
        val FINGERPRINT_KEYS = listOf(
            SyncStateKeys.DEVICE_ID, SyncStateKeys.PULL_SEQ, SyncStateKeys.HLC_LAST, SyncStateKeys.LAST_SYNC_AT,
            SyncStateKeys.REBUILD_PENDING, SyncStateKeys.LOG_EPOCH, SyncStateKeys.DURABLE_SEQ, SyncStateKeys.HLC_ACKED,
            SyncStateKeys.REMINT_OFFSET, SyncStateKeys.SERVER_SKEW_MS, SyncStateKeys.CLOCK_BLOCKED,
            SyncStateKeys.UPGRADE_REQUIRED, SyncStateKeys.LOG_RESET_EPOCH,
        )
    }
}
