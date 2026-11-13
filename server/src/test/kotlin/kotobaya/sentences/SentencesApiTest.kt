package kotobaya.sentences

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.AuthFixture
import kotobaya.auth.TokenKind
import kotobaya.server.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T408a: i+1 / i+0, ordering and paging, grammar spans, and entry examples over HTTP, on hand-built data. */
class SentencesApiTest : AuthFixture() {
    private val sentences = SentencesModule(db)

    private val mediaA = "01931f40-3000-7000-8000-00000000a001" // older
    private val mediaB = "01931f40-3000-7000-8000-00000000b001" // newer
    private val mediaGone = "01931f40-3000-7000-8000-00000000c001"
    private val segA1 = "01931f40-3100-7000-8000-00000000a011"
    private val segA2 = "01931f40-3100-7000-8000-00000000a012"
    private val segB1 = "01931f40-3100-7000-8000-00000000b011"
    private val segGone = "01931f40-3100-7000-8000-00000000c011"

    private fun sid(n: Int) = "01931f40-5000-7000-8000-0000000007%02d".format(n)

    private fun sApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = sentences.handlers, block = block)

    private fun reader() = mint(TokenKind.PERSONAL, listOf("read")).second

    private fun tok(seq: Long?, cls: String = "content") =
        mapOf("s" to 0, "e" to 1, "surface" to "x", "lemma" to "x", "reading" to "", "pos" to listOf("名詞"), "cform" to null, "seq" to seq, "class" to cls, "override" to false)

    private fun media(id: String, createdAt: String, deleted: Boolean = false) {
        db.txBlocking {
            it.execute(
                "insert into app.media (id, title, kind, source, parse_status, content_version, created_at, updated_at, deleted_at) " +
                    "values (?::uuid, 'm', 'anime', 'file', 'parsed', 1, ?::timestamptz, ?::timestamptz, case when ? then now() end)",
                id, createdAt, createdAt, deleted,
            )
        }
    }

    private fun segment(media: String, id: String, ordinal: Int) {
        db.txBlocking {
            it.execute("insert into app.media_segment (id, media_id, ordinal, kind) values (?::uuid, ?::uuid, ?, 'episode')", id, media, ordinal)
        }
    }

    private fun sentence(n: Int, media: String, seg: String, ordinal: Int, text: String, tokens: List<Map<String, Any?>>, origin: String = "media") {
        db.txBlocking {
            it.execute(
                "insert into app.sentence (id, media_id, segment_id, ordinal, text, tokens, origin) values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?::jsonb, ?)",
                sid(n), media, seg, ordinal, text, json.writeValueAsString(tokens), origin,
            )
        }
    }

    private fun status(seq: Long, status: String?) {
        db.txBlocking {
            it.execute("delete from app.word_status where entry_seq = ?", seq)
            if (status != null) {
                val auto = status.takeIf { it in setOf("new", "learning", "known") }
                it.execute(
                    "insert into app.word_status (entry_seq, status, auto_status, override_status, override_hlc, updated_at) " +
                        "values (?, ?, ?, ?, case when ? then '0193a1b2c100-0000-00000000-0000-7000-8000-000000000001' end, now())",
                    seq, status, auto, status.takeIf { auto == null }, auto == null,
                )
            }
        }
    }

    /**
     * Media A (older): seg 1: s1..s5; seg 2: s6 (generated). Media B: s7. Deleted media: s8 (always unknown seq 10).
     * Entries: 10, 20 new; 11 particle; 12 name; 13 aux; 14 other; 30 ignored.
     */
    private fun data() {
        media(mediaA, "2026-01-01T00:00:00Z")
        media(mediaB, "2026-02-01T00:00:00Z")
        media(mediaGone, "2026-03-01T00:00:00Z", deleted = true)
        segment(mediaA, segA1, 0); segment(mediaA, segA2, 1); segment(mediaB, segB1, 0); segment(mediaGone, segGone, 0)
        sentence(1, mediaA, segA1, 0, "一つ目", listOf(tok(10), tok(11, "particle"), tok(12, "name"), tok(null)))
        sentence(2, mediaA, segA1, 1, "二つ目", listOf(tok(10), tok(20)))
        sentence(3, mediaA, segA1, 2, "三つ目", listOf(tok(30), tok(11, "particle")))
        sentence(4, mediaA, segA1, 3, "四つ目", listOf(tok(10), tok(10)))
        sentence(5, mediaA, segA1, 4, "五つ目", listOf(tok(13, "auxiliary"), tok(14, "other")))
        sentence(6, mediaA, segA2, 0, "生成文", listOf(tok(20)), origin = "generated")
        sentence(7, mediaB, segB1, 0, "別の食べ物", listOf(tok(10)))
        sentence(8, mediaGone, segGone, 0, "消えた", listOf(tok(10)))
        status(30, "ignored")
    }

    private suspend fun ApplicationTestBuilder.items(path: String): List<JsonNode> {
        val r = client.call(HttpMethod.Get, path, bearer = reader())
        assertEquals(200, r.status.value, r.json().toString())
        return r.json()["items"].toList()
    }

    private suspend fun ApplicationTestBuilder.ids(path: String): List<Int> = items(path).map { it["id"].asText().takeLast(2).toInt() }

    @Test
    fun iPlusOneAndIPlusZeroIgnoreParticlesNamesUnmappedAndIgnoredEntriesAndTrackStatusChanges() = sApp {
        data()
        // s1: only 10 counts. s2: 10 and 20. s3: ignored + particle. s4: 10 twice = one entry. s5: aux skipped, other counts.
        assertEquals(listOf(7, 1, 4, 5, 6), ids("/sentences?filter=i%2B1"), "generated sentences are included")
        assertEquals(listOf(7, 1, 4, 5, 6), ids("/sentences?filter=i%201"), "'i 1' (decoded '+') is accepted")
        assertEquals(listOf(7, 1, 4, 5, 6), ids("/sentences?filter=i+1"))
        assertEquals(listOf(3), ids("/sentences?filter=i%2B0"))
        assertEquals(listOf(3), ids("/sentences?filter=i%200"))
        val all = items("/sentences")
        assertEquals(mapOf(1 to 1, 2 to 2, 3 to 0, 4 to 1, 5 to 1, 6 to 1, 7 to 1), all.associate { it["id"].asText().takeLast(2).toInt() to it["unknown_count"].asInt() })
        assertEquals("generated", all.single { it["id"].asText() == sid(6) }["origin"].asText())
        // 10 becomes known: s1, s4, s7 drop to i+0; s2 flips into i+1 (only 20 left).
        status(10, "known")
        assertEquals(listOf(2, 5, 6), ids("/sentences?filter=i%2B1"))
        assertEquals(listOf(7, 1, 3, 4), ids("/sentences?filter=i%2B0"))
        // 20 learning: s2 and s6 leave i+1; s5 stays.
        status(20, "learning")
        assertEquals(listOf(5), ids("/sentences?filter=i%2B1"))
        // an ignored-by-override entry stops counting, a new row stays new
        status(14, "blacklisted")
        assertEquals(emptyList(), ids("/sentences?filter=i%2B1"))
        assertEquals(422, client.call(HttpMethod.Get, "/sentences?filter=bogus", bearer = reader()).status.value)
    }

    @Test
    fun orderingAndCursorPagingAreStableAndDeletedMediaNeverAppears() = sApp {
        data()
        // media created_at desc, then segment ordinal, sentence ordinal; deleted media absent
        assertEquals(listOf(7, 1, 2, 3, 4, 5, 6), ids("/sentences"))
        val seen = mutableListOf<Int>()
        var cursor: String? = null
        var pages = 0
        do {
            val r = client.call(HttpMethod.Get, "/sentences?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""), bearer = reader())
            assertEquals(200, r.status.value, r.json().toString())
            val body = r.json()
            seen += body["items"].map { it["id"].asText().takeLast(2).toInt() }
            cursor = body["next_cursor"].takeUnless { it.isNull }?.asText()
            pages++
        } while (cursor != null)
        assertEquals(listOf(7, 1, 2, 3, 4, 5, 6), seen)
        assertEquals(4, pages)
        // filter + paging together; foreign cursor refused
        val r1 = client.call(HttpMethod.Get, "/sentences?filter=i%2B1&limit=2", bearer = reader()).json()
        val c = r1["next_cursor"].asText()
        assertEquals(listOf(7, 1), r1["items"].map { it["id"].asText().takeLast(2).toInt() })
        assertEquals(listOf(4, 5), ids("/sentences?filter=i%2B1&limit=2&cursor=$c"))
        assertEquals(422, client.call(HttpMethod.Get, "/sentences?limit=2&cursor=$c", bearer = reader()).status.value)
        // by media, segment, text, entry
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ids("/media/$mediaA/sentences"))
        assertEquals(listOf(6), ids("/media/$mediaA/sentences?segment_id=$segA2"))
        assertEquals(listOf(7), ids("/sentences?q=%E9%A3%9F%E3%81%B9"))
        assertEquals(listOf(2, 6), ids("/sentences?entry_seq=20"))
        assertEquals(emptyList(), ids("/sentences?media_id=$mediaGone"))
        // 404 / 410 / 404
        assertEquals(410, client.call(HttpMethod.Get, "/media/$mediaGone/sentences", bearer = reader()).status.value)
        assertEquals(404, client.call(HttpMethod.Get, "/media/01931f40-3000-7000-8000-00000000dddd/sentences", bearer = reader()).status.value)
        assertEquals(404, client.call(HttpMethod.Get, "/sentences/${sid(99)}", bearer = reader()).status.value)
        assertEquals(404, client.call(HttpMethod.Get, "/sentences/${sid(8)}", bearer = reader()).status.value, "sentence of a deleted media")
        val one = client.call(HttpMethod.Get, "/sentences/${sid(2)}", bearer = reader())
        assertEquals(200, one.status.value)
        assertEquals(2, one.json()["unknown_count"].asInt())
        assertEquals(2, one.json()["tokens"].size())
    }

    @Test
    fun grammarSpansAreAttachedWhenLiveAndTheFilterUsesThem() = sApp {
        data()
        val live = "01931f40-4000-7000-8000-000000000901"
        val dead = "01931f40-4000-7000-8000-000000000902"
        db.txBlocking {
            for ((id, deleted) in listOf(live to false, dead to true)) {
                it.execute(
                    "insert into app.grammar_point (id, title, created_at, updated_at, deleted_at) values (?::uuid, 'g', now(), now(), case when ? then now() end)",
                    id, deleted,
                )
            }
            it.execute("insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id, rule_index) values (?::uuid, ?::uuid, 1, 2, 'detected', ?::uuid, 0)", sid(1), live, mediaA)
            it.execute("insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, hlc) values (?::uuid, ?::uuid, 0, 1, 'manual', '0193a1b2c100-0000-00000000-0000-7000-8000-000000000001')", sid(1), live)
            it.execute("insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, hlc, deleted_at) values (?::uuid, ?::uuid, 0, 2, 'manual', '0193a1b2c100-0000-00000000-0000-7000-8000-000000000001', now())", sid(1), live)
            it.execute("insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id, rule_index) values (?::uuid, ?::uuid, 0, 1, 'detected', ?::uuid, 0)", sid(2), dead, mediaA)
        }
        val s1 = items("/sentences").single { it["id"].asText() == sid(1) }
        assertEquals(listOf(0 to "manual", 1 to "detected"), s1["grammar_spans"].map { it["start_offset"].asInt() to it["origin"].asText() })
        assertTrue(items("/sentences").single { it["id"].asText() == sid(2) }["grammar_spans"].isEmpty(), "span of a deleted grammar point is hidden")
        assertEquals(listOf(1), ids("/sentences?grammar_point_id=$live"))
        assertEquals(emptyList(), ids("/sentences?grammar_point_id=$dead"))
    }

    @Test
    fun entryExamplesListMediaFewestUnknownFirstThenNewestMediaAndTatoebaShortestFirst() = sApp {
        data()
        db.txBlocking {
            it.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
            it.execute(
                "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) values (10, 'x', 'x', '[{\"text\":\"x\"}]', '[]', 1)",
            )
            // sentences s1 (1 unknown), s2 (2), s4 (1), s7 (1, newer media), s8 deleted media
            it.execute(
                "insert into app.occurrence (segment_id, entry_seq, media_id, count, sentence_ids) values (?::uuid, 10, ?::uuid, 4, ?::uuid[])",
                segA1, mediaA, arrayOf(sid(1), sid(2), sid(4)),
            )
            it.execute("insert into app.occurrence (segment_id, entry_seq, media_id, count, sentence_ids) values (?::uuid, 10, ?::uuid, 1, ?::uuid[])", segB1, mediaB, arrayOf(sid(7)))
            it.execute("insert into app.occurrence (segment_id, entry_seq, media_id, count, sentence_ids) values (?::uuid, 10, ?::uuid, 1, ?::uuid[])", segGone, mediaGone, arrayOf(sid(8)))
            it.execute("insert into ref.example_sentence (id, text, translation) values (1, 'ながいながい文です', 'long'), (2, '短い', 'short'), (3, '中くらいの', ''), (4, 'unlinked', 'x')")
            it.execute("insert into ref.example_sentence_entry (sentence_id, seq) values (1, 10), (2, 10), (3, 10)")
        }
        val r = client.call(HttpMethod.Get, "/dict/entries/10/examples", bearer = reader())
        assertEquals(200, r.status.value, r.json().toString())
        val body = r.json()
        // unknown count asc (s7=1, s1=1, s4=1, s2=2), ties: media created_at desc (B before A), then id
        assertEquals(listOf(7, 1, 4, 2), body["media"].map { it["id"].asText().takeLast(2).toInt() })
        assertEquals(listOf(2L, 3L, 1L), body["tatoeba"].map { it["id"].asLong() })
        assertEquals("", body["tatoeba"][1]["translation"].asText())
        val limited = client.call(HttpMethod.Get, "/dict/entries/10/examples?limit=2", bearer = reader()).json()
        assertEquals(listOf(7, 1), limited["media"].map { it["id"].asText().takeLast(2).toInt() })
        assertEquals(listOf(2L, 3L), limited["tatoeba"].map { it["id"].asLong() })
        // known entry still lists (known entries are not special), unknown seq is 404, bad limit 422
        assertEquals(404, client.call(HttpMethod.Get, "/dict/entries/424242/examples", bearer = reader()).status.value)
        assertEquals(422, client.call(HttpMethod.Get, "/dict/entries/10/examples?limit=0", bearer = reader()).status.value)
    }
}
