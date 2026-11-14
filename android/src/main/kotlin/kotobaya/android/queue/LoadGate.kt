package kotobaya.android.queue

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * AND-PERF: conflates concurrent queue builds per key so triggers (home, widget, notification, review, each sync
 * chunk) cannot hold every SQLite pool connection with the same reads.
 *
 * For one key at most one build runs at a time. A caller arriving while a build runs waits; the next build (which
 * starts after it arrived, so it sees everything committed before the call) serves every caller that waited for it.
 * N concurrent callers therefore cause at most two builds, never N, and no caller gets a result older than its call.
 * A failed build is not shared: its caller rethrows, a waiting caller builds again.
 */
class LoadGate {
    private class Slot {
        val lock = ReentrantLock()
        /** Number of calls so far (guarded by the slot's monitor); a build started after call n covers it. */
        var requested = 0L

        /** The last call number a finished build covers, and its result (guarded by [lock]). */
        var completed = 0L
        var value: Any? = null
    }

    private val slots = HashMap<Any, Slot>()

    private fun slot(key: Any): Slot = synchronized(slots) { slots.getOrPut(key) { Slot() } }

    /** Runs [build] for [key] unless a build that started after this call completes first; returns its result. */
    fun <T> run(key: Any, build: () -> T): T {
        val s = slot(key)
        val mine = synchronized(s) { ++s.requested }
        s.lock.withLock {
            if (s.completed >= mine) {
                @Suppress("UNCHECKED_CAST")
                return s.value as T
            }
            val covers = synchronized(s) { s.requested }
            val v = build()
            s.value = v
            s.completed = covers
            return v
        }
    }
}
