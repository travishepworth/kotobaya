package kotobaya.android.queue

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * AND-PERF: conflates concurrent queue builds that have the same key (same loader arguments), so a burst of
 * triggers (home, widget, notification, review, each sync chunk) does not run the same reads N times in parallel.
 * Calls with different keys are not serialized against each other.
 *
 * For one key at most one build runs at a time. A caller arriving while a build runs waits; the next build (which
 * starts after it arrived, so it sees everything committed before the call) serves every caller that waited for it.
 * N callers arriving during one build therefore cause one more build, not N, and no caller gets a result older
 * than its call. A failed build is not shared: its caller rethrows, a waiting caller builds again.
 *
 * Nothing is cached: a result lives only while callers of its key are inside [run]; the key's slot is dropped
 * when the last one leaves (a review session's per-answer keys must not pile up queues in the singleton).
 */
class LoadGate {
    private class Slot {
        val lock = ReentrantLock()

        /** Callers inside [run] for this key (guarded by `slots`); the slot is removed when it drops to 0. */
        var users = 0

        /** Number of calls so far (guarded by `slots`); a build started after call n covers it. */
        var requested = 0L

        /** The last call number a finished build covers, and its result (guarded by [lock]). */
        var completed = 0L
        var value: Any? = null
    }

    private val slots = HashMap<Any, Slot>()

    /** Number of keys with callers inside [run] (0 when idle: no result is retained). */
    internal fun liveSlots(): Int = synchronized(slots) { slots.size }

    /** Runs [build] for [key] unless a build that started after this call completes first; returns its result. */
    fun <T> run(key: Any, build: () -> T): T {
        val (s, mine) = synchronized(slots) {
            val s = slots.getOrPut(key) { Slot() }
            s.users++
            s to ++s.requested
        }
        try {
            s.lock.withLock {
                if (s.completed >= mine) {
                    @Suppress("UNCHECKED_CAST")
                    return s.value as T
                }
                val covers = synchronized(slots) { s.requested }
                val v = build()
                s.value = v
                s.completed = covers
                return v
            }
        } finally {
            synchronized(slots) {
                if (--s.users == 0) {
                    slots.remove(key)
                    s.value = null
                }
            }
        }
    }
}
