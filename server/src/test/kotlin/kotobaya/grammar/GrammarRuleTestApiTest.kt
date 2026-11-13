package kotobaya.grammar

import io.ktor.http.HttpMethod
import kotobaya.auth.TokenKind
import kotobaya.parse.ParserUnavailable
import kotobaya.sync.SyncFixture
import kotlin.test.Test
import kotlin.test.assertEquals

/** Test double for the worker's rule endpoint: a rule "BAD..." does not parse; any other rule matches its text literally. */
class FakeRuleTester(var down: Boolean = false) : RuleTesterClient {
    var calls = 0

    override suspend fun test(rules: List<String>, sentences: List<RuleSentence>, requestId: String?): RuleTestResponse {
        calls++
        if (down) throw ParserUnavailable("worker unreachable", retryAfter = true)
        val errors = rules.withIndex().filter { it.value.startsWith("BAD") }.map { RuleError(it.index, "unknown part of speech", 2) }
        val matches = mutableListOf<RuleMatch>()
        sentences.forEachIndexed { si, s ->
            rules.forEachIndexed { ri, r ->
                if (!r.startsWith("BAD")) {
                    val at = s.text.indexOf(r)
                    if (at >= 0) matches += RuleMatch(ri, si, at, at + r.length)
                }
            }
        }
        return RuleTestResponse(matches, errors, false)
    }
}

/** T506c: rule validation on create/update (422 / 503 / idempotent retry) and `testGrammarRules`. */
class GrammarRuleTestApiTest : SyncFixture() {
    private val tester = FakeRuleTester()
    private val grammar by lazy { GrammarModule(db, sync.opApply, tester) }

    private fun rules(vararg r: String) = r.map { mapOf("rule" to it, "draft" to false) }

    @Test
    fun invalidRuleIs422WithPointerAndColumnAndWritesNothing() = app(extraHandlers = grammar.handlers) {
        val w = mint(TokenKind.PERSONAL, listOf("read", "write")).second
        val r = client.call(HttpMethod.Post, "/grammar", bearer = w, body = mapOf("title" to "t", "detection_rules" to rules("ok", "BAD x")))
        assertProblem(r, 422, "validation_failed")
        val err = r.json()["errors"][0]
        assertEquals("/detection_rules/1/rule", err["pointer"].asText())
        assertEquals("invalid_value", err["code"].asText())
        assertEquals("column 2: unknown part of speech", err["detail"].asText())
        assertEquals(0L, count("select count(*) from app.grammar_point"))
        assertEquals(0L, count("select count(*) from app.change"))

        val id = client.call(HttpMethod.Post, "/grammar", bearer = w, body = mapOf("title" to "t", "detection_rules" to rules("ok"))).json()["id"].asText()
        val u = client.call(HttpMethod.Patch, "/grammar/$id", bearer = w, body = mapOf("detection_rules" to rules("BAD")))
        assertProblem(u, 422, "validation_failed")
        assertEquals("/detection_rules/0/rule", u.json()["errors"][0]["pointer"].asText())
        assertEquals(1L, count("select count(*) from app.change where op_type = 'grammar.created'"))
        assertEquals(0L, count("select count(*) from app.change where op_type = 'grammar.updated'"))
    }

    @Test
    fun workerDownFailsClosedOnlyWhenRulesArePresentAndRetryOfExistingIdIs200() = app(extraHandlers = grammar.handlers) {
        val w = mint(TokenKind.PERSONAL, listOf("read", "write")).second
        val id = client.call(HttpMethod.Post, "/grammar", bearer = w, body = mapOf("title" to "t", "detection_rules" to rules("ok"))).json()["id"].asText()
        tester.down = true
        assertProblem(client.call(HttpMethod.Post, "/grammar", bearer = w, body = mapOf("title" to "u", "detection_rules" to rules("ok"))), 503, "unavailable")
        assertProblem(client.call(HttpMethod.Patch, "/grammar/$id", bearer = w, body = mapOf("detection_rules" to rules("ok2"))), 503, "unavailable")
        assertEquals(1L, count("select count(*) from app.grammar_point"))
        val before = tester.calls
        assertEquals(200, client.call(HttpMethod.Post, "/grammar", bearer = w, body = mapOf("id" to id, "title" to "t", "detection_rules" to rules("BAD"))).status.value)
        assertEquals(200, client.call(HttpMethod.Patch, "/grammar/$id", bearer = w, body = mapOf("meaning" to "m")).status.value)
        assertEquals(200, client.call(HttpMethod.Patch, "/grammar/$id", bearer = w, body = mapOf("detection_rules" to emptyList<Any>())).status.value)
        assertEquals(before, tester.calls, "empty/absent rules and an existing id never call the worker")
    }

