package kotobaya.android.shell.api.device

import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotobaya.android.shell.web.BridgeCore
import kotobaya.android.shell.web.BridgeMessage
import kotobaya.sync.DefaultCanonicalJson

/**
 * The `shell.dict` event (SHELL.md 2.6): the `getDictInstall` state whenever any property changed, at most twice a second
 * while an install runs; the state at emit time, so the final state is always delivered. Identical states are not repeated.
 * Sources: the installer's state and generation ([DictOps.changes]) and [poke] (gap-fill counts change after a sync).
 */
class DictEvents(
    private val scope: CoroutineScope,
    private val ops: DictOps,
    private val send: (BridgeMessage.Event) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = 500,
) {
    private val guard = Any()
    private var scheduled = false
    private var lastAt: Long? = null
    private var lastText: String? = null

    /** Starts collecting [DictOps.changes]. */
    fun start() {
        scope.launch { ops.changes().collect { poke() } }
    }

    /** Something may have changed. */
    fun poke() {
        val wait: Long
        synchronized(guard) {
            if (scheduled) return
            scheduled = true
            wait = lastAt?.let { max(0L, it + intervalMs - nowMs()) } ?: 0L
        }
        scope.launch {
            if (wait > 0) delay(wait)
            synchronized(guard) {
                scheduled = false
                lastAt = nowMs()
            }
            runCatching { emit() }
        }
    }

    private fun emit() {
        val json = ops.state().toJson()
        val text = DefaultCanonicalJson.encode(json)
        synchronized(guard) {
            if (text == lastText) return
            lastText = text
        }
        runCatching { send(BridgeMessage.Event("shell.dict", BridgeCore.formatAt(nowMs()), json)) }
    }
}
