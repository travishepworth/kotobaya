package kotobaya.sync

import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.server.json
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** `POST /sync/push` and `GET /sync/pull` over HTTP, with real device tokens. */
class PushHttpTest : SyncFixture() {
    private val noteB = op(
        OpType.NOTE_CREATED, B, 0x0193a1b2c014L,
        """{"id":"0193a1b2-0001-7000-8000-000000000002","note_type_id":"00000000-0000-7000-8000-000000000101","entry_seq":1358280}""",
    )

    private fun deviceToken(device: String, scopes: List<String> = listOf("read", "write", "review")): String {
        ensureDevice(device)
        return mint(TokenKind.DEVICE, scopes, UUID.fromString(device)).second
    }

    private fun results(text: String) = json.readTree(text)["results"]

    @Test
    fun sameOpIdEqualContentsTheSecondElementIsADuplicateOfTheFirst() = app(extraHandlers = sync.handlers) {
        val token = deviceToken(B)
        // The example's card needs its note on the server to be accepted.
        val note = client.call(HttpMethod.Post, "/sync/push", bearer = token, body = json.writeValueAsString(mapOf("ops" to listOf(noteB))))
        assertEquals("accepted", results(note.bodyAsText())[0]["status"].asText())
        val body = File(contracts, "examples/raw-push/same-op-id-equal-contents.body.txt").readText()
        val res = results(client.call(HttpMethod.Post, "/sync/push", bearer = token, body = body).bodyAsText())
        assertEquals("accepted", res[0]["status"].asText(), res.toString())
        assertEquals("duplicate", res[1]["status"].asText(), res.toString())
        assertEquals(res[0]["seq"].asLong(), res[1]["seq"].asLong())
        assertEquals(1, count("select count(*) from app.change where op_type = 'card.created'"))
    }

    @Test
    fun aReviewOpPushedWithoutTheReviewScopeIsRejectedAloneWithInsufficientScope() = app(extraHandlers = sync.handlers) {
        val token = deviceToken(B, listOf("read", "write"))
        val card = op(OpType.CARD_CREATED, B, 0x0193a1b2c015L, """{"id":"0193a1b2-0002-7000-8000-000000000002","note_id":"0193a1b2-0001-7000-8000-000000000002","kind":"meaning"}""")
        val review = op(
            OpType.REVIEW_LOGGED, B, 0x0193a1b2c020L,
            """{"id":"0193a1b2-0003-7000-8000-000000000001","card_id":"0193a1b2-0002-7000-8000-000000000002","reviewed_at":1733584666656,"utc_offset_min":540,"review_type":"learning","rating":3,"button":"good","duration_ms":4200}""",
        )
        val suspend = op(OpType.CARD_SUSPENDED, B, 0x0193a1b2c021L, """{"id":"0193a1b2-0002-7000-8000-000000000002"}""")
        val r = client.call(HttpMethod.Post, "/sync/push", bearer = token, body = json.writeValueAsString(mapOf("ops" to listOf(noteB, card, review, suspend))))
        assertEquals(200, r.status.value)
        val res = results(r.bodyAsText())
        assertEquals(listOf("accepted", "accepted", "rejected", "accepted"), res.map { it["status"].asText() })
        assertEquals("insufficient_scope", res[2]["error"]["code"].asText())
        assertEquals(0, count("select count(*) from app.review_log"))
    }

    @Test
    fun anOpMintedByAnotherDeviceIsRejectedWithDeviceMismatch() = app(extraHandlers = sync.handlers) {
        val token = deviceToken(A)
        val res = results(client.call(HttpMethod.Post, "/sync/push", bearer = token, body = json.writeValueAsString(mapOf("ops" to listOf(noteB)))).bodyAsText())
        assertEquals("device_mismatch", res[0]["error"]["code"].asText())
    }

