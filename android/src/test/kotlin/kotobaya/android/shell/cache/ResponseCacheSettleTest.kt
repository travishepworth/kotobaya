package kotobaya.android.shell.cache

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.Upstream

/**
 * LF-A2b: [ResponseCache]'s settle step against a concurrent [ResponseCache.clear] (unpair / re-pair) and against a
 * stale mark that lands while the fetch is in flight (K-4). The warm pass ([ResponseCache.warm]) runs the fetches;
 * only `getStatsForecast` answers `2xx`, every other seed gets a `500` (stores nothing).
 */
class ResponseCacheSettleTest {
    private val store = MapCacheStore()
    private val now = 1_795_168_800_000L
    private val events = ArrayList<CacheUpdate>()
    private var duringFetch: () -> Unit = {}
    private var answer: ShellResponse = json("""{"days":[2]}""")

    private val upstream = Upstream { _, op, _ ->
        if (op.operationId != FORECAST) {
            ShellResponse(500, mapOf("content-type" to "application/problem+json"), "{}")
        } else {
            duringFetch()
            answer
        }
    }

    private val cache = ResponseCache(
        store = store,
        upstream = upstream,
        scope = CoroutineScope(Dispatchers.Unconfined),
        isOnline = { true },
        hasPendingOps = { false },
        onUpdated = { events += it },
        newRequestId = { "bg" },
        nowMs = { now },
    )

    private val forecast = CacheTarget(FORECAST, "/api/v1/stats/forecast", "")

    private fun json(body: String) = ShellResponse(200, mapOf("content-type" to "application/json"), body)

    private fun seed(body: String, stale: Boolean, fetchedAt: Long = now - 120_000) = store.put(
        CacheEntry(forecast.key, FORECAST, forecast.path, "", 200, "application/json", body, body.length.toLong(), null, fetchedAt, fetchedAt, stale),
    )

    private fun row(): CacheEntry = store.peek(forecast.key)!!

    @Test
    fun clearDuringSettleLeavesNoRowOfTheOldPairing() {
        // the fetch has its answer; unpair runs on another thread just as settle reads the row
        var armed = false
        duringFetch = { armed = true }
        val cleared = CountDownLatch(1)
        var clearer: Thread? = null
        store.onGet = {
            if (armed) {
                armed = false
                clearer = thread { cache.clear(); cleared.countDown() }
                cleared.await(300, TimeUnit.MILLISECONDS) // under the lock, clear() waits for settle instead
            }
        }
        runBlocking { cache.warm() }
        clearer!!.join(5_000)
        assertTrue(cleared.await(5, TimeUnit.SECONDS))
        assertNull(store.peek(forecast.key), "the old pairing's body must not survive clear()")
    }

    @Test
    fun fetchStartedBeforeClearStoresNothing() {
        duringFetch = { cache.clear() }
        runBlocking { cache.warm() }
        assertNull(store.peek(forecast.key))
        assertEquals(emptyList(), events.filter { it.operationId == FORECAST })
    }

    @Test
    fun staleMarkDuringTheFetchSurvivesANewBody() {
        seed("""{"days":[1]}""", stale = false)
        duringFetch = { cache.onReplicaChanged(setOf("review_log")) }
        runBlocking { cache.warm() }
        assertEquals("""{"days":[2]}""", row().body)
        assertTrue(row().stale, "a local write during the revalidation keeps its stale mark")
    }

    @Test
    fun staleMarkDuringTheFetchSurvivesTheSameBytes() {
        seed("""{"days":[2]}""", stale = true)
        duringFetch = { cache.onAllStale() }
        runBlocking { cache.warm() }
        assertTrue(row().stale)
        assertEquals(now, row().fetchedAt)
    }

    @Test
    fun staleMarkDuringTheFetchSurvivesA304() {
        seed("""{"days":[1]}""", stale = true)
        answer = ShellResponse(304, emptyMap(), null)
        duringFetch = { cache.onReplicaChanged(null) }
        runBlocking { cache.warm() }
        assertTrue(row().stale)
        assertEquals(now, row().fetchedAt)
    }

    @Test
    fun aMarkOnAnUnrelatedOperationDoesNotKeepTheEntryStale() {
        seed("""{"days":[1]}""", stale = true)
        duringFetch = { cache.onParseOverrideWritten() }
        runBlocking { cache.warm() }
        assertFalse(row().stale)
    }

    @Test
    fun aMarkBeforeTheFetchStartedIsClearedByIt() {
        seed("""{"days":[1]}""", stale = false)
        cache.onReplicaChanged(setOf("card"))
        assertTrue(row().stale)
        runBlocking { cache.warm() }
        assertFalse(row().stale)
        assertEquals("""{"days":[2]}""", row().body)
        assertEquals(listOf(CacheUpdate(FORECAST, "/api/v1/stats/forecast", "", 200)), events.filter { it.operationId == FORECAST })
    }

    private companion object {
        const val FORECAST = "getStatsForecast"
    }
}
