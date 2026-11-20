package kotobaya.android.shell.proxy

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotobaya.android.net.Reachability
import kotobaya.android.sync.FailureCounter
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SyncCoordinator
import kotobaya.android.sync.SyncTrigger
import kotobaya.android.sync.memoryDriver
import kotobaya.sync.client.protocol.CycleResult

/** LF-A2b: [CoordinatorProxySync.requestPull] asks for a MANUAL cycle and stops when its scope is cancelled. */
class CoordinatorProxySyncTest {
    private val store = SqlDelightDeviceStore(memoryDriver())
    private val triggers = CopyOnWriteArrayList<SyncTrigger>()
    private val coordinatorCalls = AtomicInteger()
    private var gate: CompletableDeferred<Unit>? = null
    private val started = CompletableDeferred<Unit>()

    private val coordinator = SyncCoordinator(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        runCycle = {
            started.complete(Unit)
            gate?.await()
            CycleResult.Completed(0, 0, 0)
        },
        isPaired = { true },
        isOnline = { true },
        probe = { Reachability.Reachable(authenticated = true) },
        counter = object : FailureCounter {
            override var failures: Int = 0
        },
        onOutcome = { trigger, _, _ -> triggers += trigger },
    )

    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val proxy = CoordinatorProxySync({ coordinatorCalls.incrementAndGet(); coordinator }, { store }, scope)

    private fun awaitScope() = runBlocking {
        withTimeout(5_000) { scope.coroutineContext[Job]!!.children.forEach { it.join() } }
    }

    @Test
    fun requestPullRunsManualCycles() {
        proxy.requestPull(10) // the cursor stays at 0: two cycles
        awaitScope()
        assertEquals(listOf(SyncTrigger.MANUAL, SyncTrigger.MANUAL), triggers.toList())
    }

    @Test
    fun cursorAlreadyThereRunsNothing() {
        proxy.requestPull(0)
        awaitScope()
        assertEquals(0, coordinatorCalls.get())
    }

    @Test
    fun cancellationStopsTheRetry() {
        gate = CompletableDeferred()
        proxy.requestPull(10)
        runBlocking { withTimeout(5_000) { started.await() } }
        scope.cancel()
        runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]!!.join() } }
        gate?.complete(Unit)
        assertEquals(1, coordinatorCalls.get(), "a cancelled request must not start a second cycle")
    }
}
