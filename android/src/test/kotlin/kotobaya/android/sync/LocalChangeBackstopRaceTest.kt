package kotobaya.android.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotobaya.android.net.Reachability
import kotobaya.sync.client.protocol.CycleResult

/**
 * LF-A1b item 2 (SYNC.md 6.1 "Local-change trigger, debounced"): a local change that lands while a debounced cycle is
 * finishing keeps its process-death backstop. The race: the change's backstop request is a no-op (KEEP: one is
 * pending), the finishing cycle sees no new change and cancels the backstop, and only then is the change counted.
 */
class LocalChangeBackstopRaceTest {
    /** The unique LOCAL work as KEEP sees it: a request takes effect when made; a cancel removes it. */
    private class FakeBackstop : LocalChangeBackstop {
        @Volatile var pending = false
        @Volatile var onRequest: () -> Unit = {}
        val cancels = AtomicInteger()
        @Volatile var cancelled = CountDownLatch(1)

        override fun request() {
            pending = true
            onRequest() // the requesting thread is pre-empted right after the enqueue returned
        }

        override fun cancel() {
            pending = false
            cancels.incrementAndGet()
            cancelled.countDown()
        }
    }

    @Test
    fun aChangeLandingWhileTheDebouncedCycleFinishesKeepsItsBackstop() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val started = AtomicInteger()
        val firstStarted = CountDownLatch(1)
        val coordinator = SyncCoordinator(
            scope = scope,
            runCycle = {
                if (started.incrementAndGet() == 1) firstStarted.countDown()
                gate.receive()
                CycleResult.Completed(0, 0, 0)
            },
            isPaired = { true },
            isOnline = { true },
            probe = { Reachability.Reachable(authenticated = true) },
            counter = object : FailureCounter {
                override var failures: Int = 0
            },
        )
        val backstop = FakeBackstop()
        val triggers = SyncTriggers(coordinator, scope, { null }, quietMs = 20, maxWaitMs = 100, backstop = backstop)
        try {
            triggers.localChange()
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "the debounced cycle started")

            // the second change is caught right after its backstop request returned
            val inRequest = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            backstop.onRequest = {
                inRequest.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }
            val second = thread(name = "second-change") { triggers.localChange() }
            assertTrue(inRequest.await(5, TimeUnit.SECONDS))
            gate.trySend(Unit) // the first cycle completes while the second change is in flight
            backstop.cancelled.await(500, TimeUnit.MILLISECONDS) // the first cycle's end-of-cycle check has run
            backstop.onRequest = {}
            proceed.countDown()
            second.join(5_000)

            assertTrue(backstop.pending, "the second change has a backstop until a cycle covers it")
            assertEquals(0, backstop.cancels.get(), "the cycle that did not cover the change kept the backstop")

            // the cycle that covers it cancels the backstop
            gate.trySend(Unit)
            assertTrue(backstop.cancelled.await(5, TimeUnit.SECONDS))
            assertFalse(backstop.pending)
            assertEquals(2, started.get())
        } finally {
            scope.cancel()
        }
    }
}
