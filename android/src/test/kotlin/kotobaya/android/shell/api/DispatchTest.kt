package kotobaya.android.shell.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotobaya.contracts.sync.ClockGuardResult
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.DefaultCanonicalJson

/** A gate that is paired, ready and not rebuilding unless told otherwise. */
class AlwaysOpenGate(
    var paired: Boolean = true,
    var rebuilding: Boolean = false,
    val ready: CompletableDeferred<Unit> = CompletableDeferred(Unit),
) : ShellGate {
    override fun isPaired(): Boolean = paired

    override fun isRebuildPending(): Boolean = rebuilding

    override suspend fun awaitReady() = ready.await()
}

/** SHELL-A2 acceptance: the dispatcher's check order (SHELL.md 3.1), problems, and validation helpers. */
class DispatchTest {
    private val gate = AlwaysOpenGate()
    private val ok: suspend (LocalCall) -> ShellResponse = { ShellResponse.json(JsonObject(mapOf("ok" to JsonString(it.operation.operationId)))) }

    private val testRoutes = listOf(
        LocalRoute("getShellInfo", "GET", "/shell/v1/info", ok),
        LocalRoute("getMeta", "GET", "/api/v1/meta", ok),
        LocalRoute("listHotWords", "GET", "/api/v1/words/hot") { call ->
            val errors = FieldErrors()
            val limit = call.query("limit").int("limit", 1..200, 20, errors)
            errors.throwIfAny()
            ShellResponse.json(JsonObject(mapOf("limit" to JsonNumber(limit.toDouble()))))
        },
        LocalRoute("updateSettings", "PATCH", "/api/v1/settings") { call ->
            ShellResponse.json(call.body(setOf("values")))
        },
        LocalRoute("getDictEntry", "GET", "/api/v1/dict/entries/{seq}") { call ->
            if (call.path("seq") == "1") ShellResponse.json(JsonObject(mapOf("seq" to JsonNumber(1.0)))) else Fallthrough
        },
        LocalRoute("getNote", "GET", "/api/v1/notes/{note_id}") { error("boom") },
        LocalRoute("deleteNote", "DELETE", "/api/v1/notes/{note_id}") { throw ProblemException(Problems.gone("deleted")) },
    )

    private val forwarded = ArrayList<String>()
    private val recordingUpstream = Upstream { _, op, _ ->
        forwarded.add(op.operationId)
        ShellResponse(200, mapOf("content-type" to "application/json", "x-request-id" to "server"), "{\"from\":\"server\"}")
    }

    private fun api(upstream: Upstream = OfflineUpstream, readyTimeoutMs: Long = 20_000) =
        LocalApi(testRoutes, gate, upstream, newRequestId = { "fresh-id" }, readyTimeoutMs = readyTimeoutMs)

    private fun get(path: String, query: String = "", headers: Map<String, String> = emptyMap()) =
        ShellRequest.fromRaw("GET", path, query, headers, null)

    private fun send(method: String, path: String, body: String?, contentType: String? = "application/json") =
        ShellRequest.fromRaw(method, path, "", contentType?.let { mapOf("content-type" to it) } ?: emptyMap(), body)

    private fun run(req: ShellRequest, a: LocalApi = api()): ShellResponse = runBlocking { a.handle(req) }

    private fun body(r: ShellResponse): JsonObject = DefaultCanonicalJson.decode(r.body ?: "null").value as JsonObject

    private fun str(o: JsonObject, k: String): String? = (o.members[k] as? JsonString)?.value

    private fun assertProblem(r: ShellResponse, status: Int, code: String) {
        assertEquals(status, r.status, r.body)
        assertEquals("application/problem+json", r.headers["content-type"])
        assertEquals("1", r.headers["kotobaya-local"])
        val b = body(r)
        assertEquals(code, str(b, "code"))
        assertEquals("urn:kotobaya:problem:$code", str(b, "type"))
        assertEquals(status.toDouble(), (b.members["status"] as JsonNumber).value)
        assertEquals(r.headers["x-request-id"], str(b, "request_id"))
        assertTrue(str(b, "title")!!.isNotEmpty())
        assertTrue("instance" in b.members)
    }

    private fun firstError(r: ShellResponse): Pair<String?, String?> {
        val e = (body(r).members["errors"] as JsonArray).items.first() as JsonObject
        return str(e, "pointer") to str(e, "code")
    }

    @Test
    fun hiddenAnswers403InsufficientScopeWithoutTheNetwork() {
        val a = api(recordingUpstream)
        val tokens = run(get("/api/v1/tokens"), a)
        assertProblem(tokens, 403, "insufficient_scope")
        assertEquals("admin", str(body(tokens), "required_scope"))
        val push = run(send("POST", "/api/v1/sync/push", "{}"), a)
        assertProblem(push, 403, "insufficient_scope")
        assertNull(body(push).members["required_scope"], "hidden because native replaces it: no required_scope")
        assertProblem(run(send("POST", "/api/v1/media/import", "{}"), a), 403, "insufficient_scope")
        assertEquals(emptyList(), forwarded)
    }