    @Test
    fun requestLevelErrors() = app(extraHandlers = sync.handlers) {
        val token = deviceToken(B)
        val body = json.writeValueAsString(mapOf("ops" to listOf(noteB)))
        // A personal token without a device may not push.
        val personal = mint(TokenKind.PERSONAL, listOf("read", "write", "review")).second
        assertProblem(client.call(HttpMethod.Post, "/sync/push", bearer = personal, body = body), 403, "device_required")
        // Not JSON / empty ops: 400.
        assertProblem(client.call(HttpMethod.Post, "/sync/push", bearer = token, body = "{\"ops\":[]}"), 400, "invalid_request")
        assertProblem(client.call(HttpMethod.Post, "/sync/push", bearer = token, body = "nope"), 400, "invalid_request")
        // Wrong content type: 415.
        assertProblem(client.call(HttpMethod.Post, "/sync/push", bearer = token) { contentType(ContentType.Text.Plain); setBody(body) }, 415, "unsupported_media_type")
        // Without write: 403 from the spec's security.
        val readOnly = deviceToken(B, listOf("read"))
        assertProblem(client.call(HttpMethod.Post, "/sync/push", bearer = readOnly, body = body), 403, "insufficient_scope")
        assertEquals(0, count("select count(*) from app.change"))
    }

    @Test
    fun aDuplicateLiveNoteWithoutTheMergePolicyIs503AndAppliesNothing() = app(extraHandlers = sync.handlers) {
        val token = deviceToken(B)
        val first = client.call(HttpMethod.Post, "/sync/push", bearer = token, body = json.writeValueAsString(mapOf("ops" to listOf(noteB))))
        assertEquals(200, first.status.value)
        val dup = op(OpType.NOTE_CREATED, B, 0x0193a1b2c016L, """{"id":"0193a1b2-0001-7000-8000-000000000009","note_type_id":"00000000-0000-7000-8000-000000000101","entry_seq":1358280}""")
        val other = op(OpType.SETTINGS_SET, B, 0x0193a1b2c015L, """{"key":"scheduler.new_per_day","value":5}""")
        assertProblem(client.call(HttpMethod.Post, "/sync/push", bearer = token, body = json.writeValueAsString(mapOf("ops" to listOf(other, dup)))), 503, "unavailable")
        assertEquals(1, count("select count(*) from app.change"), "the whole batch rolled back")
        assertEquals(0, count("select count(*) from app.settings"))
    }

    @Test
    fun pushResponseCarriesTheLogIdentityAndPullReturnsTheOps() = app(extraHandlers = sync.handlers) {
        val token = deviceToken(B)
        val push = json.readTree(client.call(HttpMethod.Post, "/sync/push", bearer = token, body = json.writeValueAsString(mapOf("ops" to listOf(noteB)))).bodyAsText())
        val epoch = db.txBlocking { it.fetchValue("select value from app.server_state where key = 'log_epoch'") as String }
        assertEquals(epoch, push["log_epoch"].asText())
        assertEquals(1, push["high_water_seq"].asLong())
        assertEquals(0, push["durable_seq"].asLong())
        assertEquals(syncNow.get().toEpochMilli(), push["server_time"].asLong())
        val pull = client.call(HttpMethod.Get, "/sync/pull?since=0", bearer = token)
        assertEquals(200, pull.status.value)
        val page = json.readTree(pull.bodyAsText())
        assertEquals(1, page["ops"].size())
        val pulled = page["ops"][0]
        assertEquals(1, pulled["seq"].asLong())
        assertTrue(pulled["caused_by_op_id"].isNull)
        assertEquals(noteB["payload"], pulled["payload"])
        assertEquals(1, page["next_since"].asLong())
        assertEquals(false, page["has_more"].asBoolean())
        assertEquals(epoch, page["log_epoch"].asText())
        assertEquals(0L, count("select last_pull_seq from app.device where id = ?::uuid", B))
        assertProblem(client.call(HttpMethod.Get, "/sync/pull?since=-1", bearer = token), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Get, "/sync/pull?limit=1001", bearer = token), 422, "validation_failed")
        val pushTime = db.txBlocking { it.fetchValue("select last_push_at is not null from app.device where id = ?::uuid", B) as Boolean }
        assertTrue(pushTime)
        assertNotEquals(0, push["results"].size())
    }
}
