package kotobaya.android.shell.proxy

import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotobaya.android.shell.api.ApiOperation
import kotobaya.android.shell.api.ApiOperations
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer

/** SHELL-A5 acceptance: rules P-1 … P-3 of SHELL.md (as amended by C-31: no pre-push, no hold) against a MockWebServer. */
class ServerUpstreamTest {
    private val server = MockWebServer().apply { start() }
    private val token = "kbd_" + "t".repeat(40)
    private var online = true

    /** Records the pulls requested; the cursor never moves by itself (a pull that never finishes). */
    private class FakeSync : ProxySync {
        var cursor = 0L
        val pullRequests = ArrayList<Long>()

        override fun requestPull(seq: Long) {
            pullRequests += seq
        }

        override fun pullCursor(): Long = cursor
    }

    private val sync = FakeSync()

    private fun upstream(
        callBudgetMs: Long = 12_000,
        tokenSource: () -> String? = { token },
        proxySync: ProxySync = sync,
    ) = ServerUpstream(
        baseUrl = { server.url("/api/v1").toString() },
        token = tokenSource,
        isOnline = { online },
        sync = proxySync,
        callBudgetMs = callBudgetMs,
    )

    private fun op(id: String): ApiOperation = ApiOperations.byId.getValue(id)

    private fun get(path: String, query: Map<String, List<String>> = emptyMap(), headers: Map<String, String> = emptyMap()) =
        ShellRequest("GET", path, query, headers)

