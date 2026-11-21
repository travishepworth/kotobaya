package kotobaya.android.shell.proxy

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SyncCoordinator
import kotobaya.android.sync.SyncTrigger
import kotobaya.sync.client.store.bookkeeping

/** What [ServerUpstream] needs from the sync engine (SHELL.md P-3 as amended by C-31). Nothing here is awaited by a request. */
interface ProxySync {
    /** Schedules a pull that brings the replica to at least [seq]; returns at once (not debounced, not awaited). */
    fun requestPull(seq: Long)

    /** The device's pull cursor (`sync_state.pull_seq`). */
    fun pullCursor(): Long
}

/**
 * [ProxySync] over the app's single-flight [SyncCoordinator]. [requestPull] runs on [scope], never on the request:
 * a cycle (push then pull); when it joined one that was already running and the cursor is still behind, one more.
 */
class CoordinatorProxySync(
    private val coordinator: () -> SyncCoordinator,
    private val store: () -> SqlDelightDeviceStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ProxySync {
    override fun requestPull(seq: Long) {
        scope.launch {
            repeat(2) {
                if (pullCursor() >= seq) return@launch
                try {
                    // MANUAL: the user's own write asked for it (PUSH is the server's push-notification trigger)
                    coordinator().sync(SyncTrigger.MANUAL)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // the next cycle (or the next remote write) tries again
                }
            }
        }
    }

    override fun pullCursor(): Long = store().read { it.bookkeeping.pullSeq }
}