    @Test
    fun proxyWithTheDefaultUpstreamAnswers503ShellOffline() {
        val r = run(get("/api/v1/jobs"))
        assertProblem(r, 503, "shell_offline")
        assertEquals("/api/v1/jobs", str(body(r), "instance"))
    }

    @Test
    fun proxyIsForwardedAndNotMarkedLocal() {
        val r = run(get("/api/v1/jobs"), api(recordingUpstream))
        assertEquals(200, r.status)
        assertEquals(listOf("listJobs"), forwarded)
        assertNull(r.headers["kotobaya-local"])
    }

    @Test
    fun unpairedAnswers401ExceptShellInfoAndPair() {
        gate.paired = false
        assertProblem(run(get("/api/v1/meta")), 401, "unauthenticated")
        assertProblem(run(get("/api/v1/tokens")), 401, "unauthenticated") // 401 comes before 403
        assertProblem(run(get("/api/v1/jobs")), 401, "unauthenticated")
        assertProblem(run(send("POST", "/shell/v1/unpair", "{}")), 401, "unauthenticated")
        assertEquals(200, run(get("/shell/v1/info")).status)
        // pairShell has no handler yet (SHELL-A6): it reaches the "not available" 404, never the 401
        assertProblem(run(send("POST", "/shell/v1/pair", "{}")), 404, "not_found")
    }

    @Test
    fun unknownQueryParameterIs422WithPointer() {
        val r = run(get("/api/v1/words/hot", "limit=5&zzz=1"))
        assertProblem(r, 422, "validation_failed")
        assertEquals("/zzz" to "unknown_field", firstError(r))
        assertEquals(200, run(get("/api/v1/words/hot", "limit=5")).status)
        val range = run(get("/api/v1/words/hot", "limit=0"))
        assertProblem(range, 422, "validation_failed")
        assertEquals("/limit" to "out_of_range", firstError(range))
    }

    @Test
    fun unknownBodyPropertyIs422WithPointer() {
        val r = run(send("PATCH", "/api/v1/settings", "{\"values\":{},\"zzz\":1}"))
        assertProblem(r, 422, "validation_failed")
        assertEquals("/zzz" to "unknown_field", firstError(r))
        assertEquals(200, run(send("PATCH", "/api/v1/settings", "{\"values\":{}}")).status)
    }

    @Test
    fun malformedOrNonObjectBodies() {
        assertProblem(run(send("PATCH", "/api/v1/settings", "{nope")), 400, "invalid_request")
        val arr = run(send("PATCH", "/api/v1/settings", "[]"))
        assertProblem(arr, 422, "validation_failed")
        assertEquals("" to "invalid_type", firstError(arr))
        assertProblem(run(send("PATCH", "/api/v1/settings", null, null)), 422, "validation_failed")
    }

    @Test
    fun noRouteIs404() {
        assertProblem(run(get("/api/v1/nothing")), 404, "not_found")
        assertProblem(run(send("POST", "/api/v1/meta", "{}")), 404, "not_found")
        assertProblem(run(get("/api/v1/notes/")), 404, "not_found")
    }

    @Test
    fun bridgeLimitsAre415ShellUnsupported() {
        assertProblem(run(send("PATCH", "/api/v1/settings", "{}", "text/plain")), 415, "shell_unsupported")
        assertProblem(run(send("PATCH", "/api/v1/settings", "{}", null)), 415, "shell_unsupported")
        assertEquals(200, run(send("PATCH", "/api/v1/settings", "{}", "Application/JSON; charset=utf-8")).status)
        val big = "{\"values\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}"
        assertProblem(run(send("PATCH", "/api/v1/settings", big)), 415, "shell_unsupported")
        assertProblem(run(get("/api/v1/blobs/" + "a".repeat(64))), 415, "shell_unsupported")
        // limits come before the 404 and the 401
        gate.paired = false
        assertProblem(run(send("POST", "/api/v1/nothing", "{}", "text/plain")), 415, "shell_unsupported")
    }

    @Test
    fun aBodyOnGetIsIgnored() {
        val r = run(ShellRequest.fromRaw("GET", "/api/v1/meta", "", mapOf("content-type" to "text/plain"), "junk"))
        assertEquals(200, r.status)
    }

    @Test
    fun localAnswersCarryKotobayaLocalAndTheRequestId() {
        val r = run(get("/api/v1/meta", headers = mapOf("x-request-id" to "abc-123")))
        assertEquals(200, r.status)
        assertEquals("1", r.headers["kotobaya-local"])
        assertEquals("abc-123", r.headers["x-request-id"])
        assertEquals("application/json", r.headers["content-type"])
        assertNull(r.headers["kotobaya-seq"])
        val bad = run(get("/api/v1/meta", headers = mapOf("x-request-id" to "not valid!")))
        assertEquals("fresh-id", bad.headers["x-request-id"])
    }

