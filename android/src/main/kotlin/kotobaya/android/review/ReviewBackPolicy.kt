package kotobaya.android.review

/**
 * ADR-030: what the system back gesture does on the review screen. After an answer of this session, back undoes it
 * (the Undo action's own path: one `review.undone` op); a second back within [windowMs], or back with nothing to
 * undo, leaves. Back on the finished pages and on a revealed back side with no answer yet always leaves.
 */
internal class ReviewBackPolicy(
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastUndoAt: Long? = null

    /** True when this back press is to undo (and is consumed); false when it should leave the screen. */
    fun shouldUndo(showing: Boolean, canUndo: Boolean): Boolean {
        if (!showing || !canUndo) return false
        val t = now()
        val last = lastUndoAt
        if (last != null && t - last <= windowMs) return false
        lastUndoAt = t
        return true
    }

    /** A new answer starts a new burst: the next back undoes again. */
    fun answered() {
        lastUndoAt = null
    }

    companion object {
        const val WINDOW_MS = 3_000L
    }
}
