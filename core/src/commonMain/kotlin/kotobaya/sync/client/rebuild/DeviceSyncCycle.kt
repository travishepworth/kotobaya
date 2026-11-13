package kotobaya.sync.client.rebuild

import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.StartupReplay
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.SyncEngine
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.bookkeeping

/**
 * One device sync cycle with everything T303a leaves to the apply side: the startup replay of
 * SYNC.md 10.5 before anything else, the log reset of 6.2 when a response shows a changed log, the
 * rebuild of 10.3 while `rebuild_pending` is set, and the `unknown_reference` auto-retry of 6.2.
 *
 * Restartable at every point: each step commits on its own and the state it leaves (`pending`
 * rows, `rebuild_pending`, `pull_seq`) makes the next [run] continue correctly.
 */
public class DeviceSyncCycle<L : LearningTables>(
    private val store: DeviceStore<L>,
    private val engine: SyncEngine<L>,
    private val rebuild: Rebuild<L>,
    private val startup: StartupReplay = StartupReplay(),
) {
    /**
     * Runs one cycle: [StartupReplay.run]; `SyncEngine.cycle`; on [CycleResult.LogChanged] the log
     * reset (`SyncEngine.resetLog`) and again; on [CycleResult.RebuildPending] the [Rebuild] (again
     * after a log reset it reports); after a pull that advanced `pull_seq` (the cycle's or the
     * rebuild's), the auto-retry. At most [MAX_LOG_RESETS] resets per call; then the last
     * [CycleResult.LogChanged] is returned and the next call continues.
     */
    public suspend fun run(): CycleResult {
        startup.run(store)
        var resets = 0
        var result = engine.cycle()
        while (true) {
            when (result) {
                is CycleResult.LogChanged -> {
                    if (resets++ >= MAX_LOG_RESETS) return result
                    engine.resetLog(result)
                    result = engine.cycle()
                }
                is CycleResult.RebuildPending -> {
                    result = rebuild.run()
                    if (result is CycleResult.LogChanged) continue
                    if (result is CycleResult.Completed) autoRetry(result.pullAdvanced)
                    return result
                }
                is CycleResult.Completed -> {
                    autoRetry(result.pullAdvanced)
                    return result
                }
                else -> return result
            }
        }
    }

    /**
     * SYNC.md 6.2 auto-retry: if a log reset happened in the current epoch and [pullAdvanced], every
     * `rejected` row with code `unknown_reference` that has been auto-retried fewer than
     * [MAX_AUTO_RETRIES] times since the reset becomes `pending` with `attempts + 1`. Returns how many.
     */
    public fun autoRetry(pullAdvanced: Boolean): Int = store.transaction { tx -> autoRetry(tx, pullAdvanced) }

    private fun autoRetry(tx: StoreTransaction<L>, pullAdvanced: Boolean): Int {
        if (!pullAdvanced) return 0
        val bk = tx.bookkeeping
        val epoch = bk.logEpoch ?: return 0
        if (bk.logResetEpoch != epoch) return 0
        var n = 0
        for (row in tx.outbox.inState(PendingState.REJECTED)) {
            if (row.lastError?.startsWith("$UNKNOWN_REFERENCE:") != true) continue
            if (row.attempts >= MAX_AUTO_RETRIES) continue
            tx.outbox.markPending(row.opId, row.attempts + 1)
            n++
        }
        return n
    }

    /** Limits. */
    public companion object {
        /** SYNC.md 6.2: a row is auto-retried at most this many times after a log reset. */
        public const val MAX_AUTO_RETRIES: Int = 10

        /** Log resets handled within one [run] before it returns (a server whose epoch keeps changing). */
        public const val MAX_LOG_RESETS: Int = 3

        private const val UNKNOWN_REFERENCE = "unknown_reference"
    }
}