    @Test
    fun testGrammarRulesScansNewestMediaFirstWithLimitAndPointers() = app(extraHandlers = grammar.handlers) {
        val w = mint(TokenKind.PERSONAL, listOf("read", "write")).second
        val r = mint(TokenKind.PERSONAL, listOf("read")).second
        val gid = client.call(HttpMethod.Post, "/grammar", bearer = w, body = mapOf("title" to "g", "detection_rules" to rules("ざる"))).json()["id"].asText()
        val older = "01931f40-3000-7000-8000-000000000e01"
        val newer = "01931f40-3000-7000-8000-000000000e02"
        db.txBlocking {
            for ((i, m) in listOf(older, newer).withIndex()) {
                val seg = "01931f40-3100-7000-8000-000000000e1$i"
                it.execute("insert into app.media (id, title, kind, source, parse_status, content_version, created_at, updated_at) values (?::uuid, 'm', 'anime', 'file', 'parsed', 1, now() - interval '${2 - i} day', now())", m)
                it.execute("insert into app.media_segment (id, media_id, ordinal, kind) values (?::uuid, ?::uuid, 0, 'episode')", seg, m)
                for (k in 0 until 3) {
                    val text = if (k == 1) "行かざるを得ない" else "パン"
                    it.execute(
                        "insert into app.sentence (id, media_id, segment_id, ordinal, text, tokens, origin) values (?::uuid, ?::uuid, ?::uuid, ?, ?, '[]'::jsonb, 'media')",
                        "01931f40-5000-7000-8000-00000000070${i * 3 + k}", m, seg, k, text,
                    )
                }
            }
        }
        val all = client.call(HttpMethod.Post, "/grammar/$gid/rules/test", bearer = r, body = emptyMap<String, Any>())
        assertEquals(200, all.status.value, all.json().toString())
        assertEquals(6, all.json()["scanned"].asInt())
        assertEquals(false, all.json()["truncated"].asBoolean())
        assertEquals(listOf(newer, older), all.json()["matches"].map { it["sentence"]["media_id"].asText() })
        assertEquals(2, all.json()["matches"][0]["spans"][0]["start_offset"].asInt())

        val one = client.call(HttpMethod.Post, "/grammar/$gid/rules/test", bearer = r, body = mapOf("limit" to 1, "rules" to rules("パン")))
        assertEquals(1, one.json()["matches"].size())
        assertEquals(true, one.json()["truncated"].asBoolean())
        assertEquals(1, one.json()["scanned"].asInt())

        val onlyOlder = client.call(HttpMethod.Post, "/grammar/$gid/rules/test", bearer = r, body = mapOf("media_id" to older))
        assertEquals(1, onlyOlder.json()["matches"].size())
        assertEquals(3, onlyOlder.json()["scanned"].asInt())

        val bad = client.call(HttpMethod.Post, "/grammar/$gid/rules/test", bearer = r, body = mapOf("rules" to rules("ざる", "BAD")))
        assertProblem(bad, 422, "validation_failed")
        assertEquals("/rules/1/rule", bad.json()["errors"][0]["pointer"].asText())

        tester.down = true
        assertProblem(client.call(HttpMethod.Post, "/grammar/$gid/rules/test", bearer = r, body = emptyMap<String, Any>()), 503, "unavailable")
        assertProblem(client.call(HttpMethod.Post, "/grammar/01931f40-0000-7000-8000-000000000999/rules/test", bearer = r, body = emptyMap<String, Any>()), 404, "not_found")
        assertEquals(0L, count("select count(*) from app.sentence_grammar_span"))
    }
}
