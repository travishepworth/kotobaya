package kotobaya.android.shell.proxy

import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
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

/** SHELL-A5 acceptance: rules P-1 … P-3 of SHELL.md against a MockWebServer. */
class ServerUpstreamTest {
    private val server = MockWebServer().apply { start() }
    private val token = "kbd_" + "t".repeat(40)
    private var online = true

    /** Records the order of sync calls against the number of requests the server had seen. */
    private class FakeSync(private val server: MockWebServer) : ProxySync {
        var cursor = 0L
        val log = ArrayList<String>()
        var onPush: suspend () -> Unit = {}
        var onPull: suspend () -> Unit = {}

        override suspend fun push() {
            log += "push@${server.requestCount}"
            onPush()
        }

        override suspend fun pull() {
            log += "pull@${server.requestCount}"
            onPull()
        }

        override fun pullCursor(): Long = cursor
    }

    private val sync = FakeSync(server)

    private fun upstream(
        pushBudgetMs: Long = 5_000,
        pullBudgetMs: Long = 10_000,
        callBudgetMs: Long = 12_000,
        tokenSource: () -> String? = { token },
        proxySync: ProxySync = sync,
    ) = ServerUpstream(
        baseUrl = { server.url("/api/v1").toString() },
        token = tokenSource,
        isOnline = { online },
        sync = proxySync,
        pushBudgetMs = pushBudgetMs,
        callBudgetMs = callBudgetMs,
        pullBudgetMs = pullBudgetMs,
        pullRetryDelayMs = 10,
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
    fun offlineAnswers503ShellOfflineWithZeroRequestsAndNoPush() = runBlocking {
        online = false
        val res = upstream().forward(get("/api/v1/stats/summary"), op("getStatsSummary"), "r1")
        assertEquals(503, res.status)
        assertTrue(res.body!!.contains("\"code\":\"shell_offline\""))
        assertEquals("1", res.headers["kotobaya-local"])
        assertEquals("r1", res.headers["x-request-id"])
        assertEquals(0, server.requestCount)
        assertEquals(emptyList(), sync.log)
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
    fun pendingOpsArePushedBeforeAForwardedGet() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").setHeader("Content-Type", "application/json"))
        upstream().forward(get("/api/v1/stats/summary"), op("getStatsSummary"), "r1")
        takeRequest()
        assertEquals(listOf("push@0"), sync.log)
    }

    @Test
    fun aFailingOrSlowPushDoesNotStopTheForward() = runBlocking {
        sync.onPush = { error("push failed") }
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Type", "application/json"))
        assertEquals(200, upstream().forward(get("/api/v1/stats/summary"), op("getStatsSummary"), "r1").status)
        sync.onPush = { delay(60_000) }
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Type", "application/json"))
        val started = System.nanoTime()
        assertEquals(200, upstream(pushBudgetMs = 100).forward(get("/api/v1/stats/summary"), op("getStatsSummary"), "r2").status)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun aMutatingReplyWithKotobayaSeqReturnsOnlyAfterThePullCursorPassedIt() = runBlocking {
        sync.cursor = 40
        var pulls = 0
        sync.onPull = { pulls++; sync.cursor = if (pulls < 3) 41 else 42 }
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"p"}""").setHeader("Content-Type", "application/json").setHeader("Kotobaya-Seq", "42"))
        val res = upstream().forward(ShellRequest("POST", "/api/v1/presets", body = "{}", headers = mapOf("content-type" to "application/json")), op("createPreset"), "r1")
        assertEquals(201, res.status)
        assertTrue(sync.cursor >= 42)
        assertEquals(3, pulls)
        assertEquals("42", res.headers["kotobaya-seq"])
        assertNull(res.headers["kotobaya-shell-stale"])
        assertEquals(listOf("push@0", "pull@1", "pull@1", "pull@1"), sync.log)
    }

    @Test
    fun aCursorAlreadyPastTheSeqAnswersWithoutPulling() = runBlocking {
        sync.cursor = 50
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}").setHeader("Content-Type", "application/json").setHeader("Kotobaya-Seq", "42"))
        val res = upstream().forward(ShellRequest("PATCH", "/api/v1/presets/x", body = "{}", headers = mapOf("content-type" to "application/json")), op("updatePreset"), "r1")
        assertEquals(200, res.status)
        assertEquals(listOf("push@0"), sync.log)
    }

    @Test
    fun aPullThatNeverReachesTheSeqSetsTheStaleHeaderAfterTheBudget() = runBlocking {
        sync.cursor = 1
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Kotobaya-Seq", "9"))
        val res = upstream(pullBudgetMs = 200).forward(ShellRequest("DELETE", "/api/v1/presets/x"), op("deletePreset"), "r1")
        assertEquals(204, res.status)
        assertNull(res.body)
        assertEquals("1", res.headers["kotobaya-shell-stale"])
        assertEquals("9", res.headers["kotobaya-seq"])
    }

    @Test
    fun aNon2xxWithKotobayaSeqIsNotHeld() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("{}").setHeader("Content-Type", "application/problem+json").setHeader("Kotobaya-Seq", "9"))
        val res = upstream().forward(ShellRequest("DELETE", "/api/v1/presets/x"), op("deletePreset"), "r1")
        assertEquals(409, res.status)
        assertNull(res.headers["kotobaya-shell-stale"])
        assertEquals(listOf("push@0"), sync.log)
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
