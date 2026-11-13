package kotobaya.struggle

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.notes.NotesModule
import kotobaya.server.Uuid7
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T501: `listHotWords` and `listLeeches` over HTTP, with the real core replay as the card cache. */
class StruggleApiTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache())

    private val handlers by lazy { NotesModule(db, sync.opApply).handlers + StruggleModule(db) { syncNow.get() }.handlers }
    private val vocab = "00000000-0000-7000-8000-000000000101"
    private val hour = 3_600_000L

    private fun struggleApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = handlers, block = block)

    private val writer by lazy { mint(TokenKind.PERSONAL, listOf("read", "write")).second }

    private fun token() = mint(TokenKind.PERSONAL, listOf("read")).second

    private suspend fun ApplicationTestBuilder.note(t: String, expression: String): String {
        val r = client.call(HttpMethod.Post, "/notes", bearer = writer, body = mapOf("note_type_id" to vocab, "fields" to mapOf("expression" to expression), "tags" to emptyList<String>()))
        assertEquals(201, r.status.value)
        return r.json()["cards"].first { it["kind"].asText() == "meaning" }["id"].asText()
    }

    private fun review(card: String, hoursAgo: Long, rating: Int) {
        val at = syncNow.get().toEpochMilli() - hoursAgo * hour
        emit(
            OpType.REVIEW_LOGGED,
            """{"id":"${Uuid7.generate(at)}","card_id":"$card","reviewed_at":$at,"utc_offset_min":0,"review_type":"scheduled","rating":$rating}""",
        )
    }

    private suspend fun ApplicationTestBuilder.hot(t: String, query: String = ""): JsonNode {
        val r = client.call(HttpMethod.Get, "/words/hot$query", bearer = t)
        assertEquals(200, r.status.value)
        return r.json()["items"]
    }

    @Test
    fun recentFailuresAppearAndRecoveryDropsTheWordOff() = struggleApp {
        val t = token()
        val struggling = note(t, "struggling")
        val calm = note(t, "calm")
        review(calm, 5, 3)
        review(struggling, 5, 1)
        review(struggling, 4, 1)

        val items = hot(t)
        assertEquals(1, items.size())
        val item = items[0]
        assertEquals(2, item["failures_14d"].asInt())
        assertTrue(item["score"].asDouble() > 0.0)
        assertEquals("struggling", item["note"]["fields"]["expression"].asText())
        assertEquals(struggling, item["cards"][0]["id"].asText())
        assertNull(item["cards"][0].get("note"))
        assertNull(item["entry"]?.takeUnless { it.isNull })

        review(struggling, 3, 3)
        review(struggling, 2, 3)
        assertEquals(0, hot(t).size(), "two successes after the last failure: recovered")
    }

    @Test
    fun oldFailuresDoNotCountAndLimitIsEnforced() = struggleApp {
        val t = token()
        val old = note(t, "old")
        review(old, 24 * 20, 1)
        review(old, 24 * 20 - 1, 1)
        assertEquals(0, hot(t).size())

        for (n in listOf("a", "b", "c")) {
            val c = note(t, n)
            review(c, 3, 1)
            review(c, 2, 1)
        }
        assertEquals(3, hot(t).size())
        assertEquals(2, hot(t, "?limit=2").size())
        assertEquals(422, client.call(HttpMethod.Get, "/words/hot?limit=201", bearer = t).status.value)
    }

    @Test
    fun leechesListLapsesAtOrAboveTheThresholdMostFirst() = struggleApp {
        val t = token()
        val a = note(t, "leechA")
        val b = note(t, "leechB")
        val c = note(t, "fine")
        db.txBlocking { dsl ->
            dsl.execute("update app.card set lapses = 9 where id = ?::uuid", a)
            dsl.execute("update app.card set lapses = 12, suspended = true where id = ?::uuid", b)
            dsl.execute("update app.card set lapses = 7 where id = ?::uuid", c)
        }
        val r = client.call(HttpMethod.Get, "/words/leeches", bearer = t)
        assertEquals(200, r.status.value)
        assertEquals(listOf(b, a), r.json()["items"].map { it["id"].asText() })

        val page = client.call(HttpMethod.Get, "/words/leeches?limit=1", bearer = t).json()
        assertEquals(1, page["items"].size())
        assertTrue(!page["next_cursor"].isNull)

        emit(OpType.SETTINGS_SET, """{"key":"scheduler.leech_threshold","value":7}""")
        assertEquals(3, client.call(HttpMethod.Get, "/words/leeches", bearer = t).json()["items"].size())
    }

    @Test
    fun scopeAndAuthAreRequired() = struggleApp {
        assertEquals(401, client.call(HttpMethod.Get, "/words/hot").status.value)
        assertEquals(401, client.call(HttpMethod.Get, "/words/leeches").status.value)
        val noRead = mint(TokenKind.PERSONAL, listOf("write")).second
        assertEquals(403, client.call(HttpMethod.Get, "/words/hot", bearer = noRead).status.value)
        assertEquals(403, client.call(HttpMethod.Get, "/words/leeches", bearer = noRead).status.value)
    }

    @Test
    fun hotKanjiRollsUpHotWordScoresPerKanji() = struggleApp {
        val t = token()
        db.txBlocking { dsl ->
            dsl.execute("insert into ref.dict_release (id, version, built_at) values (1, 'hk', now()) on conflict do nothing")
            for ((seq, head) in listOf(9001L to "績績", 9002L to "績x", 9003L to "果")) {
                dsl.execute(
                    "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) " +
                        "values (?, ?, 'あ', '[{\"text\":\"あ\"}]'::jsonb, '[]'::jsonb, 1)",
                    seq, head,
                )
            }
            dsl.execute("insert into ref.entry_kanji (seq, literal) values (9001,'績'),(9002,'績'),(9003,'果')")
            dsl.execute("insert into ref.kanji (literal, meanings) values ('績', array['achievement'])")
        }
        val a = note(t, "wordA")
        val b = note(t, "wordB")
        val c = note(t, "wordC")
        for ((card, seq) in listOf(a to 9001L, b to 9002L, c to 9003L)) {
            db.txBlocking { it.execute("update app.note set entry_seq = ? where id = (select note_id from app.card where id = ?::uuid)", seq, card) }
            review(card, 5, 1)
            review(card, 4, 1)
        }
        val r = client.call(HttpMethod.Get, "/kanji/hot", bearer = t)
        assertEquals(200, r.status.value)
        val items = r.json()["items"]
        assertEquals(listOf("績", "果"), items.map { it["literal"].asText() })
        assertEquals(2, items[0]["word_count"].asInt())
        assertEquals(1, items[1]["word_count"].asInt())
        assertTrue(items[0]["score"].asDouble() > items[1]["score"].asDouble())
        assertEquals(listOf("achievement"), items[0]["meanings"].map { it.asText() })
        assertTrue(items[1]["meanings"].isEmpty)
        assertTrue(items[0]["note_id"].isNull)
        assertEquals(1, client.call(HttpMethod.Get, "/kanji/hot?limit=1", bearer = t).json()["items"].size())
        assertEquals(422, client.call(HttpMethod.Get, "/kanji/hot?limit=0", bearer = t).status.value)
        assertEquals(401, client.call(HttpMethod.Get, "/kanji/hot").status.value)
    }
}
