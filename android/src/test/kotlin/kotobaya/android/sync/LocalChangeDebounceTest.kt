package kotobaya.android.sync

import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotobaya.android.net.Reachability
import kotobaya.sync.client.protocol.CycleResult

/**
 * LF-A1 (RC-1; SYNC.md 6.1 "Local-change trigger, debounced", SHELL.md L-2): `SyncTriggers.localChange()` schedules a
 * cycle 2 s after the last local change, at most 10 s after the first one no cycle has started for; a change made
 * while a cycle runs is pushed by the next one. Virtual time; each cycle "pushes" every change committed before it
 * started and takes [cycleMs].
 */
class LocalChangeDebounceTest {
    /** A single-threaded dispatcher with virtual time (the module has no kotlinx-coroutines-test). */
    @OptIn(InternalCoroutinesApi::class)
    private class VirtualTime : CoroutineDispatcher(), Delay {
        var now: Long = 0
            private set
        private var seq = 0L

        private class Task(val at: Long, val seq: Long, val block: Runnable)

        private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += Task(now, seq++, block)
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            // a cancelled continuation ignores the resume
            queue += Task(now + timeMillis, seq++) { continuation.resume(Unit) }
        }

        /** Runs everything due up to [t], in time order, and leaves the clock at [t]. */
        fun advanceTo(t: Long) {
            while (true) {
                val next = queue.peek() ?: break
                if (next.at > t) break
                queue.poll()
                now = next.at
                next.block.run()
            }
            now = t
        }
    }

    private class World(val cycleMs: Long) {
        val time = VirtualTime()
        val scope = CoroutineScope(SupervisorJob() + time)
        var committed = 0
        val starts = ArrayList<Long>()

        /** (end time, changes pushed) per finished cycle. */
        val pushed = ArrayList<Pair<Long, Int>>()
        val counter = object : FailureCounter {
            override var failures: Int = 0
        }
        val coordinator = SyncCoordinator(
            scope = scope,
            runCycle = {
                starts += time.now
                val through = committed
                delay(cycleMs)
                pushed += time.now to through
                CycleResult.Completed(through, 0, 0)
            },
            isPaired = { true },
            isOnline = { true },
            probe = { Reachability.Reachable(authenticated = true) },
            counter = counter,
        )
        val triggers = SyncTriggers(coordinator, scope, { null }, nowMs = { time.now })

        fun tapAt(t: Long) {
            time.advanceTo(t)
            committed++
            triggers.localChange()
        }

        fun settle() {
            time.advanceTo(time.now + 60_000)
        }

        /** When the change number [n] (1-based) was pushed. */
        fun pushedAt(n: Int): Long = pushed.first { it.second >= n }.first
    }

    @Test
    fun thirtyAnswersInTwentySecondsStartAtMostFourCyclesAndTheLastIsPushedWithinTwelveSeconds() {
        val w = World(cycleMs = 1_500)
        val taps = (0 until 30).map { it * 20_000L / 30 }
        taps.forEach(w::tapAt)
        w.settle()
        assertTrue(w.starts.size <= 4, "cycles started at ${w.starts}")
        val lag = w.pushedAt(30) - taps.last()
        assertTrue(lag <= 12_000, "last answer pushed $lag ms after the tap (cycles at ${w.starts})")
    }

    @Test
    fun answersThenAPauseStartOneCycleTwoSecondsAfterTheLast() {
        val w = World(cycleMs = 500)
        listOf(0L, 1_500L, 3_000L, 4_000L).forEach(w::tapAt)
        w.settle()
        assertEquals(listOf(6_000L), w.starts)
        assertEquals(4, w.pushed.single().second)
    }

    @Test
    fun anUnbrokenStreamStartsACycleEveryTenSeconds() {
        val w = World(cycleMs = 500)
        var t = 0L
        while (t < 25_000) {
            w.tapAt(t)
            t += 1_500
        }
        w.settle()
        // taps at 0, 1.5, … 24 s: max wait 10 s after 0 s, then 10 s after 10.5 s (the first change the 10 s cycle did not
        // cover), then trailing 2 s after the last tap
        assertEquals(listOf(10_000L, 20_500L, 26_000L), w.starts)
    }

    @Test
    fun aChangeMadeWhileACycleRunsIsPushedByTheNextCycle() {
        val w = World(cycleMs = 5_000)
        w.tapAt(0) // cycle at 2 s, runs to 7 s
        w.tapAt(3_000) // during the cycle: its timer fires at 5 s, while the first cycle still runs
        w.settle()
        assertEquals(2, w.starts.size, "a second cycle, not a join of the running one: ${w.starts}")
        assertEquals(2_000L, w.starts[0])
        assertTrue(w.starts[1] >= 7_000, "after the first cycle ended: ${w.starts}")
        assertEquals(2, w.pushed[1].second, "the second cycle pushed the change made during the first")
    }

    @Test
    fun noLocalChangeNoCycle() {
        val w = World(cycleMs = 500)
        w.settle()
        assertEquals(emptyList(), w.starts)
    }
}