    @Test
    fun fallthroughForwardsThroughTheUpstream() {
        val a = api(recordingUpstream)
        assertEquals("1", run(get("/api/v1/dict/entries/1"), a).headers["kotobaya-local"])
        val fwd = run(get("/api/v1/dict/entries/2"), a)
        assertEquals(listOf("getDictEntry"), forwarded)
        assertNull(fwd.headers["kotobaya-local"])
        assertProblem(run(get("/api/v1/dict/entries/2")), 503, "shell_offline")
    }

    @Test
    fun aLocalOperationWithoutAHandlerIsForwarded() {
        assertProblem(run(get("/api/v1/review/queue")), 503, "shell_offline")
        run(get("/api/v1/review/queue"), api(recordingUpstream))
        assertEquals(listOf("getReviewQueue"), forwarded)
    }

    @Test
    fun handlerFailuresBecomeProblems() {
        assertProblem(run(get("/api/v1/notes/n1")), 500, "internal")
        assertProblem(run(send("DELETE", "/api/v1/notes/n1", null)), 410, "gone")
    }

    @Test
    fun rebuildPendingIs503WithRetryAfterExceptExemptOperations() {
        gate.rebuilding = true
        val r = run(get("/api/v1/meta"))
        assertProblem(r, 503, "shell_rebuilding")
        assertEquals("2", r.headers["retry-after"])
        assertEquals(200, run(get("/shell/v1/info")).status)
        assertProblem(run(get("/api/v1/jobs")), 503, "shell_offline") // proxy is not gated
    }

    @Test
    fun aRequestBeforeReadinessWaitsAndIsAnsweredWhenTheGateOpens() = runBlocking {
        val closed = AlwaysOpenGate(ready = CompletableDeferred())
        val a = LocalApi(testRoutes, closed, newRequestId = { "id" })
        val pending = async { a.handle(get("/api/v1/meta")) }
        yield()
        assertFalse(pending.isCompleted)
        // exempt operations are answered at once
        assertEquals(200, a.handle(get("/shell/v1/info")).status)
        closed.ready.complete(Unit)
        assertEquals(200, pending.await().status)
    }

    @Test
    fun readinessGateTimesOutWith503ShellRebuilding() {
        val closed = AlwaysOpenGate(ready = CompletableDeferred())
        val a = LocalApi(testRoutes, closed, newRequestId = { "id" }, readyTimeoutMs = 50)
        val r = run(get("/api/v1/meta"), a)
        assertProblem(r, 503, "shell_rebuilding")
        assertEquals("2", r.headers["retry-after"])
    }

    @Test
    fun queryIsParsedAsTheServerDoes() {
        assertEquals(mapOf("q" to listOf("a+b c"), "x" to listOf(""), "k" to listOf("1", "2")), parseQuery("q=a+b%20c&x&&k=1&k=2"))
        assertEquals(mapOf("q" to listOf("食べる")), parseQuery("q=%E9%A3%9F%E3%81%B9%E3%82%8B"))
        assertEquals(emptyMap<String, List<String>>(), parseQuery(""))
    }

    @Test
    fun mintRefusalsMapAsRuleL3() {
        fun check(p: Problem, status: Int, code: String, pointer: String? = null, errCode: String? = null) {
            assertEquals(status, p.status)
            assertEquals(code, p.code)
            if (pointer != null) assertEquals(FieldError(pointer, errCode!!, p.errors.first().detail), p.errors.first())
        }
        check(Problems.fromMintRefusal(MintRefusal.NotPaired), 401, "unauthenticated")
        val rebuilding = Problems.fromMintRefusal(MintRefusal.RebuildPending)
        check(rebuilding, 503, "shell_rebuilding")
        assertEquals("2", rebuilding.headers["retry-after"])
        check(
            Problems.fromMintRefusal(MintRefusal.ClockGuard(ClockGuardResult.G1_BEFORE_FLOOR)),
            422, "validation_failed", "/reviewed_at", "before_floor",
        )
        check(
            Problems.fromMintRefusal(MintRefusal.ClockGuard(ClockGuardResult.G2_BEHIND_LAST)),
            422, "validation_failed", "/reviewed_at", "before_card",
        )
        check(Problems.fromMintRefusal(MintRefusal.ClockFarFromServer(-90_000_000)), 422, "validation_failed", "/reviewed_at", "in_future")
        check(Problems.fromMintRefusal(MintRefusal.ClockFarFromServer(90_000_000)), 422, "validation_failed", "/reviewed_at", "before_card")
        check(Problems.fromMintRefusal(MintRefusal.ClockFarFromServer(null)), 422, "validation_failed", "/reviewed_at", "before_card")
        check(Problems.fromMintRefusal(MintRefusal.TooLarge(0, 2, 1)), 413, "payload_too_large")
        val invalid = MintRefusal.Invalid(0, "bad", "/payload/tags/0")
        check(Problems.fromMintRefusal(invalid) { "/tags/0" }, 422, "validation_failed", "/tags/0", "invalid_value")
        check(Problems.fromMintRefusal(invalid), 422, "validation_failed", "", "invalid_value")
        check(Problems.fromMintRefusal(MintRefusal.ServerOnly(0, OpType.NOTE_MERGED)), 500, "internal")
    }
}
