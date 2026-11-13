package kotobaya.android.sync

import kotobaya.contracts.Uuid
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.bookkeeping

/**
 * The database side of the rejected-op actions (SYNC.md 10.1, 10.2) for T303d: reads and single
 * statements only. Which action is allowed, the order of steps, Re-mint and when to run the rebuild
 * are T303d's. Every `*In` method runs inside the caller's transaction (so Discard can delete rows
 * and set `rebuild_pending` atomically); the others open their own.
 */
class RejectedOpsStore(private val store: SqlDelightDeviceStore) {
    /** Rejected rows, ascending hlc (SYNC.md 10.1: they MUST be shown). */
    fun list(): List<PendingOpRow> = store.read { it.outbox.inState(PendingState.REJECTED) }

    /** Number of rejected rows. */
    fun count(): Int = store.read { it.outbox.count(PendingState.REJECTED) }

    /** One row by id, any state. */
    fun get(opId: Uuid): PendingOpRow? = store.read { it.outbox.get(opId) }

    /** Retry (10.2): the row becomes `pending` with [attempts]; `last_error` is kept for display. */
    fun markPendingIn(tx: StoreTransaction<*>, opId: Uuid, attempts: Int = 0) = tx.outbox.markPending(opId, attempts)

    /** Discard (10.2), the row part: deletes it. */
    fun deleteIn(tx: StoreTransaction<*>, opId: Uuid) = tx.outbox.delete(opId)

    /** Discard / Re-mint (10.2, 10.3): sets `rebuild_pending = "1"`. */
    fun setRebuildPendingIn(tx: StoreTransaction<*>) {
        tx.bookkeeping.rebuildPending = true
    }

    /** `remint_offset` (10.5), or null. */
    fun remintOffsetIn(tx: StoreTransaction<*>): Long? = tx.bookkeeping.remintOffset

    /** Deletes `remint_offset` (Re-mint, or Discard leaving no clock-rejected row; 10.5). */
    fun clearRemintOffsetIn(tx: StoreTransaction<*>) {
        tx.bookkeeping.remintOffset = null
    }

    /** Runs [block] in one store transaction (commit notifies the status). */
    fun <R> transaction(block: (StoreTransaction<SqlLearningTables>) -> R): R = store.transaction(block)
}
