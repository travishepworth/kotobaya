package kotobaya.android.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotobaya.android.net.ApiError
import kotobaya.android.net.Reachability
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.FailureKind
import kotobaya.sync.client.protocol.SyncPhase
import kotobaya.sync.client.protocol.SyncStep

class SyncCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = SqlDelightDeviceStore(memoryDriver())
    private val counter = StoreFailureCounter(store)
    private val status = SyncStatusRepository(store, { 1_733_500_000_000L }, { true }, { counter.failures })

    private var online = true
    private var probeResult: Reachability = Reachability.Reachable(authenticated = true)
    private var cycleResult: CycleResult = CycleResult.Completed(0, 0, 0)
    private val cycles = AtomicInteger()
    private val delays = ArrayList<Long>()
    private var gate: CompletableDeferred<Unit>? = null

    private fun coordinator(ready: suspend () -> Unit = {}) = SyncCoordinator(
        scope = scope,
        runCycle = {
            cycles.incrementAndGet()
            gate?.await()
            cycleResult
        },
        isPaired = { true },
        isOnline = { online },
        probe = { probeResult },
        counter = counter,
        ready = ready,
        jitter = { 0.0 },
        onOutcome = { _, outcome, delay ->
            status.onOutcome(outcome.asCycleResult())
            if (outcome.isFailure) delays += delay
        },
    )

    @Test
    fun concurrentTriggersRunOneSync() = runBlocking {
        val c = coordinator()
        gate = CompletableDeferred()
        val calls = SyncTrigger.entries.map { t -> async(Dispatchers.Default) { c.sync(t) } }
        withTimeout(5_000) { while (!c.running.value) kotlinx.coroutines.delay(5) }
        gate!!.complete(Unit)
        val outcomes = calls.awaitAll()
        assertEquals(1, cycles.get())
        assertEquals(1, c.cyclesRun)
        assertTrue(outcomes.all { it == outcomes.first() })
        assertFalse(c.running.value)
        // the next trigger after it finished runs a new cycle
        c.sync(SyncTrigger.MANUAL)
        assertEquals(2, cycles.get())
    }

    @Test
    fun offlineSkipsTheCycleWithStatusOffline() = runBlocking {
        online = false
        val c = coordinator()
        assertEquals(SyncOutcome.Offline, c.sync(SyncTrigger.WORKER))
        assertEquals(0, cycles.get())
        assertEquals(SyncPhase.Offline, status.current().status.phase)
        assertEquals(0, counter.failures, "a skip is not a failure")
        // a probe that finds no network is offline too
        online = true
        probeResult = Reachability.Down(ApiError.Offline)
        assertEquals(SyncOutcome.Offline, c.sync(SyncTrigger.WORKER))
        assertEquals(0, cycles.get())
        assertEquals(0, counter.failures)
    }

    @Test
    fun aReachabilityFailureIsServerUnreachableAndAFailure() = runBlocking {
        probeResult = Reachability.Down(ApiError.Unreachable("ConnectException"))
        val c = coordinator()
        assertIs<SyncOutcome.Unreachable>(c.sync(SyncTrigger.APP_OPEN))
        assertEquals(0, cycles.get())
        assertEquals(SyncPhase.ServerUnreachable(null), status.current().status.phase)
        assertEquals(1, counter.failures)
    }

    @Test
    fun backoffGrowsPerT303aPolicyAndResetsAfterSuccess() = runBlocking {
        val c = coordinator()
        cycleResult = CycleResult.Failed(FailureKind.SERVER_ERROR, SyncStep.PUSH, "503", 503)
        repeat(4) { c.sync(SyncTrigger.WORKER) }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L), delays)
        assertEquals(4, c.consecutiveFailures)
        // durable: a new process continues the count
        assertEquals(4, StoreFailureCounter(store).failures)
        cycleResult = CycleResult.Completed(1, 1, 0)
        c.sync(SyncTrigger.WORKER)
        assertEquals(0, c.consecutiveFailures)
        assertEquals(0L, c.retryDelayMs())
        assertEquals(SyncPhase.Idle, status.current().status.phase)
    }

    @Test
    fun backoffIsCappedAt15Minutes() = runBlocking {
        val c = coordinator()
        cycleResult = CycleResult.Failed(FailureKind.OFFLINE, SyncStep.PULL, "timeout")
        repeat(12) { c.sync(SyncTrigger.WORKER) }
        assertEquals(900_000L, delays.last())
    }

    @Test
    fun authFailuresStopWithoutBackoffAndLocalExceptionsAreFailures() = runBlocking {
        val c = coordinator()
        probeResult = Reachability.Reachable(authenticated = false)
        val auth = assertIs<SyncOutcome.Ran>(c.sync(SyncTrigger.WORKER))
        assertEquals(FailureKind.AUTH, assertIs<CycleResult.Failed>(auth.result).kind)
        assertEquals(0, counter.failures)
        assertEquals(SyncPhase.AuthRequired, status.current().status.phase)

        probeResult = Reachability.Reachable(authenticated = true)
        val boom = SyncCoordinator(scope, { error("disk I/O error") }, { true }, { true }, { probeResult }, counter, jitter = { 0.0 })
        val r = assertIs<SyncOutcome.Ran>(boom.sync(SyncTrigger.WORKER))
        assertEquals(FailureKind.LOCAL_APPLY, assertIs<CycleResult.Failed>(r.result).kind)
        assertEquals(1, counter.failures)
    }

    @Test
    fun everySyncWaitsForTheStartupReplay() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val c = coordinator { ready.await() }
        val pending = async(Dispatchers.Default) { c.sync(SyncTrigger.APP_OPEN) }
        kotlinx.coroutines.delay(100)
        assertEquals(0, cycles.get())
        ready.complete(Unit)
        pending.await()
        assertEquals(1, cycles.get())
    }

    @Test
    fun notPairedDoesNothing() = runBlocking {
        val c = SyncCoordinator(scope, { error("must not run") }, { false }, { true }, { probeResult }, counter)
        assertEquals(SyncOutcome.NotPaired, c.sync(SyncTrigger.MANUAL))
        assertEquals(0, counter.failures)
    }

    @Test
    fun staleIsMoreThan48Hours() {
        val now = 1_733_500_000_000L
        assertFalse(SyncStatusRepository.isStale(null, now))
        assertFalse(SyncStatusRepository.isStale(now - SyncStatusRepository.STALE_AFTER_MS, now))
        assertTrue(SyncStatusRepository.isStale(now - SyncStatusRepository.STALE_AFTER_MS - 1, now))
        store.transaction { it.syncState.put("last_sync_at", (now - 3 * 86_400_000L).toString()) }
        val view = SyncStatusRepository(store, { now }, { true }).current()
        assertTrue(view.staleOverTwoDays)
        assertEquals(now - 3 * 86_400_000L, view.status.lastSyncAt)
    }
}
