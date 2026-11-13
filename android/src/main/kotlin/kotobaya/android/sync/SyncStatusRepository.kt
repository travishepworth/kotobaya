package kotobaya.android.sync

import kotobaya.sync.client.protocol.PullProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.SyncSnapshot
import kotobaya.sync.client.protocol.SyncStatus
import kotobaya.sync.client.protocol.UpgradeRequiredState

/**
 * What the sync status chip (T303e) and the stale-sync notification (T308) read.
 *
 * [status] is T303a's [SyncStatus] (phase, pending / acked / rejected counts, last sync, clock warning,
 * minting blocked, durable refused-cache count). The extras are not in it:
 */
data class SyncStatusView(
    val status: SyncStatus,
    /** The device is paired (otherwise nothing syncs and the chip says so). */
    val paired: Boolean,
    /** `last_sync_at` is more than 48 hours ago (spec "Sync and offline"; T308's "sync stale for 2 days"). */
    val staleOverTwoDays: Boolean,
    /** The persisted *upgrade required* stop (SYNC.md 6.1: `seq`, `op_id`, first error), if any. */
    val upgradeRequired: UpgradeRequiredState?,
    /** Consecutive failed cycles (backoff count). */
    val consecutiveFailures: Int,
    /** The last outcome was "network up, server did not answer" (the coordinator's probe), not an HTTP/protocol error. */
    val unreachable: Boolean = false,
    /** While a cycle pulls: how far (BUG-8, "Syncing N of M"); null otherwise. */
    val progress: PullProgress? = null,
)

/**
 * A [StateFlow] of [SyncStatusView], recomputed after every committed store write (mint, push result,
 * pull page, rebuild step), every sync outcome and every change of [running]. Reads go through
 * [SqlDelightDeviceStore.read] (one transaction, no commit notification).
 */
class SyncStatusRepository(
    private val store: SqlDelightDeviceStore,
    private val clock: () -> Long,
    private val paired: () -> Boolean,
    private val failures: () -> Int = { 0 },
) {
    private val state = MutableStateFlow<SyncStatusView?>(null)

    @Volatile
    private var last: CycleResult? = null

    @Volatile
    private var lastUnreachable: Boolean = false

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var progress: PullProgress? = null

    /** The status; null until the first [refresh] (the bootstrap runs one after the startup replay). */
    val status: StateFlow<SyncStatusView?> = state.asStateFlow()

    /** The current status, computed now. */
    fun current(): SyncStatusView = compute().also { publish(it) }

    /** Records the last sync outcome (as a [CycleResult], null for "nothing to report"). */
    fun onOutcome(result: CycleResult?, unreachable: Boolean = false) {
        last = result
        lastUnreachable = unreachable
        refresh()
    }

    /** Records whether a cycle runs. */
    fun onRunning(value: Boolean) {
        running = value
        if (!value) progress = null
        refresh()
    }

    /** Records pull progress (after each committed chunk). Updates the published view without a database read. */
    fun onProgress(p: PullProgress) {
        progress = p
        // atomic: a refresh() publishing from another thread at the same moment cannot drop this progress
        state.update { it?.copy(progress = p) }
    }

    /** Recomputes from the store. Never throws (a failed read keeps the previous value). */
    fun refresh() {
        runCatching { compute() }.onSuccess { publish(it) }
    }

    /**
     * Publishes [computed], atomically, with the newest pull progress: [onProgress] (sync thread) and [refresh]
     * (any thread) both go through [MutableStateFlow.update], so neither overwrites the other's newer progress.
     */
    private fun publish(computed: SyncStatusView) {
        state.update { computed.copy(progress = if (running) progress else null) }
    }

    private fun compute(): SyncStatusView {
        val snapshot = store.read { tx -> SyncSnapshot.read(tx) { it.refusedCacheCount() } }
        val now = clock()
        return SyncStatusView(
            status = SyncStatus.of(snapshot, last, running, now),
            paired = paired(),
            staleOverTwoDays = isStale(snapshot.lastSyncAt, now),
            upgradeRequired = snapshot.upgradeRequired,
            consecutiveFailures = failures(),
            unreachable = lastUnreachable,
            progress = if (running) progress else null,
        )
    }

    companion object {
        /** 48 hours. */
        const val STALE_AFTER_MS: Long = 48L * 60 * 60 * 1000

        /** True when [lastSyncAt] is more than 48 h before [now]. Never synced reads as not stale (no reference point). */
        fun isStale(lastSyncAt: Long?, now: Long): Boolean = lastSyncAt != null && now - lastSyncAt > STALE_AFTER_MS
    }
}
