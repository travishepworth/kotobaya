package kotobaya.android.shell.api

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotobaya.android.shell.cache.CacheEntry
import kotobaya.android.shell.cache.CacheKey
import kotobaya.android.shell.cache.CacheUpdate
import kotobaya.android.shell.cache.ResponseCache
import kotobaya.android.shell.cache.SqlResponseCacheStore
import kotobaya.android.shell.proxy.ProxySync
import kotobaya.android.shell.proxy.ServerUpstream
import kotobaya.db.app.AppDatabase
import okhttp3.mockwebserver.MockWebServer

/**
 * LF-A2 acceptance: the `cached` class through [LocalApi] (SHELL.md 3.1 step 6, rules K-1 … K-4) over the real
 * `response_cache` table. Background fetches run on a queue the test drains explicitly, so "the request did not wait"
 * is visible: the answer exists before anything was fetched.
 */
class CachedDispatchTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AppDatabase.Schema.create(it) }
    private val db = AppDatabase(driver)
    private val store = SqlResponseCacheStore(db)

    /** Background work, run only by [drain]. */
    private val queued = ArrayDeque<Runnable>()
    private val background = CoroutineScope(Executor { queued.addLast(it) }.asCoroutineDispatcher())

    private fun drain() {
        var n = 0
        while (queued.isNotEmpty()) {
            queued.removeFirst().run()
            check(++n < 10_000) { "background work does not settle" }
        }
    }

    private var now = 1_795_168_800_000L
    private var online = true
    private var pending = false
    private val events = ArrayList<CacheUpdate>()

    /** What the server answers, in order; each call is recorded as `operationId path?query [if-none-match]`. */
    private val answers = ArrayDeque<ShellResponse>()
    private val calls = ArrayList<String>()
    private val upstream = Upstream { req, op, _ ->
        val q = CacheKey.canonicalQuery(req.query)
        calls += "${op.operationId} ${req.path}${if (q.isEmpty()) "" else "?$q"}" + (req.header("if-none-match")?.let { " inm=$it" } ?: "")
        answers.removeFirstOrNull() ?: ShellResponse(500, mapOf("content-type" to "application/problem+json"), "{}")
    }

    private val gate = AlwaysOpenGate()

    private fun cache(up: Upstream = upstream) = ResponseCache(
        store = store,
        upstream = up,
        scope = background,
        isOnline = { online },
        hasPendingOps = { pending },
        onUpdated = { events += it },
        newRequestId = { "bg" },
        nowMs = { now },
    )

    private val cache = cache()
    private fun api(up: Upstream = upstream, c: ResponseCache? = cache) =
        LocalApi(emptyList(), gate, up, newRequestId = { "fresh" }, cache = c)

    private val api = api()
    private val mock = MockWebServer()

    @AfterTest
    fun stop() {
        runCatching { mock.shutdown() }
    }

    private fun get(path: String, query: String = "", a: LocalApi = api) =
        runBlocking { a.handle(ShellRequest.fromRaw("GET", path, query, emptyMap(), null)) }

    private fun json(body: String, etag: String? = null) =
        ShellResponse(200, buildMap { put("content-type", "application/json"); etag?.let { put("etag", it) } }, body)

    private fun seed(op: String, path: String, query: String, body: String, fetchedAt: Long = now, etag: String? = null, stale: Boolean = false) {
        val key = CacheKey.of(op, path, query)
        store.put(CacheEntry(key, op, path, query, 200, "application/json", body, body.length.toLong(), etag, fetchedAt, fetchedAt, stale))
    }

    private fun code(r: ShellResponse): String? = Regex("\"code\":\"([a-z_]+)\"").find(r.body.orEmpty())?.groupValues?.get(1)

    @Test
    fun cachedHitAnswersWithoutTheUpstream() {
        seed("getStatsForecast", "/api/v1/stats/forecast", "days=30", """{"days":[1]}""", fetchedAt = now - 10_000)
        val r = get("/api/v1/stats/forecast", "days=30")
        assertEquals(200, r.status)
        assertEquals("""{"days":[1]}""", r.body)
        assertEquals("application/json", r.headers["content-type"])
        assertEquals("1", r.headers["kotobaya-local"])
        assertEquals("fresh", r.headers["x-request-id"])
        assertEquals("2026-11-20T09:59:50.000Z", r.headers["kotobaya-cached-at"])
        assertNull(r.headers["retry-after"])
        drain()
        assertEquals(emptyList(), calls, "a fresh entry is not revalidated")
        assertEquals(now, store.get(CacheKey.of("getStatsForecast", "/api/v1/stats/forecast", "days=30"))?.lastUsedAt)
    }

    @Test
    fun revalidationUpdatesTheRowAndEmitsExactlyOneCacheUpdated() {
        seed("listSentences", "/api/v1/sentences", "cursor=&limit=20&q=%E7%8C%AB", """{"items":[]}""", fetchedAt = now - 61_000, etag = "\"a\"")
        answers += json("""{"items":[1]}""", etag = "\"b\"")
        val r = get("/api/v1/sentences", "q=%e7%8c%ab&limit=20&cursor=")
        assertEquals("""{"items":[]}""", r.body, "the hit is the old body, at once")
        assertEquals(emptyList(), calls, "nothing was fetched before the answer")
        drain()
        assertEquals(listOf("listSentences /api/v1/sentences?cursor=&limit=20&q=%E7%8C%AB inm=\"a\""), calls)
        assertEquals(listOf(CacheUpdate("listSentences", "/api/v1/sentences", "cursor=&limit=20&q=%E7%8C%AB", 200)), events)
        val row = assertNotNull(store.get(CacheKey.of("listSentences", "/api/v1/sentences", "cursor=&limit=20&q=%E7%8C%AB")))
        assertEquals("""{"items":[1]}""", row.body)
        assertEquals("\"b\"", row.etag)
        assertEquals(now, row.fetchedAt)
        assertFalse(row.stale)
        assertEquals("""{"items":[1]}""", get("/api/v1/sentences", "cursor=&limit=20&q=%E7%8C%AB").body)
    }

    @Test
    fun identicalBodyEmitsNoneAndOnlyConfirmsTheRow() {
        seed("getStatsKnownWords", "/api/v1/stats/known-words", "", """{"n":1}""", fetchedAt = now - 120_000, stale = true)
        answers += json("""{"n":1}""")
        get("/api/v1/stats/known-words")
        drain()
        assertEquals(1, calls.size)
        assertEquals(emptyList(), events)
        val row = assertNotNull(store.get(CacheKey.of("getStatsKnownWords", "/api/v1/stats/known-words", "")))
        assertEquals(now, row.fetchedAt)
        assertFalse(row.stale)
        // 304 likewise
        now += 61_000
        answers += ShellResponse(304)
        get("/api/v1/stats/known-words")
        drain()
        assertEquals(emptyList(), events)
        assertEquals(now, store.get(CacheKey.of("getStatsKnownWords", "/api/v1/stats/known-words", ""))?.fetchedAt)
    }

    @Test
    fun aRevalidationWithinFiveSecondsOfTheLastFetchIsNotStarted() {
        seed("getStatsForecast", "/api/v1/stats/forecast", "", "{}", fetchedAt = now - 61_000)
        answers += json("{}")
        get("/api/v1/stats/forecast")
        drain()
        store.markStale(listOf("getStatsForecast"))
        now += 4_000
        get("/api/v1/stats/forecast")
        drain()
        assertEquals(1, calls.size)
        now += 1_000
        answers += json("{}")
        get("/api/v1/stats/forecast")
        drain()
        assertEquals(2, calls.size)
    }

    @Test
    fun aClockSetBackRevalidates() {
        seed("getStatsCalibration", "/api/v1/stats/calibration", "", "{}", fetchedAt = now + 3_600_000)
        answers += json("{}")
        val r = get("/api/v1/stats/calibration")
        assertEquals("2026-11-20T11:00:00.000Z", r.headers["kotobaya-cached-at"])
        drain()
        assertEquals(1, calls.size)
    }

    @Test
    fun anEntryFetchedWhileOpsArePendingStaysStale() {
        pending = true
        answers += json("""{"x":1}""")
        get("/api/v1/deck-lists")
        drain()
        assertEquals(true, store.get(CacheKey.of("listDeckLists", "/api/v1/deck-lists", ""))?.stale)
    }

    @Test
    fun missIsImmediate503ShellUncachedThenOneBackgroundFetchStoresAndEmits() {
        answers += json("""{"coverage":0.5}""")
        val path = "/api/v1/decks/01931f40-2000-7000-8000-000000000d01/coverage"
        val r = get(path)
        assertEquals(503, r.status)
        assertEquals("shell_uncached", code(r))
        assertEquals("application/problem+json", r.headers["content-type"])
        assertEquals("1", r.headers["kotobaya-local"])
        assertNull(r.headers["retry-after"])
        assertNull(r.headers["kotobaya-cached-at"])
        assertEquals(emptyList(), calls)
        // a second miss while the first fetch is in flight starts no second one
        get(path)
        drain()
        assertEquals(listOf("getDeckCoverage $path"), calls)
        assertEquals(listOf(CacheUpdate("getDeckCoverage", path, "", 200)), events)
        val hit = get(path)
        assertEquals(200, hit.status)
        assertEquals("""{"coverage":0.5}""", hit.body)
        assertEquals("2026-11-20T10:00:00.000Z", hit.headers["kotobaya-cached-at"])
    }

    @Test
    fun aMissEndingInAFinalRefusalEmitsItsStatusAndStoresNothing() {
        for ((status, emits) in listOf(404 to true, 422 to true, 400 to true, 403 to true, 500 to false, 429 to false, 401 to false)) {
            events.clear()
            answers += ShellResponse(status, mapOf("content-type" to "application/problem+json"), "{}")
            get("/api/v1/media/m$status/coverage")
            drain()
            assertEquals(if (emits) listOf(CacheUpdate("getMediaCoverage", "/api/v1/media/m$status/coverage", "", status)) else emptyList(), events, "status $status")
            assertNull(store.get(CacheKey.of("getMediaCoverage", "/api/v1/media/m$status/coverage", "")))
        }
    }

    @Test
    fun aRevalidationAnswered404DeletesTheEntryAndEmits() {
        seed("getSentence", "/api/v1/sentences/s1", "", "{}", fetchedAt = now - 61_000)
        answers += ShellResponse(404, mapOf("content-type" to "application/problem+json"), "{}")
        assertEquals(200, get("/api/v1/sentences/s1").status)
        drain()
        assertEquals(listOf(CacheUpdate("getSentence", "/api/v1/sentences/s1", "", 404)), events)
        assertEquals("shell_uncached", code(get("/api/v1/sentences/s1")))
    }

    @Test
    fun aRevalidationThatFailsKeepsServingTheStoredAnswer() {
        seed("listGrammarExamples", "/api/v1/grammar/g/examples", "", """{"a":1}""", fetchedAt = now - 61_000)
        answers += ShellResponse(503, mapOf("content-type" to "application/problem+json"), "{}")
        get("/api/v1/grammar/g/examples")
        drain()
        answers += ShellResponse(200, mapOf("content-type" to "text/html"), "<html>")
        now += 10_000
        get("/api/v1/grammar/g/examples")
        drain()
        assertEquals(2, calls.size)
        assertEquals(emptyList(), events)
        assertEquals("""{"a":1}""", get("/api/v1/grammar/g/examples").body)
    }

    @Test
    fun offlineCachedHitStillAnswersMissIsUncachedAndRemoteIsShellOfflineWithNoNetworkAttempt() {
        online = false
        mock.start()
        val noSync = object : ProxySync {
            override fun requestPull(seq: Long) = error("no pull while offline")
            override fun pullCursor(): Long = 0
        }
        val server = ServerUpstream({ mock.url("/api/v1").toString() }, { "kbd_" + "t".repeat(40) }, { online }, noSync)
        val c = cache(server)
        val a = api(server, c)
        seed("listParseOverrides", "/api/v1/parse-overrides", "", """{"items":[]}""", fetchedAt = now - 3_600_000, stale = true)

        val hit = get("/api/v1/parse-overrides", a = a)
        assertEquals(200, hit.status)
        assertEquals("""{"items":[]}""", hit.body)
        val miss = get("/api/v1/stats/forecast", a = a)
        assertEquals(503, miss.status)
        assertEquals("shell_uncached", code(miss))
        val remote = get("/api/v1/jobs", a = a)
        assertEquals(503, remote.status)
        assertEquals("shell_offline", code(remote))
        drain()
        assertEquals(0, mock.requestCount, "no network attempt")
        assertEquals(emptyList(), events)
        // warm pass while offline: nothing
        c.onCycleSucceeded()
        drain()
        assertEquals(0, mock.requestCount)
    }

    @Test
    fun withoutACacheEveryCachedOperationIsUncachedAndNothingIsFetched() {
        val a = api(c = null)
        val r = get("/api/v1/deck-lists", a = a)
        assertEquals(503, r.status)
        assertEquals("shell_uncached", code(r))
        drain()
        assertEquals(emptyList(), calls)
    }

    @Test
    fun cachedIsNotBehindTheReadinessOrRebuildGate() {
        gate.rebuilding = true
        seed("listDeckLists", "/api/v1/deck-lists", "", "[]")
        assertEquals(200, get("/api/v1/deck-lists").status)
        val closed = LocalApi(emptyList(), AlwaysOpenGate(ready = kotlinx.coroutines.CompletableDeferred()), upstream, newRequestId = { "x" }, readyTimeoutMs = 10_000, cache = cache)
        val started = System.nanoTime()
        assertEquals(200, get("/api/v1/deck-lists", a = closed).status)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun replicaChangesMarkOnlyDependentEntriesStale() {
        seed("listDeckLists", "/api/v1/deck-lists", "", "[]")
        seed("getStatsCalibration", "/api/v1/stats/calibration", "", "{}")
        seed("listParseOverrides", "/api/v1/parse-overrides", "", "{}")
        cache.onReplicaChanged(setOf("review_log"))
        fun stale(op: String, path: String) = store.get(CacheKey.of(op, path, ""))?.stale
        assertEquals(false, stale("listDeckLists", "/api/v1/deck-lists"))
        assertEquals(true, stale("getStatsCalibration", "/api/v1/stats/calibration"))
        cache.onReplicaChanged()
        assertEquals(true, stale("listDeckLists", "/api/v1/deck-lists"))
        assertEquals(false, stale("listParseOverrides", "/api/v1/parse-overrides"), "parse_override is not on the device")
        cache.onAllStale()
        assertEquals(true, stale("listParseOverrides", "/api/v1/parse-overrides"))
    }

    @Test
    fun aProxiedParseOverrideWriteMarksItsDependentsStale() {
        seed("listParseOverrides", "/api/v1/parse-overrides", "", "{}")
        seed("getSentence", "/api/v1/sentences/s", "", "{}")
        seed("listDeckLists", "/api/v1/deck-lists", "", "[]")
        answers += ShellResponse(204)
        runBlocking { api.handle(ShellRequest.fromRaw("DELETE", "/api/v1/parse-overrides/o1", "", emptyMap(), null)) }
        assertEquals(true, store.get(CacheKey.of("listParseOverrides", "/api/v1/parse-overrides", ""))?.stale)
        assertEquals(true, store.get(CacheKey.of("getSentence", "/api/v1/sentences/s", ""))?.stale)
        assertEquals(false, store.get(CacheKey.of("listDeckLists", "/api/v1/deck-lists", ""))?.stale)
    }

    @Test
    fun unpairEmptiesTheCache() {
        seed("listDeckLists", "/api/v1/deck-lists", "", "[]")
        val a = LocalApi(
            listOf(LocalRoute("unpairShell", "POST", "/shell/v1/unpair") { ShellResponse.noContent() }),
            gate, upstream, newRequestId = { "x" }, cache = cache,
        )
        runBlocking { a.handle(ShellRequest.fromRaw("POST", "/shell/v1/unpair", "", mapOf("content-type" to "application/json"), "{}")) }
        assertNull(store.get(CacheKey.of("listDeckLists", "/api/v1/deck-lists", "")))
    }

    @Test
    fun theWarmPassFetchesSeedsDeckCoverageAndStaleEntries() {
        db.transaction {
            driver.execute(null, "INSERT INTO deck (id, name, type, created_at, updated_at) VALUES ('dl', 'L', 'list', 1, 30), ('dm', 'M', 'media', 1, 20), ('du', 'U', 'user', 1, 40)", 0)
            driver.execute(null, "INSERT INTO deck (id, name, type, created_at, updated_at, deleted_at) VALUES ('dx', 'X', 'list', 1, 50, 60)", 0)
        }
        seed("getStatsForecast", "/api/v1/stats/forecast", "", "{}") // fresh: skipped
        seed("listSentences", "/api/v1/sentences", "limit=5", "{}", stale = true)
        repeat(20) { answers += json("{}") }
        runBlocking { cache.warm() }
        drain()
        assertEquals(
            setOf(
                "listDeckLists /api/v1/deck-lists",
                "listParseOverrides /api/v1/parse-overrides",
                "getStatsKnownWords /api/v1/stats/known-words",
                "getStatsCalibration /api/v1/stats/calibration",
                "getDeckCoverage /api/v1/decks/dl/coverage",
                "getDeckCoverage /api/v1/decks/dm/coverage",
                "listSentences /api/v1/sentences?limit=5",
            ),
            calls.toSet(),
        )
        assertEquals(7, calls.size)
    }

    @Test
    fun evictionDeletesLeastRecentlyUsedFirstAndKeepsTheNewEntry() {
        val big = "\"" + "x".repeat(4 * 1024 * 1024 - 2) + "\""
        for (i in 1..4) {
            store.put(CacheEntry(CacheKey.of("getSentence", "/api/v1/sentences/s$i", ""), "getSentence", "/api/v1/sentences/s$i", "", 200, "application/json", big, big.length.toLong(), null, now, now - 100 + i, false))
        }
        answers += json("""{"new":true}""")
        get("/api/v1/sentences/s5")
        drain()
        assertNull(store.get(CacheKey.of("getSentence", "/api/v1/sentences/s1", "")), "least recently used evicted")
        assertNotNull(store.get(CacheKey.of("getSentence", "/api/v1/sentences/s2", "")))
        assertNotNull(store.get(CacheKey.of("getSentence", "/api/v1/sentences/s5", "")))
        assertTrue(store.totalSize() <= ResponseCache.MAX_TOTAL_BYTES)
    }

    @Test
    fun anAnswerOver4MiBIsNotStored() {
        answers += json("\"" + "x".repeat(4 * 1024 * 1024) + "\"")
        get("/api/v1/sentences/huge")
        drain()
        assertNull(store.get(CacheKey.of("getSentence", "/api/v1/sentences/huge", "")))
        assertEquals(emptyList(), events)
    }

    @Test
    fun canonicalQueryFollowsRuleK1() {
        assertEquals("cursor=&limit=20&q=%E7%8C%AB", CacheKey.canonicalQuery("limit=20&cursor=&q=%E7%8C%AB"))
        assertEquals("cursor=&limit=20&q=%E7%8C%AB", CacheKey.canonicalQuery("q=%e7%8c%ab&limit=20&cursor="))
        assertEquals("limit=50", CacheKey.canonicalQuery("limit=50"))
        assertEquals("", CacheKey.canonicalQuery(""))
        assertEquals("a=&b=2&k=1&k=2", CacheKey.canonicalQuery("k=2&&b=2&k=1&a"))
        assertEquals("q=a%2Bb%20c~-._", CacheKey.canonicalQuery("q=a+b%20c~-._"))
        // code points, not UTF-16 units: U+FF5E sorts before U+1F600
        assertEquals("q=%EF%BD%9E&q=%F0%9F%98%80", CacheKey.canonicalQuery("q=%F0%9F%98%80&q=%EF%BD%9E"))
        assertEquals("listSentences\n/api/v1/sentences\ncursor=&limit=20", CacheKey.of("listSentences", "/api/v1/sentences", "cursor=&limit=20"))
    }

    @Test
    fun theInvalidationTableCoversExactlyTheCachedOperations() {
        val cached = ApiOperations.all.filter { it.opClass == OpClass.CACHED }.map { it.operationId }.toSet()
        assertEquals(cached, ResponseCache.STALE_WHEN.keys)
        assertTrue(ResponseCache.SEED_PATHS.all { (op, path) -> ApiOperations.match("GET", path)?.first?.operationId == op })
    }
}
