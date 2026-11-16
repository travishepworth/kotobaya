package kotobaya.android.shell.proxy

import kotlinx.coroutines.delay
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SyncCoordinator
import kotobaya.android.sync.SyncTrigger
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.bookkeeping

/** What [ServerUpstream] needs from the sync engine (SHELL.md P-2, P-3). The caller bounds every call in time. */
interface ProxySync {
    /** One push of the pending ops (P-2). Failures are the caller's to ignore. */
    suspend fun push()

    /** One pull (P-3); returns when it finished or failed. */
    suspend fun pull()

    /** The device's pull cursor (`sync_state.pull_seq`). */
    fun pullCursor(): Long
}

/**
 * [ProxySync] over the app's single-flight [SyncCoordinator]: never a second engine run beside a
 * cycle in flight (a proxy push or pull joins it). A cycle is push-then-pull, so [push] and [pull]
 * are both "one cycle"; [push] skips the cycle when nothing is pending. The coordinator runs the cycle
 * in its own scope, so a caller whose time budget runs out does not cancel it.
 */
class CoordinatorProxySync(
    private val coordinator: () -> SyncCoordinator,
    private val store: () -> SqlDelightDeviceStore,
) : ProxySync {
    override suspend fun push() {
        val pending = store().read { it.outbox.inState(PendingState.PENDING, 1).isNotEmpty() }
        if (pending) coordinator().sync(SyncTrigger.MANUAL)
    }

    override suspend fun pull() {
        coordinator().sync(SyncTrigger.MANUAL)
    }

    override fun pullCursor(): Long = store().read { it.bookkeeping.pullSeq }
}

/** Pulls until [ProxySync.pullCursor] ≥ [seq]; unbounded, the caller bounds it. */
internal suspend fun ProxySync.pullUntil(seq: Long, retryDelayMs: Long) {
    while (pullCursor() < seq) {
        runCatchingNonCancel { pull() }
        if (pullCursor() >= seq) return
        delay(retryDelayMs)
    }
}

internal inline fun runCatchingNonCancel(block: () -> Unit) {
    try {
        block()
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (_: Exception) {
        // failure ignored by rule (P-2, P-3): the request is answered anyway
    }
}
