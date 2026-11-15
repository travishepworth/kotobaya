package kotobaya.android.review

import android.util.Log

/**
 * Where the review screen's latency goes (AND-REVIEW-SNAPPY). One line per event under [ReviewTiming.TAG]
 * (`adb logcat -s KotobayaReview`). No card content is ever logged: kinds, sides and milliseconds only.
 */
sealed interface ReviewTiming {
    fun line(): String

    /**
     * One answer. [advanceMs]: grade tap to the next card's state being published (what the user waits for);
     * of that, [selectMs] picking the next item from the in-memory queue and [renderMs] rendering it (0 when it was
     * pre-rendered, [prefetchHit]). [commitMs]: the mint transaction (op + card cache fold/replay + outbox, fsynced);
     * [requeueMs]: re-reading the answered card to requeue it; [prefetchMs]: pre-rendering the card after next;
     * [rebuildMs]: a full queue build after this answer, or null when none ran. [outcome]: committed, refused,
     * failed or withdrawn (undone before it was written).
     */
    data class Answer(
        val kind: String,
        val advanceMs: Double,
        val selectMs: Double,
        val renderMs: Double,
        val prefetchHit: Boolean,
        /** Queue items passed over as not showable (deleted, merged, refused cache, sibling answered). */
        val skipped: Int,
        val commitMs: Double,
        val requeueMs: Double,
        val prefetchMs: Double,
        val rebuildMs: Double?,
        val outcome: String,
    ) : ReviewTiming {
        override fun line(): String =
            "answer $kind $outcome advance=${f(advanceMs)} select=${f(selectMs)} render=${f(renderMs)}" +
                (if (prefetchHit) "(prefetched)" else "") +
                (if (skipped > 0) " skipped=$skipped" else "") +
                " commit=${f(commitMs)} requeue=${f(requeueMs)} prefetch=${f(prefetchMs)}" +
                (rebuildMs?.let { " rebuild=${f(it)}" } ?: "")
    }

    /** Front to back: the session's state change ([flipMs]); the page paint is logged by the card WebView ([Page]). */
    data class Reveal(val flipMs: Double) : ReviewTiming {
        override fun line(): String = "reveal flip=${f(flipMs)}"
    }

    /** A full queue build by the session (start, end of the in-memory queue, or the periodic refresh). */
    data class Rebuild(val why: String, val loadMs: Double, val items: Int) : ReviewTiming {
        override fun line(): String = "rebuild $why load=${f(loadMs)} items=$items"
    }

    /** The card WebView: [describe] (`vocab/meaning back`) from load start to the page being visible. */
    data class Page(val describe: String, val paintMs: Double) : ReviewTiming {
        override fun line(): String = "page $describe paint=${f(paintMs)}"
    }

    companion object {
        const val TAG: String = "KotobayaReview"

        /** The production sink: logcat (a no-op in plain JVM tests). */
        val LOG: (ReviewTiming) -> Unit = { t -> runCatching { Log.i(TAG, t.line()) } }

        internal fun ms(fromNs: Long, toNs: Long = System.nanoTime()): Double = (toNs - fromNs) / 1_000_000.0

        private fun f(ms: Double): String = "%.1fms".format(java.util.Locale.ROOT, ms)
    }
}
