package kotobaya.android.shell.web

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotobaya.android.shell.api.AlwaysOpenGate
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.LocalRoute
import kotobaya.android.shell.api.ShellResponse
import kotobaya.contracts.sync.JsonObject

/** PERF-GATE: the split of a call's time into gate, lane, mutex and compute, and when a call is logged at all. */
class PerfTimingTest {
    private var now = 0L
    private val records = ArrayList<PerfRecord>()

    /** A lane that is busy: every task waits 20 ms (fake clock) before it runs. */
    private val busyLane = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean {
            now += 20
            return false // then the task runs inline, right after the wait
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }

    private fun core(gate: AlwaysOpenGate, handler: suspend () -> Unit, all: Boolean): BridgeCore {
        val route = LocalRoute("listDecks", "GET", "/api/v1/decks") { handler(); ShellResponse(200) }
        val api = LocalApi(listOf(route), gate, newRequestId = { "id" }, backgroundLane = busyLane)
        return BridgeCore(
            scope = CoroutineScope(SupervisorJob()),
            io = Dispatchers.Unconfined,
            main = Dispatchers.Unconfined,
            handle = { api.handle(it) },
            helloData = { JsonObject(emptyMap()) },
            nowMs = { now },
            onPerf = { records += it },
            perfAll = { all },
        )
    }

    private fun send(core: BridgeCore, id: String) {
        core.onMessage(reqText(id, path = "/api/v1/decks"), ShellOrigin.ORIGIN, true) { }
    }

    @Test
    fun gatePlusLanePlusMutexPlusComputeIsTheTotal() {
        val gate = AlwaysOpenGate(ready = CompletableDeferred())
        val release = CompletableDeferred<Unit>()
        var first = true
        val core = core(gate, {
            now += 7 // compute
            if (first) {
                first = false
                release.await()
                now += 40 // the first call holds the mutex for 40 more ms
            }
        }, all = true)
        send(core, "00000000-0000-4000-8000-0000000000b1")
        send(core, "00000000-0000-4000-8000-0000000000b2") // identical request: waits for the first one's mutex
        assertTrue(records.isEmpty()) // both wait for the gate
        now += 30 // a delayed gate
        gate.ready.complete(Unit)
        release.complete(Unit)
        assertEquals(2, records.size, records.toString())
        for (r in records) {
            assertTrue(kotlin.math.abs(r.gateMs + r.laneMs + r.mutexMs + r.computeMs - r.totalMs) <= 1, "$r")
            assertTrue(r.gateMs >= 30, "$r")
            assertTrue(r.laneMs >= 20, "$r")
        }
        assertTrue(records.any { it.mutexMs >= 40 }, records.toString())
        assertTrue("op=listDecks" in BridgeCore.perfLine(records[0]) && "KTB-PERF" == BridgeCore.PERF_TAG)
    }

    @Test
    fun aFastCallLogsNothingWithoutTheDebugFlag() {
        val core = core(AlwaysOpenGate(), { now += 5 }, all = false)
        send(core, "00000000-0000-4000-8000-0000000000b3")
        assertTrue(records.isEmpty())
    }

    @Test
    fun aFastCallIsLoggedWithTheDebugFlagAndASlowOneWithout() {
        send(core(AlwaysOpenGate(), { now += 5 }, all = true), "00000000-0000-4000-8000-0000000000b5")
        assertEquals(1, records.size)
        send(core(AlwaysOpenGate(), { now += 300 }, all = false), "00000000-0000-4000-8000-0000000000b4")
        assertEquals(2, records.size)
    }
}
