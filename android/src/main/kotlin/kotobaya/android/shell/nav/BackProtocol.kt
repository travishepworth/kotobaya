package kotobaya.android.shell.nav

/** Schedules [block] after [delayMs] on the main thread; the returned function cancels it. */
fun interface BackTimer {
    fun after(delayMs: Long, block: () -> Unit): () -> Unit
}

/**
 * Native half of the back protocol (SHELL.md 6.2, ADR-030). Main thread only.
 *
 * The callback is [enabled] iff the last `back.state` of the page life said `can_handle`. A press sends `back{id}` and
 * waits [TIMEOUT_MS] for `back.result`; no answer runs [fallback] (`goBack()` or `moveTaskToBack`). `handled=false`
 * runs [moveToBack]. A second press while one is pending runs [fallback] at once; late results are ignored.
 */
class BackProtocol(
    private val sendBack: (id: String) -> Boolean,
    private val timer: BackTimer,
    private val fallback: () -> Unit,
    private val moveToBack: () -> Unit,
    private val newId: () -> String,
    private val onEnabledChanged: (Boolean) -> Unit = {},
) {
    var enabled: Boolean = false
        private set
    private var pending: String? = null
    private var cancelTimeout: (() -> Unit)? = null

    fun onBackState(canHandle: Boolean) = setEnabled(canHandle)

    /** A new page life: disabled until the first `back.state`, no pending press. */
    fun onPageStarted() {
        clearPending()
        setEnabled(false)
    }

    fun onBackPressed() {
        if (pending != null) {
            clearPending()
            fallback()
            return
        }
        val id = newId()
        if (!sendBack(id)) {
            fallback()
            return
        }
        pending = id
        cancelTimeout = timer.after(TIMEOUT_MS) {
            if (pending == id) {
                pending = null
                cancelTimeout = null
                fallback()
            }
        }
    }

    fun onBackResult(id: String, handled: Boolean) {
        if (id != pending) return
        clearPending()
        if (!handled) moveToBack()
    }

    private fun clearPending() {
        cancelTimeout?.invoke()
        cancelTimeout = null
        pending = null
    }

    private fun setEnabled(v: Boolean) {
        if (enabled == v) return
        enabled = v
        onEnabledChanged(v)
    }

    companion object {
        const val TIMEOUT_MS: Long = 300
    }
}
