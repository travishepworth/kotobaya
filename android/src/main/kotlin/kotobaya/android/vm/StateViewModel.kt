package kotobaya.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI-FOUND-APP: the base of every screen's ViewModel. One immutable UiState in a [StateFlow]; the screen's
 * composable collects it and calls the ViewModel's intent functions. Every intent that touches storage or the
 * network runs through [launchIo] on [io] (Dispatchers.IO in the app; an immediate dispatcher in previews, the
 * screenshot harness and tests), so no database statement runs on the main thread (BUG-8, DbThreadGuard).
 */
abstract class StateViewModel<S>(initial: S, protected val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val mutable = MutableStateFlow(initial)

    /** The screen state. */
    val state: StateFlow<S> = mutable.asStateFlow()

    /** The current state. */
    protected val current: S get() = mutable.value

    /** Atomically replaces the state (safe from any thread). */
    protected fun update(f: (S) -> S) = mutable.update(f)

    /**
     * Called (on the failing thread) when a [launchIo] block throws. The failure is already logged with the tag
     * [ScreenLog.TAG]; override to leave the loading state (AND-POSTSYNC: a failed load used to crash the process
     * from the ViewModel's scope, with nothing tagged in the log).
     */
    protected open fun onFailure(e: Throwable) {}

    private val failures = CoroutineExceptionHandler { _, e ->
        ScreenLog.loadFailed(javaClass.simpleName, e)
        runCatching { onFailure(e) }.onFailure { ScreenLog.loadFailed(javaClass.simpleName + ".onFailure", it) }
    }

    /**
     * Runs [block] on [io] in the ViewModel's scope (cancelled when the screen's back-stack entry goes). An exception
     * it throws is logged and passed to [onFailure]; it does not crash the app.
     */
    protected fun launchIo(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch(io + failures, block = block)
}
