package kotobaya.grammar

import io.ktor.http.HttpMethod
import kotobaya.auth.TokenKind
import kotobaya.sync.SyncFixture
import kotlin.test.Test
import kotlin.test.assertEquals

/** W-6: re-detection enqueue on rule edits (C-13), manual span ops, examples. */
class GrammarDetectApiTest : SyncFixture() {
    private val grammar by lazy { GrammarModule(db, sync.opApply, FakeRuleTester()) }

    private fun jobs(): Long = count("select count(*) from app.job where kind = 'grammar-detect'")

    private fun releaseKey() {
        db.txBlocking { it.execute("update app.job set dedupe_key = null where kind = 'grammar-detect'") }
    }

    private val rule = listOf(mapOf("rule" to "[verb:未然形] ざる を 得 ない", "draft" to false))

    @Test
    fun ruleEditsEnqueueOneDedupedFanOutAndReleasedKeyEnqueuesAgain() = app(extraHandlers = grammar.handlers) {
        val w = mint(TokenKind.PERSONAL, listOf("read", "write")).second
        val plain = client.call(HttpMethod.Post, "/grammar", bearer = w, body = mapOf("title" to "a")).json()["id"].asText()
        assertEquals(0, jobs(), "no rules, no job")
        client.call(HttpMethod.Patch, "/grammar/$plain", bearer = w, body = mapOf("meaning" to "x"))
        assertEquals(0, jobs(), "a non-rule edit enqueues nothing")

        val id = client.call(HttpMethod.Post, "/grammar", bearer = w, body = mapOf("title" to "b", "detection_rules" to rule)).json()["id"].asText()
        assertEquals(1, jobs())
        client.call(HttpMethod.Patch, "/grammar/$id", bearer = w, body = mapOf("detection_rules" to listOf(mapOf("rule" to "ざる", "draft" to true))))
        client.call(HttpMethod.Patch, "/grammar/$id", bearer = w, body = mapOf("detection_rules" to rule))
        assertEquals(1, jobs(), "edits collapse onto the queued fan-out")
        client.call(HttpMethod.Delete, "/grammar/$id", bearer = w)
        assertEquals(1, jobs())

        releaseKey()
        client.call(HttpMethod.Delete, "/grammar/$id", bearer = w) // already tombstoned: nothing
        assertEquals(1, jobs())
        val id2 = client.call(HttpMethod.Post, "/grammar", bearer = w, body = mapOf("title" to "c", "detection_rules" to rule)).json()["id"].asText()
        assertEquals(2, jobs(), "released key: a new fan-out")
        releaseKey()
        client.call(HttpMethod.Delete, "/grammar/$id2", bearer = w)
        assertEquals(3, jobs(), "deleting a point with rules enqueues")
        releaseKey()
        client.call(HttpMethod.Delete, "/grammar/$plain", bearer = w)
        assertEquals(3, jobs(), "deleting a rule-less point enqueues nothing")
    }

    @Test
    fun manualSpansAreIdempotentScopedAndListedAsExamples() = app(extraHandlers = grammar.handlers) {
        val w = mint(TokenKind.PERSONAL, listOf("read", "write")).second
        val r = mint(TokenKind.PERSONAL, listOf("read")).second
        val gid = client.call(HttpMethod.Post, "/grammar", bearer = w, body = mapOf("title" to "g")).json()["id"].asText()
        val media = "01931f40-3000-7000-8000-000000000e01"
        val seg = "01931f40-3100-7000-8000-000000000e11"
        val s1 = "01931f40-5000-7000-8000-000000000701"
        val s2 = "01931f40-5000-7000-8000-000000000702"
        db.txBlocking {
            it.execute("insert into app.media (id, title, kind, source, parse_status, content_version, created_at, updated_at) values (?::uuid, 'm', 'anime', 'file', 'parsed', 1, now(), now())", media)
            it.execute("insert into app.media_segment (id, media_id, ordinal, kind) values (?::uuid, ?::uuid, 0, 'episode')", seg, media)
            for ((i, sid) in listOf(s1, s2).withIndex()) {
                it.execute("insert into app.sentence (id, media_id, segment_id, ordinal, text, tokens, origin) values (?::uuid, ?::uuid, ?::uuid, ?, 'パンを食べる。', '[]'::jsonb, 'media')", sid, media, seg, i)
            }
            it.execute(
                "insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id, rule_index) values (?::uuid, ?::uuid, 0, 2, 'detected', ?::uuid, 0)",
                s1, gid, media,
            )
        }
        val url = "/grammar/$gid/spans/$s2/1/3"
        assertProblem(client.call(HttpMethod.Put, url, bearer = r), 403, "insufficient_scope")
        assertProblem(client.call(HttpMethod.Put, "/grammar/$gid/spans/$s2/3/3", bearer = w), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Put, "/grammar/$gid/spans/$s2/1/99", bearer = w), 422, "validation_failed")
        val put = client.call(HttpMethod.Put, url, bearer = w)
        assertEquals(200, put.status.value, put.json().toString())
        assertEquals(media, put.json()["media_id"].asText(), "media filled from the sentence")
        assertEquals("manual", put.json()["origin"].asText())
        assertEquals(200, client.call(HttpMethod.Put, url, bearer = w).status.value)
        assertEquals(1L, count("select count(*) from app.change where op_type = 'grammar.span_put'"), "a live span is not re-put")
        // unknown sentence: stored as given
        assertEquals(200, client.call(HttpMethod.Put, "/grammar/$gid/spans/01931f40-5000-7000-8000-000000000799/0/5", bearer = w).status.value)

        val ex = client.call(HttpMethod.Get, "/grammar/$gid/examples", bearer = r).json()["items"]
        assertEquals(listOf(s2, s1), ex.map { it["id"].asText() }, "manual first")
        assertEquals(listOf(s1), client.call(HttpMethod.Get, "/grammar/$gid/examples?origin=detected", bearer = r).json()["items"].map { it["id"].asText() })

        assertProblem(client.call(HttpMethod.Delete, url, bearer = r), 403, "insufficient_scope")
        assertEquals(204, client.call(HttpMethod.Delete, url, bearer = w).status.value)
        assertEquals(204, client.call(HttpMethod.Delete, url, bearer = w).status.value)
        assertEquals(1L, count("select count(*) from app.change where op_type = 'grammar.span_removed'"))
        assertEquals(listOf(s1), client.call(HttpMethod.Get, "/grammar/$gid/examples", bearer = r).json()["items"].map { it["id"].asText() })
        assertEquals(200, client.call(HttpMethod.Put, url, bearer = w).status.value, "removed span becomes live again")

        client.call(HttpMethod.Delete, "/grammar/$gid", bearer = w)
        assertProblem(client.call(HttpMethod.Put, url, bearer = w), 410, "gone")
        assertProblem(client.call(HttpMethod.Get, "/grammar/$gid/examples", bearer = r), 410, "gone")
    }
}
