package kotobaya.review

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.decks.DecksModule
import kotobaya.notes.NotesModule
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SRV-28 (ADR-038..044): `until_stop`, kanji notes minted for drawn vocab (step A, once, never again once the user deleted
 * one), `kanji_parts`, `front_furigana` and `Deck.progress` - all decided by the core, here only wired.
 */
class KanjiFirstTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache())

    private val notes by lazy { NotesModule(db, sync.opApply) }
    private val review by lazy { ReviewModule(db, sync.opApply) }
    private val decks by lazy { DecksModule(db, sync.opApply) }
    private fun reviewApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        app(extraHandlers = notes.handlers + review.handlers + decks.handlers, block = block)

    private fun token(scopes: List<String> = listOf("read", "write", "review")) = mint(TokenKind.PERSONAL, scopes).second
    private val vocab = "00000000-0000-7000-8000-000000000101"

    private fun kanjiRow(literal: String, grade: Int?, on: String, kun: String = "", meaning: String = "m") = db.txBlocking {
        it.execute(
            "insert into ref.kanji (literal, meanings, on_readings, kun_readings, grade) values (?, array[?], array[?], " +
                (if (kun.isEmpty()) "'{}'" else "array['$kun']") + ", ?)",
            literal, meaning, on, grade,
        )
    }

    /** A deckless vocab note with a meaning card, through ops; returns (note id, card id). */
    private fun vocabNote(expression: String, reading: String): Pair<String, String> {
        val noteId = Uuid7.generate().toString()
        emit(OpType.NOTE_CREATED, json.writeValueAsString(mapOf("id" to noteId, "note_type_id" to vocab, "fields" to mapOf("expression" to expression, "reading" to reading, "meaning" to "m"))))
        val cardId = Uuid7.generate().toString()
        emit(OpType.CARD_CREATED, json.writeValueAsString(mapOf("id" to cardId, "note_id" to noteId, "kind" to "meaning")))
        return noteId to cardId
    }

    private suspend fun ApplicationTestBuilder.fetch(t: String, query: String = ""): JsonNode {
        val r = client.call(HttpMethod.Get, "/review/queue$query", bearer = t)
        assertEquals(200, r.status.value)
        return r.json()
    }

    private fun autoKanji(): Set<String> =
        db.txBlocking { d -> d.fetch("select kanji_literal from app.note where origin = 'auto_kanji' and deleted_at is null").map { it.get(0, String::class.java) }.toSet() }

    @Test
    fun aDrawnDecklessWordMintsItsKanjiOnceAndNeverRecreatesADeletedOne() = reviewApp {
        kanjiRow("学", 1, "ガク", "まな.ぶ", "study")
        kanjiRow("校", 1, "コウ", meaning = "school")
        val (_, wordCard) = vocabNote("学校", "がっこう")

        // A read-only caller cannot mint: the word is withheld and the kanji are pending.
        val ro = fetch(token(listOf("read")))
        assertEquals(0, ro["items"].size())
        assertEquals(2, ro["to_materialize"].asInt())
        assertEquals(emptySet(), autoKanji())

        val t = token()
        val q = fetch(t)
        assertEquals(setOf("学", "校"), autoKanji())
        assertEquals(listOf("kanji", "kanji", "vocab"), q["items"].map { it["card"]["note"]["type"].asText() })
        assertEquals(listOf("学", "校"), q["items"].take(2).map { it["card"]["note"]["kanji_literal"].asText() })
        assertEquals(wordCard, q["items"][2]["card"]["id"].asText())
        assertEquals(0, q["to_materialize"].asInt())
        assertEquals(3, q["loop"]["until_stop"].asInt())
        assertEquals(2L, changes("note.created", origin = true))

        // Idempotent on re-fetch.
        val head = count("select max(seq) from app.change")
        val again = fetch(t)
        assertEquals(q["items"].map { it["card"]["id"] }, again["items"].map { it["card"]["id"] })
        assertEquals(head, count("select max(seq) from app.change"))

        // ADR-041/042 on the word's item: two tiles, the reading each kanji uses, no furigana front.
        val item = q["items"][2]
        assertEquals(false, item["front_furigana"].asBoolean())
        val parts = item["kanji_parts"]
        assertEquals(listOf("学", "校"), parts.map { it["literal"].asText() })
        assertEquals("study", parts[0]["keyword"].asText())
        assertEquals("on", parts[0]["used_reading"]["type"].asText())
        assertEquals("ガク", parts[0]["used_reading"]["reading"].asText())
        assertEquals("がっ", parts[0]["used_reading"]["kana"].asText())
        assertEquals("school", parts[1]["keyword"].asText())
        assertTrue(q["items"][0]["kanji_parts"] == null, "kanji items carry no kanji_parts")

        // A kanji note the user deleted is never created again.
        val gaku = db.txBlocking { it.fetchValue("select id::text from app.note where kanji_literal = '学' and deleted_at is null") as String }
        assertEquals(204, client.call(HttpMethod.Delete, "/notes/$gaku", bearer = t).status.value)
        fetch(t)
        fetch(t)
        assertEquals(setOf("校"), autoKanji())
        assertEquals(0L, count("select count(*) from app.note where kanji_literal = '学' and deleted_at is null"))
    }

    private fun changes(type: String, origin: Boolean = false): Long =
        if (origin) count("select count(*) from app.note where origin = 'auto_kanji'") else count("select count(*) from app.change where op_type = ?", type)

    @Test
    fun aWordThatRequiresNoKanjiHasAFuriganaFrontAndMintsNothing() = reviewApp {
        kanjiRow("鱈", null, "セツ") // no grade, no frequency rank: not learnable
        vocabNote("鱈", "たら")
        val t = token()
        val q = fetch(t)
        assertEquals(1, q["items"].size())
        val item = q["items"][0]
        assertEquals(true, item["front_furigana"].asBoolean())
        assertEquals("鱈（たら）", item["text"]["front"].asText())
        assertEquals(emptySet(), autoKanji())
        assertEquals(1, item["kanji_parts"].size(), "the breakdown shows every kanji, learnable or not")

        // The setting off: every kanji is required again, and no front has furigana.
        emit(OpType.SETTINGS_SET, """{"key":"cards.kanji_exemptions","value":false}""")
        val off = fetch(t)
        assertEquals(setOf("鱈"), autoKanji())
        assertEquals(listOf("kanji", "vocab"), off["items"].map { it["card"]["note"]["type"].asText() })
        assertEquals(false, off["items"][1]["front_furigana"].asBoolean())
    }

    @Test
    fun deckProgressCountsLearnedWordsAndIgnoresRetiredReadingCardsAndBlacklistedWords() = reviewApp {
        val t = token()
        db.txBlocking {
            it.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
            for (seq in 7001..7007) {
                it.execute(
                    "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) " +
                        "values (?, 'たべる', 'たべる', '[{\"text\":\"たべる\"}]', '[]', 1)",
                    seq,
                )
            }
        }
        val deck = UUID.randomUUID().toString()
        emit(OpType.DECK_CREATED, """{"id":"$deck","name":"P","type":"user"}""")
        for (seq in 7001..7007) emit(OpType.DECK_ITEM_PUT, """{"deck_id":"$deck","item_key":"e:$seq","position":${seq * 1024}}""")
        fun note(seq: Int, vararg kinds: String): Map<String, String> {
            val noteId = Uuid7.generate().toString()
            emit(OpType.NOTE_CREATED, json.writeValueAsString(mapOf("id" to noteId, "note_type_id" to vocab, "entry_seq" to seq, "fields" to emptyMap<String, String>())))
            return kinds.associateWith { k ->
                val c = Uuid7.generate().toString()
                emit(OpType.CARD_CREATED, json.writeValueAsString(mapOf("id" to c, "note_id" to noteId, "kind" to k)))
                c
            }
        }
        fun state(card: String, s: String) = db.txBlocking { it.execute("update app.card set state = ?, interval_days = 30, stability = 30, difficulty = 5, due_at = now() + interval '30 days', step = null where id = ?::uuid", s, card) }
        state(note(7001, "meaning").getValue("meaning"), "review") // learned
        state(note(7002, "meaning").getValue("meaning"), "learning") // started, not learned
        note(7003, "meaning") // new
        state(note(7004, "reading").getValue("reading"), "review") // only a retired reading card in REVIEW: not learned
        // 7005: no note, status known -> learned; 7006: blacklisted -> not counted; 7007: no note, nothing.
        emit(OpType.WORD_STATUS_SET, """{"entry_seq":7005,"status":"known","origin":"manual"}""")
        emit(OpType.WORD_STATUS_SET, """{"entry_seq":7006,"status":"blacklisted","origin":"manual"}""")
        val smart = UUID.randomUUID().toString()
        emit(OpType.DECK_CREATED, """{"id":"$smart","name":"S","type":"smart","query":"deck:P"}""")

        val got = client.call(HttpMethod.Get, "/decks/$deck", bearer = t).json()
        assertEquals(2, got["progress"]["learned"].asInt())
        assertEquals(6, got["progress"]["total"].asInt())
        assertEquals(33, got["progress"]["percent"].asInt())
        val list = client.call(HttpMethod.Get, "/decks", bearer = t).json()["items"]
        assertEquals(got["progress"], list.first { it["id"].asText() == deck }["progress"])
        assertTrue(list.first { it["id"].asText() == smart }["progress"].isNull, "a smart deck has no progress")
    }
}
