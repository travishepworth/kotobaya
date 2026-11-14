package kotobaya.clienterrors

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotobaya.auth.AuthFixture
import kotobaya.auth.TokenKind
import kotobaya.server.LogCapture
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C-23 / OBS-1s: `POST /client-errors` limits, check order and the emitted log lines. */
class ClientErrorsTest : AuthFixture() {
    private fun item(message: String = "boom", extra: String = "") =
        """{"client":"web","app_version":"0.2.0","occurred_at":"2026-11-14T08:15:02.120+09:00","level":"error","kind":"api_failure","message":${q(message)}$extra}"""

    private fun q(s: String) = kotobaya.server.json.writeValueAsString(s)

    private fun batch(vararg items: String, extra: String = "") = """{"items":[${items.joinToString(",")}]$extra}"""

    private fun clientApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(String) -> Unit) =
        app(extraHandlers = ClientErrorsModule { now.get() }.handlers) { block(mint(TokenKind.PERSONAL, listOf("read")).second) }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.post(token: String?, body: String): HttpResponse =
        client.call(HttpMethod.Post, "/client-errors", bearer = token, body = body)

    @Test
    fun logsOneSanitisedLinePerItemWithTokenAndRequestId() = clientApp { token ->
        LogCapture("kotobaya.client_error").use { cap ->
            val bad = item(
                "line1\nline2\u0000 x {} y", ""","stack":"a\r\n\tb","route":"/notes?q=secret#frag","request_id":"srv-1","http_method":"POST","http_path":"/api/v1/x?y=1","http_status":500,"count":2""",
            )
            val warn = item("careful").replace("\"error\"", "\"warn\"")
            val r = post(token, batch(bad, warn, extra = ""","dropped":3"""))
            assertEquals(202, r.status.value)
            assertEquals(2, r.json()["accepted"].asInt())
            val lines = cap.lines()
            val dropped = lines.single { it["message"].asText() == "client dropped error reports" }
            assertEquals("WARN", dropped["level"].asText())
            assertEquals(3, dropped["dropped"].asInt())
            val e = lines.single { it["message"].asText().startsWith("line1") }
            assertEquals("ERROR", e["level"].asText())
            assertEquals("line1\\nline2\\u0000\\u2028x {} y", e["message"].asText())
            assertEquals("a\\r\\n\\tb", e["stack"].asText())
            assertEquals("/notes", e["route"].asText())
            assertEquals("/api/v1/x", e["http_path"].asText())
            assertEquals("srv-1", e["client_request_id"].asText())
            assertEquals("2026-11-13T23:15:02.120Z", e["occurred_at"].asText())
            assertEquals(500, e["http_status"].asInt())
            assertEquals(2, e["count"].asInt())
            assertEquals("web", e["client"].asText())
            assertEquals("0.2.0", e["app_version"].asText())
            assertEquals("test-personal", e["token.name"].asText())
            assertTrue(e["request_id"].asText().isNotEmpty())
            val w = lines.single { it["message"].asText() == "careful" }
            assertEquals("WARN", w["level"].asText())
            assertFalse(w.has("route"))
            assertFalse(w.has("stack"))
            assertEquals(1, w["count"].asInt())
        }
    }

    @Test
    fun requestsOver12PerMinuteAreRefusedWithRetryAfterAndOneWarn() = clientApp { token ->
        repeat(12) { assertEquals(202, post(token, batch(item())).status.value) }
        LogCapture("kotobaya.client_error_refused").use { cap ->
            val r = post(token, batch(item()))
            assertProblem(r, 429, "rate_limited")
            assertEquals("60", r.headers[HttpHeaders.RetryAfter])
            // A second refusal in the same window logs no further line.
            assertEquals(429, post(token, batch(item())).status.value)
            val lines = cap.lines()
            assertEquals(1, lines.size)
            assertEquals("WARN", lines[0]["level"].asText())
            assertEquals(1, lines[0]["refused_requests"].asInt())
            now.set(now.get().plus(Duration.ofSeconds(61)))
            assertEquals(202, post(token, batch(item())).status.value)
        }
    }

    @Test
    fun itemsOver120PerMinuteAreRefusedAsAWholeAndNotLogged() = clientApp { token ->
        val twenty = batch(*Array(20) { item() })
        repeat(6) { assertEquals(202, post(token, twenty).status.value) }
        LogCapture("kotobaya.client_error").use { cap ->
            val r = post(token, batch(item("one-more")))
            assertProblem(r, 429, "rate_limited")
            assertTrue(r.headers[HttpHeaders.RetryAfter]!!.toLong() in 1..60)
            assertTrue(cap.events.isEmpty())
        }
    }

    @Test
    fun bodyOverOneMiBIs413AndMoreThan20ItemsIs413Not422() = clientApp { token ->
        assertProblem(post(token, "x".repeat(1_048_577)), 413, "payload_too_large")
        // 21 items: 413 even though the schema's maxItems would also fail it.
        assertProblem(post(token, batch(*Array(21) { item() })), 413, "payload_too_large")
        // 21 items with an invalid item: still 413 (item count is checked before field validation).
        assertProblem(post(token, batch(*Array(21) { """{"bad":1}""" })), 413, "payload_too_large")
    }

    @Test
    fun fieldViolationsAre422AndNothingIsLogged() = clientApp { token ->
        LogCapture("kotobaya.client_error").use { cap ->
            assertProblem(post(token, """{"items":[]}"""), 422, "validation_failed")
            assertProblem(post(token, batch(item("m".repeat(2049)))), 422, "validation_failed")
            val r = post(token, batch(item(), item("ok", ""","extra":1""")))
            assertProblem(r, 422, "validation_failed")
            assertEquals("/items/1/extra", r.json()["errors"][0]["pointer"].asText())
            assertProblem(post(token, "not json"), 400, "invalid_request")
            // Only the rejection WARNs (one per 422), never an item line.
            assertTrue(cap.lines().all { it["message"].asText().startsWith("client error report rejected") })
        }
    }

    @Test
    fun noCredentialIs401() = clientApp { _ ->
        assertProblem(post(null, batch(item())), 401, "unauthenticated")
    }
}
