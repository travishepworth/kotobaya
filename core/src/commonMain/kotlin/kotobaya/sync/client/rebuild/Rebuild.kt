package kotobaya.sync.client.rebuild

import kotobaya.sync.client.apply.BuiltIns
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.SyncEngine
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.bookkeeping
import kotobaya.sync.client.store.toOp

/**
 * The rebuild of SYNC.md 10.3: the local effects of discarded ops (Discard, Re-mint) and of a lost
 * log (log reset, 6.2) are rolled back by re-deriving the learning tables from the server's log plus
 * the ops the device still holds.
 *
 * Every step is its own transaction and `rebuild_pending` is deleted only by the last one, so a
 * process killed anywhere starts again from step 0 at the next [run] (restartable). Minting is
 * refused while `rebuild_pending` is set (`OpMinter`). `core_scheduler_version` and the other
 * `sync_state` keys are kept: every cache step 2 and 3 create is computed by the running core.
 */
public class Rebuild<L : LearningTables>(
    private val store: DeviceStore<L>,
    private val engine: SyncEngine<L>,
    private val applier: OpApplier<L>,
    /** Called with each kept outbox row that step 3 cannot decode (skipped; minting validates, so only a corrupt row). */
    private val onUndecodable: (PendingOpRow, String) -> Unit = { _, _ -> },
) {
    /**
     * Runs steps 0–3. Returns:
     * - [CycleResult.Completed] (`pullAdvanced` = the pull from 0 got any op) or
     *   [CycleResult.UpgradeRequired] (step 2 stopped at an unsupported op; step 3 still ran):
     *   the rebuild is done and `rebuild_pending` is deleted;
     * - [CycleResult.LogChanged]: a response showed another log change; the caller runs the log
     *   reset (`SyncEngine.resetLog`) and calls [run] again;
     * - [CycleResult.Failed]: the server is unreachable or answered unusably; `rebuild_pending`
     *   stays set and the next cycle starts again from step 0.
     *
     * Does nothing (returns `Completed(0, 0, 0)`) when `rebuild_pending` is not set.
     */
    public suspend fun run(): CycleResult {
        if (!store.transaction { it.bookkeeping.rebuildPending }) return CycleResult.Completed(0, 0, 0)
        // step 0: push until no row is pending (the device's own ops then come back in step 2)
        val pushed = engine.pushAll()
        if (pushed !is CycleResult.Completed) return pushed
        // step 1: wipe, re-seed, pull from 0
        store.transaction { tx -> wipe(tx) }
        // step 2: pull from 0 until has_more is false or the pull stops in upgrade required
        val pulled = engine.pullAll()
        if (pulled is CycleResult.LogChanged || pulled is CycleResult.Failed) return pulled
        // step 3: re-apply what the log does not hold yet, then clear the flag
        store.transaction { tx -> reapply(tx) }
        return when (pulled) {
            is CycleResult.Completed -> CycleResult.Completed(pushed.pushedOps, pulled.pulledOps, 0, pulled.pullAdvanced)
            else -> pulled
        }
    }

    /** Step 1 (one transaction): every learning and media row deleted, built-ins re-seeded, `pull_seq = 0`. */
    private fun wipe(tx: StoreTransaction<L>) {
        // the only DELETE of review_log a device ever issues (SYNC.md 7.9 rule 5); tx.learning is the
        // raw table set, the applier's append-only guard is not involved
        for (table in LearningTable.entries) tx.learning.clear(table)
        BuiltIns.seed(tx.learning)
        tx.bookkeeping.pullSeq = 0
    }

    /**
     * Step 3 (one transaction): re-apply, ascending hlc, every `pending` or `rejected` row, and every
     * `acked` row with `acked_seq > pull_seq` (only possible after an *upgrade required* stop); then
     * delete `rebuild_pending`. A row whose target is missing is a no-op (SYNC.md 7.7).
     */
    private fun reapply(tx: StoreTransaction<L>) {
        val pullSeq = tx.bookkeeping.pullSeq
        val rows = tx.outbox.inState(PendingState.PENDING) +
            tx.outbox.inState(PendingState.REJECTED) +
            tx.outbox.inState(PendingState.ACKED).filter { (it.ackedSeq ?: 0L) > pullSeq }
        for (row in rows.sortedBy { it.hlc }) {
            val op = try {
                row.toOp()
            } catch (e: IllegalStateException) {
                onUndecodable(row, e.message ?: e.toString())
                continue
            }
            applier.apply(tx, op, ApplyOrigin.LOCAL)
        }
        tx.bookkeeping.rebuildPending = false
    }
}
