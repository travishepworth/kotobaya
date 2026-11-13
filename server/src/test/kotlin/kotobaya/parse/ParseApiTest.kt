package kotobaya.parse

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.server.json
import kotobaya.sync.SyncFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T403c: `POST /parse` against a fake worker, and override writes as ops. */
class ParseApiTest : SyncFixture() {
    private var worker: ParserClient = ParserClient { text, _ -> reply(text) }
    private var calls = 0
    private val module by lazy { ParseModule(db, sync.opApply) { text, id -> calls++; worker.parse(text, id) } }

    private fun parseApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = module.handlers, block = block)

    private fun reader() = mint(TokenKind.PERSONAL, listOf("read")).second
    private fun writer() = mint(TokenKind.PERSONAL, listOf("read", "write")).second

    private fun token(s: Int, e: Int, surface: String, seq: Long?) =
        mapOf("s" to s, "e" to e, "surface" to surface, "lemma" to surface, "reading" to "", "pos" to listOf("名詞"), "cform" to null, "seq" to seq, "class" to "content", "override" to false)

    private fun reply(text: String) = WorkerParse(
        "kp1;test",
        json.valueToTree(
            listOf(
                mapOf("text" to text, "tokens" to listOf(token(0, 2, "パン", 1000), token(3, 5, "食べ", 3000), token(5, 6, "る", 999_999), token(6, 8, "パン", 1000), token(8, 9, "ぬ", null))),
            ),
        ),
    )

    private fun entry(seq: Long, headword: String) {
        db.txBlocking {
            it.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
            it.execute(
                "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, pos, added_release_id) " +
                    "values (?, ?, ?, '[{\"text\":\"x\"}]', '[{\"gloss\":[{\"text\":\"a\"},{\"text\":\"b\"}]}]', '{n}', 1) on conflict do nothing",
                seq, headword, headword,
            )
        }
    }

    private fun status(seq: Long, status: String) {
        db.txBlocking {
            it.execute(
                "insert into app.word_status (entry_seq, status, auto_status, updated_at) values (?, ?, ?, now())",
                seq, status, status,
            )
        }
    }

    private suspend fun ApplicationTestBuilder.parse(token: String, text: Any?): HttpResponse =
        client.call(HttpMethod.Post, "/parse", bearer = token, body = mapOf("text" to text))

    private fun head(): Long = count("select coalesce(max(seq), 0) from app.change")

    private fun opsSince(after: Long): List<String> =
        db.txBlocking { dsl -> dsl.fetch("select op_type from app.change where seq > ? order by seq", after).map { it.get(0, String::class.java) } }

    @Test
    fun parseTextReturnsWorkerSentencesAndEntriesWithStatusAndNoteAscendingBySeq() = parseApp {
        entry(1000, "パン")
        entry(3000, "食べる")
        status(1000, "known")
        val note = "0193a1b2-0001-7000-8000-000000000001"
        emit(OpType.NOTE_CREATED, """{"id":"$note","note_type_id":"00000000-0000-7000-8000-000000000101","entry_seq":3000,"fields":{"expression":"食べる"},"origin":"manual"}""")
        val r = parse(reader(), "パン食べるパンぬ")
        assertEquals(200, r.status.value)
        val body = r.json()
        assertEquals("kp1;test", body["parser_version"].asText())
        assertEquals("パン食べるパンぬ", body["sentences"][0]["text"].asText())
        assertEquals(5, body["sentences"][0]["tokens"].size(), "tokens pass through unchanged")
        // seq 999999 is not in the release: omitted from entries, kept on its token; null seq never listed.
        val entries = body["entries"]
        assertEquals(listOf(1000L, 3000L), entries.map { it["seq"].asLong() })
        assertEquals("known", entries[0]["status"].asText())
        assertTrue(entries[0]["note_id"].isNull)
        assertTrue(entries[1]["status"].asText() in setOf("new", "learning"))
        assertEquals(note, entries[1]["note_id"].asText())
        assertEquals(listOf("a", "b"), entries[0]["glosses"].map { it.asText() })
        assertEquals(setOf("seq", "headword", "headword_reading", "is_common", "glosses", "pos", "freq_rank", "retired", "status", "note_id"), entries[0].fieldNames().asSequence().toSet())
    }

    @Test
    fun workerFailuresAreAnOpaque503AndBusyCarriesRetryAfter() = parseApp {
        val t = reader()
        for (failure in listOf(ParserUnavailable("down"), ParserUnavailable("worker 503 busy", retryAfter = true))) {
            worker = ParserClient { _, _ -> throw failure }
            val r = parse(t, "パン")
            assertProblem(r, 503, "unavailable")
            assertEquals(if (failure.retryAfter) "5" else null, r.headers["Retry-After"])
        }
        worker = ParserClient { _, _ -> throw ParserTooLarge() }
        assertProblem(parse(t, "パン"), 413, "payload_too_large")
    }

    @Test
    fun oversizeIs413WithoutCallingTheWorkerAndTheBoundaryPasses() = parseApp {
        val t = reader()
        assertProblem(parse(t, "あ".repeat(20_001)), 413, "payload_too_large")
        assertEquals(0, calls)
        // 20,000 code points that are 40,000 UTF-16 units: counted in code points.
        assertEquals(200, parse(t, "𠮷".repeat(20_000)).status.value)
        assertEquals(1, calls)
        assertProblem(parse(t, ""), 422, "validation_failed")
        assertProblem(parse(t, 5), 422, "validation_failed")
        assertEquals(1, calls)
    }

    @Test
    fun parseNeedsReadScope() = parseApp {
        val noScopes = mint(TokenKind.PERSONAL, listOf("review")).second
        assertProblem(parse(noScopes, "パン"), 403, "insufficient_scope")
    }

    // ------------------------------------------------------------------ overrides

    private val ov = "01931f40-6000-7000-8000-000000000801"
    private val media = "01931f40-3000-7000-8000-000000000e01"
    private val sentence = "01931f40-5000-7000-8000-000000000701"
    private fun tok(surface: String, seq: Long?) = mapOf("surface" to surface, "lemma" to surface, "seq" to seq)
    private fun putBody(vararg extra: Pair<String, Any?>) =
        mapOf("match_text" to "食べかけ", "tokens" to listOf(tok("食べ", 1358280), tok("かけ", 1207610))) + extra

    private suspend fun ApplicationTestBuilder.put(token: String, id: String, body: Any): HttpResponse =
        client.call(HttpMethod.Put, "/parse-overrides/$id", bearer = token, body = body)

    @Test
    fun putAndDeleteEmitOpsAndPutOfADeletedIdIs410() = parseApp {
        val t = writer()
        val before = head()
        val r = put(t, ov, putBody("media_id" to media, "note" to "split"))
        assertEquals(200, r.status.value, r.json().toString())
        assertEquals(listOf("parse_override.put"), opsSince(before))
        assertEquals(head(), r.headers["Kotobaya-Seq"]?.toLong())
        val body = r.json()
        assertEquals(ov, body["id"].asText())
        assertEquals(media, body["media_id"].asText())
        assertTrue(body["sentence_id"].isNull)
        assertEquals("split", body["note"].asText())
        assertEquals(2, body["tokens"].size())
        assertTrue(body["deleted_at"].isNull)
        assertEquals(1, count("select count(*) from app.change where op_type = 'parse_override.put' and entity_id = ?", ov))

        // replace (whole row)
        val r2 = put(t, ov, mapOf("match_text" to "食べ", "tokens" to listOf(tok("食べ", null))))
        assertEquals(200, r2.status.value, r2.json().toString())
        assertEquals("食べ", r2.json()["match_text"].asText())
        assertTrue(r2.json()["media_id"].isNull, "whole-row replace: scope back to global")
        assertEquals(2, count("select count(*) from app.change where op_type = 'parse_override.put'"))

        val mid = head()
        val d = client.call(HttpMethod.Delete, "/parse-overrides/$ov", bearer = t)
        assertEquals(204, d.status.value)
        assertEquals(listOf("parse_override.deleted"), opsSince(mid))
        assertEquals(head(), d.headers["Kotobaya-Seq"]?.toLong())
        // already deleted: 204, no op, no header
        val d2 = client.call(HttpMethod.Delete, "/parse-overrides/$ov", bearer = t)
        assertEquals(204, d2.status.value)
        assertNull(d2.headers["Kotobaya-Seq"])
        assertEquals(mid + 1, head())
        // put of a deleted id: 410, nothing emitted
        assertProblem(put(t, ov, putBody()), 410, "gone")
        assertEquals(mid + 1, head())
        assertProblem(client.call(HttpMethod.Delete, "/parse-overrides/01931f40-6000-7000-8000-000000000802", bearer = t), 404, "not_found")
    }

    @Test
    fun validationFailuresAre422AndEmitNothing() = parseApp {
        val t = writer()
        val before = head()
        val bad = listOf(
            putBody("match_text" to "食べかけ", "tokens" to listOf(tok("食べ", 1), tok("か", 2))),
            putBody("sentence_id" to sentence),
            putBody("media_id" to "nope"),
            putBody("tokens" to emptyList<Any>()),
            putBody("tokens" to listOf(mapOf("surface" to "食べかけ"))),
            putBody("tokens" to listOf(tok("食べかけ", 0))),
            putBody("tokens" to listOf(mapOf("surface" to "食べかけ", "seq" to null, "reading" to "タベ"))),
            putBody("tokens" to listOf(mapOf("surface" to "食べかけ", "seq" to null, "class" to "foo"))),
            putBody("tokens" to listOf(mapOf("surface" to "食べかけ", "seq" to null, "extra" to 1))),
            putBody("match_text" to ""),
            putBody("note" to "x".repeat(1001)),
            putBody("bogus" to 1),
        )
        for (b in bad) assertProblem(put(t, ov, b), 422, "validation_failed")
        assertEquals(before, head())
        assertEquals(0, count("select count(*) from app.parse_override"))
        // valid variants
        val ok = put(t, ov, putBody("media_id" to media, "sentence_id" to sentence))
        assertEquals(200, ok.status.value, ok.json().toString())
        assertEquals(sentence, ok.json()["sentence_id"].asText())
        val ok2 = put(t, "01931f40-6000-7000-8000-000000000803", mapOf("match_text" to "食べかけ", "tokens" to listOf(mapOf("surface" to "食べかけ", "seq" to null, "reading" to "たべかけ", "class" to "name"))))
        assertEquals(200, ok2.status.value, ok2.json().toString())
    }

    @Test
    fun overrideWritesNeedWriteScope() = parseApp {
        val r = put(reader(), ov, putBody())
        assertProblem(r, 403, "insufficient_scope")
        assertProblem(client.call(HttpMethod.Delete, "/parse-overrides/$ov", bearer = reader()), 403, "insufficient_scope")
    }

    @Test
    fun listFiltersScopeDeletedAndPagesStably() = parseApp {
        val t = writer()
        val ids = (1..5).map { "01931f40-6000-7000-8000-00000000090$it" }
        put(t, ids[0], putBody())
        put(t, ids[1], putBody("media_id" to media))
        put(t, ids[2], putBody("media_id" to media, "sentence_id" to sentence))
        put(t, ids[3], putBody("media_id" to "01931f40-3000-7000-8000-000000000e02"))
        put(t, ids[4], putBody())
        client.call(HttpMethod.Delete, "/parse-overrides/${ids[4]}", bearer = t)

        suspend fun list(query: String): JsonNode {
            val r = client.call(HttpMethod.Get, "/parse-overrides$query", bearer = t)
            assertEquals(200, r.status.value, r.json().toString())
            return r.json()
        }
        fun JsonNode.idList() = this["items"].map { it["id"].asText() }
        assertEquals(setOf(ids[0], ids[1], ids[2], ids[3]), list("").idList().toSet())
        assertEquals(setOf(ids[0], ids[1], ids[2], ids[3], ids[4]), list("?include_deleted=true").idList().toSet())
        assertEquals(setOf(ids[0]), list("?scope=global").idList().toSet())
        assertEquals(setOf(ids[1], ids[3]), list("?scope=media").idList().toSet())
        assertEquals(setOf(ids[2]), list("?scope=sentence").idList().toSet())
        assertEquals(setOf(ids[1], ids[2]), list("?media_id=$media").idList().toSet(), "includes sentence-scoped ones")
        assertEquals(setOf(ids[1]), list("?media_id=$media&scope=media").idList().toSet())
        assertProblem(client.call(HttpMethod.Get, "/parse-overrides?scope=x", bearer = t), 422, "validation_failed")

        // updated_at descending, then id; paging visits each row exactly once
        val all = list("?include_deleted=true&limit=500").idList()
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = list("?include_deleted=true&limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
            seen += page.idList()
            cursor = page["next_cursor"].takeUnless { it.isNull }?.asText()
        } while (cursor != null)
        assertEquals(all, seen)
        assertEquals(5, seen.size)
        assertNotNull(list("?limit=1")["next_cursor"].asText())
        assertProblem(client.call(HttpMethod.Get, "/parse-overrides?cursor=garbage", bearer = t), 422, "validation_failed")
    }
}
