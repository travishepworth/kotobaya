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
class DueTrigger(private val listeners: () -> List<DueListener>) {
    fun fire() {
        for (l in listeners()) runCatching { l.onDueChanged() }
    }
}
