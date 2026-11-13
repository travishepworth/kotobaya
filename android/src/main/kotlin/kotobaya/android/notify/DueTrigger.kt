package kotobaya.android.notify

/** Something that reacts to "the due state may have changed" (the notification here, the widget and tile in T307). */
fun interface DueListener {
    fun onDueChanged()
}

/**
 * The shared due-trigger seam. Anyone who changes review state calls [fire] (after each review, each sync, app open);
 * the alarm, boot, TIME_SET and TIMEZONE_CHANGED receivers call it too. Listeners are bound in Koin with
 * `single { ... } bind DueListener::class`; they are looked up lazily on every fire.
 */
class DueTrigger(
    private val background: (Runnable) -> Unit = { BACKGROUND.execute(it) },
    private val listeners: () -> List<DueListener>,
) {
    /** Runs every listener on the calling thread (they read the database: never the main thread). */
    fun fire() {
        for (l in listeners()) runCatching { l.onDueChanged() }
    }

    /** [fire] on a background thread (BUG-8), then [done]; for main-thread callers (lifecycle callbacks, receivers). */
    fun fireInBackground(done: () -> Unit = {}) = background(
        Runnable {
            try {
                fire()
            } catch (e: Exception) {
                android.util.Log.e("KotobayaDue", "due recompute failed", e)
            } finally {
                done()
            }
        },
    )

    private companion object {
        val BACKGROUND: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "kotobaya-due").apply { isDaemon = true }
        }
    }
}
