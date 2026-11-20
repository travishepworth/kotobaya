package kotobaya.android.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotobaya.android.shell.api.device.SyncStatusData
import kotobaya.android.shell.api.device.toJson
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.SyncTransport
import kotobaya.sync.client.protocol.TransportResponse
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.bookkeeping

class FirstSyncStatusTest {
    private val deviceId = SyntheticHistory.DEVICE.replace("a1", "c3")

    private val server = TestServer(nowMs = 0x0193a1b2c000L + 200_000L + 60_000).also { s ->
        s.pageSize = 100
        for (op in SyntheticHistory.ops(200)) s.append(op, null)
    }
    private val store = SqlDelightDeviceStore(DbFile().open()).also { st ->
        st.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, deviceId) }
    }
    private val repo = SyncStatusRepository(store, { server.nowMs }, { true })

    /** Passes calls through; the [killOn]-th pull throws (a process kill), pulls from [failFrom] on fail like a network error. */
    private class Flaky(private val inner: SyncTransport) : SyncTransport {
        var pulls = 0
        var failFrom = Int.MAX_VALUE
        var killOn = Int.MAX_VALUE
        val sinces = ArrayList<Long>()
        override suspend fun push(body: String) = inner.push(body)
        override suspend fun limits() = inner.limits()
        override suspend fun pull(since: Long): TransportResponse {
            pulls++
            sinces += since
            if (pulls == killOn) throw Killed("before page")
            if (pulls >= failFrom) return TransportResponse.NetworkError("offline")
            return inner.pull(since)
        }
    }

    private fun client(t: SyncTransport, seen: MutableList<JsonObject>? = null) =
        SyncClient(store, t, TestClock(server.nowMs), onPullProgress = {
            repo.onProgress(it)
            seen?.add(json())
        })

    private fun json(): JsonObject {
        val pull = store.read { it.bookkeeping.pullSeq }
        val status = SyncStatusData(repo.current(), true, pull).toJson()
        return status.members.getValue("first_sync") as JsonObject
    }

    private fun JsonObject.done() = (members.getValue("done") as JsonBool).value
    private fun JsonObject.applied() = (members.getValue("applied") as JsonNumber).value.toLong()
    private fun JsonObject.total() = (members.getValue("total") as JsonNumber).value.toLong()

    @Test
    fun firstSyncRisesThenIsDoneAndNeverGoesBack() {
        val before = json()
        assertFalse(before.done())
        assertEquals(0L, before.applied())
        assertEquals(JsonNull, before.members.getValue("total"))

        val seen = ArrayList<JsonObject>()
        val c = client(Flaky(TestTransport(server)), seen)
        repo.onRunning(true)
        assertIs<CycleResult.Completed>(runBlocking { c.engine.cycle() })
        repo.onRunning(false)

        assertTrue(seen.size >= 4, "one report per committed sub-page")
        assertTrue(seen.none { it.done() }, "not done before the pull completed")
        seen.zipWithNext().forEach { (a, b) -> assertTrue(b.applied() > a.applied(), "applied rises") }
        assertEquals(server.highWaterSeq, seen.first().total())

        val after = json()
        assertTrue(after.done())
        assertEquals(server.highWaterSeq, after.applied())
        assertEquals(after.applied(), after.total())

        // a failed later cycle leaves it done
        runBlocking { client(Flaky(TestTransport(server)).also { it.failFrom = 1 }).engine.cycle() }
        assertTrue(json().done())
        assertEquals(server.highWaterSeq, json().applied())
    }

    @Test
    fun anInterruptedFirstSyncResumesFromTheCursorAndEndsDone() {
        val t = Flaky(TestTransport(server)).also { it.killOn = 2 }
        assertFailsWith<Killed> { runBlocking { client(t).engine.cycle() } }
        val mid = json()
        assertFalse(mid.done())
        assertEquals(100L, mid.applied(), "the first page is committed")
        assertNull(store.read { it.bookkeeping.lastSyncAt })

        val resumed = Flaky(TestTransport(server))
        assertIs<CycleResult.Completed>(runBlocking { client(resumed).engine.cycle() })
        assertEquals(100L, resumed.sinces.first(), "resumes from the stored cursor, not from 0")
        assertTrue(json().done())
        assertEquals(server.highWaterSeq, json().applied())
    }
}