    private fun takeRequest(): RecordedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "no request reached the server" }

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    @Test
    fun bearerIsAddedAndCookieCsrfAndTheSpasAuthorizationAreStripped() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setHeader("Content-Type", "application/json").setHeader("X-Request-Id", "srv-1"))
        val res = upstream().forward(
            get(
                "/api/v1/stats/summary",
                query = mapOf("from" to listOf("2024-01-01"), "q" to listOf("a+b c&d")),
                headers = mapOf(
                    "Cookie" to "kotobaya_session=abc",
                    "Authorization" to "Bearer spa-token",
                    "X-Kotobaya-Csrf" to "csrf",
                    "Accept" to "application/json",
                    "X-Request-Id" to "req-1",
                    "X-Other" to "nope",
                ),
            ),
            op("getStatsSummary"), "req-1",
        )
        val r = takeRequest()
        assertEquals("Bearer $token", r.getHeader("Authorization"))
        assertNull(r.getHeader("Cookie"))
        assertNull(r.getHeader("X-Kotobaya-Csrf"))
        assertNull(r.getHeader("X-Other"))
        assertEquals("req-1", r.getHeader("X-Request-Id"))
        assertEquals("application/json", r.getHeader("Accept"))
        assertEquals("/api/v1/stats/summary", r.requestUrl?.encodedPath)
        assertEquals("2024-01-01", r.requestUrl?.queryParameter("from"))
        assertEquals("a+b c&d", r.requestUrl?.queryParameter("q"))
        assertEquals(200, res.status)
        assertEquals("""{"ok":true}""", res.body)
        assertEquals("srv-1", res.headers["x-request-id"])
        assertNull(res.headers["kotobaya-local"])
        // the token never comes back in any header
        assertFalse(res.headers.values.any { token in it })
    }

    @Test
    fun problemsPassThroughByteIdentical401And403And422WithRetryAfter() = runBlocking {
        val bodies = mapOf(
            401 to """{"type":"urn:kotobaya:problem:unauthenticated","title":"Authentication required","status":401,"code":"unauthenticated","request_id":"x"}""",
            403 to """{ "type": "urn:kotobaya:problem:insufficient_scope", "status": 403, "code": "insufficient_scope", "request_id": "y" }""",
            422 to "{\"type\":\"urn:kotobaya:problem:validation_failed\",\"status\":422,\"code\":\"validation_failed\",\"errors\":[{\"pointer\":\"/name\",\"code\":\"required\",\"detail\":\"名前\"}],\"request_id\":\"z\"}\n",
        )
        for ((status, body) in bodies) {
            server.enqueue(
                MockResponse().setResponseCode(status).setBody(body)
                    .setHeader("Content-Type", "application/problem+json")
                    .setHeader("Retry-After", "7")
                    .setHeader("Set-Cookie", "x=1"),
            )
            val res = upstream().forward(ShellRequest("POST", "/api/v1/presets", body = "{}", headers = mapOf("content-type" to "application/json")), op("createPreset"), "r$status")
            takeRequest()
            assertEquals(status, res.status)
            assertEquals(body, res.body)
            assertEquals("application/problem+json", res.headers["content-type"])
            assertEquals("7", res.headers["retry-after"])
            assertNull(res.headers["kotobaya-local"])
            assertNull(res.headers["set-cookie"])
        }
    }

    @Test
    fun remoteIsShellOfflineWithNoNetworkAttemptWhenOffline() = runBlocking {
        online = false
        val res = upstream().forward(get("/api/v1/stats/summary"), op("getStatsSummary"), "r1")
        assertEquals(503, res.status)
        assertTrue(res.body!!.contains("\"code\":\"shell_offline\""))
        assertEquals("1", res.headers["kotobaya-local"])
        assertEquals("r1", res.headers["x-request-id"])
        assertEquals(0, server.requestCount)
        assertEquals(emptyList(), sync.pullRequests)
    }

    @Test
    fun connectFailureAnswers503ShellOffline() = runBlocking {
        server.shutdown()
        val res = upstream().forward(get("/api/v1/stats/summary"), op("getStatsSummary"), "r1")
        assertEquals(503, res.status)
        assertTrue(res.body!!.contains("shell_offline"))
    }

    @Test
    fun noCompleteAnswerWithinTheCallBudgetIs503ShellOffline() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val res = upstream(callBudgetMs = 300).forward(get("/api/v1/stats/summary"), op("getStatsSummary"), "r1")
        assertEquals(503, res.status)
        assertTrue(res.body!!.contains("shell_offline"))
        assertEquals(1, server.requestCount) // no retry
    }

    @Test
    fun nothingIsPushedOrPulledBeforeAForward() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").setHeader("Content-Type", "application/json"))
        upstream().forward(get("/api/v1/jobs"), op("listJobs"), "r1")
        takeRequest()
        assertEquals(emptyList(), sync.pullRequests)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun remoteWriteReturnsWithoutWaitingForTheCursorAndCarriesShellStale() = runBlocking {
        sync.cursor = 48213
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"id":"d"}""").setHeader("Content-Type", "application/json").setHeader("Kotobaya-Seq", "48214"),
        )
        val started = System.nanoTime()
        val res = upstream().forward(
            ShellRequest("POST", "/api/v1/decks", body = """{"name":"N5","type":"list","list_key":"jlpt-n5"}""", headers = mapOf("content-type" to "application/json")),
            op("createDeck"), "r1",
        )
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(201, res.status)
        assertEquals("48214", res.headers["kotobaya-seq"])
        assertEquals("1", res.headers["kotobaya-shell-stale"])
        assertEquals(listOf(48214L), sync.pullRequests, "one pull scheduled, not awaited")
        assertEquals(48213, sync.cursor, "the answer did not wait for the cursor")
        assertTrue(tookMs < 2_000, "answered in $tookMs ms")
    }

    @Test
    fun aCursorAlreadyPastTheSeqAnswersWithoutStaleOrPull() = runBlocking {
        sync.cursor = 50
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}").setHeader("Content-Type", "application/json").setHeader("Kotobaya-Seq", "42"))
        val res = upstream().forward(ShellRequest("PUT", "/api/v1/parse-overrides/x", body = "{}", headers = mapOf("content-type" to "application/json")), op("putParseOverride"), "r1")
        assertEquals(200, res.status)
        assertNull(res.headers["kotobaya-shell-stale"])
        assertEquals(emptyList(), sync.pullRequests)
    }

    @Test
    fun aNon2xxOrNoSeqIsNeitherStaleNorPulls() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("{}").setHeader("Content-Type", "application/problem+json").setHeader("Kotobaya-Seq", "9"))
        val res = upstream().forward(ShellRequest("DELETE", "/api/v1/parse-overrides/x"), op("deleteParseOverride"), "r1")
        assertEquals(409, res.status)
        assertNull(res.headers["kotobaya-shell-stale"])
        server.enqueue(MockResponse().setResponseCode(204))
        val noSeq = upstream().forward(ShellRequest("DELETE", "/api/v1/parse-overrides/x"), op("deleteParseOverride"), "r2")
        assertEquals(204, noSeq.status)
        assertNull(noSeq.headers["kotobaya-shell-stale"])
        assertEquals(emptyList(), sync.pullRequests)
    }

    @Test
    fun aCachedFetchSendsIfNoneMatchAndGetsTheEtagButAProxyCallDoesNot() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"v2\""))
        val cached = upstream().forward(get("/api/v1/stats/forecast", headers = mapOf("if-none-match" to "\"v1\"")), op("getStatsForecast"), "r1")
        assertEquals("\"v1\"", takeRequest().getHeader("If-None-Match"))
        assertEquals(304, cached.status)
        assertEquals("\"v2\"", cached.headers["etag"])
        server.enqueue(MockResponse().setBody("[]").setHeader("Content-Type", "application/json").setHeader("ETag", "\"j\""))
        val proxied = upstream().forward(get("/api/v1/jobs", headers = mapOf("if-none-match" to "\"v1\"")), op("listJobs"), "r2")
        assertNull(takeRequest().getHeader("If-None-Match"))
        assertNull(proxied.headers["etag"])
    }

    @Test
    fun redirectsAreFollowedWithoutTheAuthorizationHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/api/v1/stats/summary2"))
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Type", "application/json"))
        val res = upstream().forward(get("/api/v1/stats/summary"), op("getStatsSummary"), "r1")
        assertEquals(200, res.status)
        assertEquals("Bearer $token", takeRequest().getHeader("Authorization"))
        val second = takeRequest()
        assertEquals("/api/v1/stats/summary2", second.path)
        assertNull(second.getHeader("Authorization"))
    }

    @Test
    fun aLargeOrNonTextAnswerIs415ShellUnsupported() = runBlocking {
        val big = Buffer().apply { write(ByteArray((ServerUpstream.MAX_RESPONSE_BYTES + 1).toInt()) { 'a'.code.toByte() }) }
        server.enqueue(MockResponse().setBody(big).setHeader("Content-Type", "text/plain"))
        val r1 = upstream().forward(get("/api/v1/export"), op("exportAll"), "r1")
        assertEquals(415, r1.status)
        assertTrue(r1.body!!.contains("shell_unsupported"))
        server.enqueue(MockResponse().setBody("PK\u0003\u0004").setHeader("Content-Type", "application/zip"))
        val r2 = upstream().forward(get("/api/v1/export"), op("exportAll"), "r2")
        assertEquals(415, r2.status)
        assertEquals("1", r2.headers["kotobaya-local"])
        // exactly 8 MiB of text is allowed
        server.enqueue(MockResponse().setBody(Buffer().apply { write(ByteArray(ServerUpstream.MAX_RESPONSE_BYTES.toInt()) { 'a'.code.toByte() }) }).setHeader("Content-Type", "text/plain; charset=utf-8"))
        assertEquals(200, upstream().forward(get("/api/v1/export"), op("exportAll"), "r3").status)
    }

    @Test
    fun dotSegmentsAreNeverForwarded() = runBlocking {
        for (path in listOf("/api/v1/presets/..", "/api/v1/presets/%2e%2E", "/api/v1/presets/.", "/api/v1/presets/a%2F..%2F..")) {
            val res = upstream().forward(ShellRequest("DELETE", path), op("deletePreset"), "r1")
            assertEquals(404, res.status, path)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aMissingResponseRequestIdFallsBackToTheRequests() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Type", "application/json"))
        val res: ShellResponse = upstream().forward(get("/api/v1/stats/summary"), op("getStatsSummary"), "mine")
        assertEquals("mine", res.headers["x-request-id"])
    }
}
