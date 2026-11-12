package kotobaya.android.ankiapi.provider

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where [AnkiProvider] gets the real, persistent [AnkiStore]. Set once by the app (T305c's Koin
 * module, from `Application.onCreate`). Content providers are created before
 * `Application.onCreate`, so a call arriving during a cold start waits up to [waitMillis] for the
 * store; if it is still missing, the provider refuses the call (IllegalStateException across the
 * binder) instead of pretending to save anything. There is deliberately no in-memory fallback.
 */
object AnkiStoreHolder {
    const val DEFAULT_WAIT_MILLIS: Long = 5_000

    @Volatile
    private var store: AnkiStore? = null

    @Volatile
    private var ready = CountDownLatch(1)

    /** How long a provider call waits for [set] before failing. */
    @Volatile
    var waitMillis: Long = DEFAULT_WAIT_MILLIS

    fun set(store: AnkiStore) {
        this.store = store
        ready.countDown()
    }

    /** The store, waiting up to [waitMillis] for it; null if none was set in time. */
    fun await(): AnkiStore? {
        store?.let { return it }
        ready.await(waitMillis, TimeUnit.MILLISECONDS)
        return store
    }

    /** Tests only: forget the store. */
    internal fun reset() {
        store = null
        ready = CountDownLatch(1)
        waitMillis = DEFAULT_WAIT_MILLIS
    }
}
