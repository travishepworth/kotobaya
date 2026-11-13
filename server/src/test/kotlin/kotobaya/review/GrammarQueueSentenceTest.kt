package kotobaya.review

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.notes.NotesModule
import kotobaya.server.Uuid7
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-25: the queue's grammar_sentence is a pure function of replica state (C-15): refetch and undo show the same sentence. */
class GrammarQueueSentenceTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache())

    private val notes by lazy { NotesModule(db, sync.opApply) }
    private val review by lazy { ReviewModule(db, sync.opApply) }
    private val gid = "01931f40-7000-7000-8000-000000000001"
    private val media = "01931f40-3000-7000-8000-000000000f01"
    private val seg = "01931f40-3100-7000-8000-000000000f11"
    private fun sid(n: Int) = "01931f40-5000-7000-8000-00000000080$n"

    private fun setup() = db.txBlocking {
        it.execute("insert into app.grammar_point (id, title, created_at, updated_at) values (?::uuid, 'g', now(), now())", gid)
        it.execute("insert into app.media (id, title, kind, source, parse_status, content_version, created_at, updated_at) values (?::uuid, 'm', 'anime', 'file', 'parsed', 1, now(), now())", media)
        it.execute("insert into app.media_segment (id, media_id, ordinal, kind) values (?::uuid, ?::uuid, 0, 'episode')", seg, media)
        for (n in 1..3) {
            it.execute("insert into app.sentence (id, media_id, segment_id, ordinal, text, tokens, origin) values (?::uuid, ?::uuid, ?::uuid, ?, ?, '[]'::jsonb, 'media')", sid(n), media, seg, n, "ざるを得ない$n")
            it.execute(
                "insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id, rule_index) values (?::uuid, ?::uuid, 0, 2, 'detected', ?::uuid, 0)",
                sid(n), gid, media,
            )
        }
    }

    private suspend fun ApplicationTestBuilder.shown(t: String): JsonNode {
        val resp = client.call(HttpMethod.Get, "/review/queue", bearer = t)
        val body = resp.json()
        assertEquals(200, resp.status.value, body.toString())
        val items = body["items"]
        return items.single()
    }

    @Test
    fun refetchAndUndoShowTheSameSentenceAndAnswerMovesOn() = app(extraHandlers = notes.handlers + review.handlers) {
        setup()
        val t = mint(TokenKind.PERSONAL, listOf("read", "write", "review")).second
        val created = client.call(
            HttpMethod.Post, "/notes", bearer = t,
            body = mapOf("note_type_id" to "00000000-0000-7000-8000-000000000104", "fields" to mapOf("pattern" to "〜ざるを得ない"), "grammar_point_id" to gid),
        )
        assertEquals(201, created.status.value)
        val card = created.json()["cards"].first { it["kind"].asText() == "cloze" }["id"].asText()

        val first = shown(t)
        assertEquals(sid(1), first["grammar_sentence"]["sentence_id"].asText())
        assertEquals(sid(1), first["sentence"]["id"].asText())
        assertEquals(0, first["grammar_sentence"]["rotation"].asInt())
        assertEquals(sid(1), shown(t)["grammar_sentence"]["sentence_id"].asText(), "refetch is stable")

        val answerId = Uuid7.generate().toString()
        val ans = client.call(
            HttpMethod.Post, "/review/answer", bearer = t,
            body = mapOf("id" to answerId, "card_id" to card, "rating" to 3, "sentence_id" to sid(1)),
        )
        assertEquals(200, ans.status.value)
        val second = shown(t)
        assertEquals(sid(2), second["grammar_sentence"]["sentence_id"].asText())
        assertEquals(sid(1), second["grammar_sentence"]["last_shown_sentence_id"].asText())
        assertTrue(second["grammar_sentence"]["source"]["media_title"].asText() == "m")

        assertEquals(200, client.call(HttpMethod.Post, "/review/undo", bearer = t, body = mapOf("review_id" to answerId)).status.value)
        assertEquals(sid(1), shown(t)["grammar_sentence"]["sentence_id"].asText(), "undo restores the earlier sentence")
    }
}
