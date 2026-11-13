package kotobaya.dict

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotobaya.server.AppDeps
import kotobaya.server.json
import kotobaya.server.kotobayaModule
import kotlin.test.Test
import kotlin.test.assertEquals

class EntryExamplesTest {
    private val withExamples = 1358340L
    private val without = 1259290L
    private val mediaSeq = 1169870L

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val db = DictFixture.db
        db.txBlocking { dsl ->
            dsl.execute("delete from ref.example_sentence")
            dsl.execute("insert into ref.example_sentence (id, text, translation) values (2, '食べ物が好きです。', 'I like food.'), (1, '食べ物。', ''), (3, '無関係。', 'x')")
            dsl.execute("insert into ref.example_sentence_entry (sentence_id, seq) values (1, ?), (2, ?), (3, ?)", withExamples, withExamples, 1169870L)
        }
        application { kotobayaModule(AppDeps(db, handlers = dictHandlers(db))) }
        block()
    }

    @Test
    fun linkedSentencesAreReturnedShortestFirstAndLimited() = app {
        val r = client.get("/api/v1/dict/entries/$withExamples/examples")
        assertEquals(200, r.status.value)
        val body = json.readTree(r.bodyAsText())
        assertEquals(listOf(1L, 2L), body["tatoeba"].map { it["id"].asLong() })
        assertEquals("I like food.", body["tatoeba"][1]["translation"].asText())
        assertEquals("", body["tatoeba"][0]["translation"].asText())
        assertEquals(0, body["media"].size())
        val one = json.readTree(client.get("/api/v1/dict/entries/$withExamples/examples?limit=1").bodyAsText())
        assertEquals(1, one["tatoeba"].size())
    }

    @Test
    fun entryWithoutExamplesIsEmpty200AndUnknownSeqIs404() = app {
        val r = client.get("/api/v1/dict/entries/$without/examples")
        assertEquals(200, r.status.value)
        val body = json.readTree(r.bodyAsText())
        assertEquals(0, body["tatoeba"].size())
        assertEquals(0, body["media"].size())
        assertEquals(404, client.get("/api/v1/dict/entries/999999999/examples").status.value)
        assertEquals(422, client.get("/api/v1/dict/entries/$without/examples?limit=51").status.value)
    }

    @Test
    fun mediaSentencesComeThroughOccurrences() = app {
        val db = DictFixture.db
        val m = "01931f40-3000-7000-8000-000000000e01"
        val seg = "01931f40-3100-7000-8000-000000000e11"
        val s1 = "01931f40-5000-7000-8000-000000000701"
        val s2 = "01931f40-5000-7000-8000-000000000702"
        db.txBlocking { dsl ->
            dsl.execute("insert into app.media (id, title, kind, source, created_at, updated_at) values (?::uuid, 'T', 'anime', 'paste', now(), now()) on conflict do nothing", m)
            dsl.execute("insert into app.media_segment (id, media_id, ordinal, kind) values (?::uuid, ?::uuid, 0, 'whole') on conflict do nothing", seg, m)
            dsl.execute("insert into app.sentence (id, media_id, segment_id, ordinal, text, unknown_count) values (?::uuid, ?::uuid, ?::uuid, 0, 'a', 3), (?::uuid, ?::uuid, ?::uuid, 1, 'b', 1) on conflict do nothing", s1, m, seg, s2, m, seg)
            dsl.execute("insert into app.occurrence (segment_id, entry_seq, media_id, count, sentence_ids) values (?::uuid, ?, ?::uuid, 2, array[?::uuid, ?::uuid]) on conflict do nothing", seg, mediaSeq, m, s1, s2)
        }
        val body = json.readTree(client.get("/api/v1/dict/entries/$mediaSeq/examples").bodyAsText())
        assertEquals(listOf(s2, s1), body["media"].map { it["id"].asText() })
        assertEquals(0, body["media"][0]["grammar_spans"].size())
    }
}
