package kotobaya.review

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.notes.NotesModule
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ARCH-CARD-TEXT: `QueueItem.text` is the core's plain-text render, from the item's own note, entry, kanji and cloze. */
class QueueTextTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache())

    private val notes by lazy { NotesModule(db, sync.opApply) }
    private val review by lazy { ReviewModule(db, sync.opApply) }
    private fun reviewApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = notes.handlers + review.handlers, block = block)

    private val vocab = "00000000-0000-7000-8000-000000000101"
    private val kanji = "00000000-0000-7000-8000-000000000102"
    private val phrase = "00000000-0000-7000-8000-000000000103"
    private val grammar = "00000000-0000-7000-8000-000000000104"
    private val sentence = "00000000-0000-7000-8000-000000000105"
    private val custom = "00000000-0000-7000-8000-000000000106"
    private val seq = 1358280L
    private val gid = "01931f40-7000-7000-8000-000000000001"
    private val media = "01931f40-3000-7000-8000-000000000f01"
    private val seg = "01931f40-3100-7000-8000-000000000f11"
    private val sid = "01931f40-5000-7000-8000-000000000801"

    private fun token() = mint(TokenKind.PERSONAL, listOf("read", "write", "review")).second

    private fun seedReference() = db.txBlocking {
        it.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
        it.execute(
            "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) values (?, '食べる', 'たべる', " +
                "'[{\"text\":\"たべる\"}]', '[{\"gloss\":[{\"lang\":\"eng\",\"text\":\"to eat\"}]},{\"gloss\":[{\"lang\":\"eng\",\"text\":\"to live on (e.g. a salary)\"}]}]', 1)",
            seq,
        )
        it.execute("insert into ref.entry_pitch (seq, reading, positions) values (?, 'たべる', '{2}')", seq)
        it.execute("insert into ref.kanji (literal, meanings, on_readings, kun_readings) values ('食', array['eat','food'], array['ショク'], array['た.べる'])")
        it.execute("insert into app.grammar_point (id, title, created_at, updated_at) values (?::uuid, 'g', now(), now())", gid)
        it.execute("insert into app.media (id, title, kind, source, parse_status, content_version, created_at, updated_at) values (?::uuid, 'm', 'anime', 'file', 'parsed', 1, now(), now())", media)
        it.execute("insert into app.media_segment (id, media_id, ordinal, kind) values (?::uuid, ?::uuid, 0, 'episode')", seg, media)
        it.execute("insert into app.sentence (id, media_id, segment_id, ordinal, text, tokens, origin) values (?::uuid, ?::uuid, ?::uuid, 1, '行かざるを得ない。', '[]'::jsonb, 'media')", sid, media, seg)
        it.execute(
            "insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id, rule_index) values (?::uuid, ?::uuid, 2, 8, 'detected', ?::uuid, 0)",
            sid, gid, media,
        )
    }

    /**
     * Creates a note and one card per kind through ops (`note.created` + `card.created`, the path a synced
     * Takoboto note takes; REST `createNote` insists on at least one field); returns kind → card id.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun note(t: String, body: Map<String, Any?>, kinds: List<String>): Map<String, String> {
        val noteId = Uuid7.generate().toString()
        emit(OpType.NOTE_CREATED, json.writeValueAsString(body + ("id" to noteId)))
        return kinds.associateWith { k ->
            val cardId = Uuid7.generate().toString()
            emit(OpType.CARD_CREATED, json.writeValueAsString(mapOf("id" to cardId, "note_id" to noteId, "kind" to k)))
            cardId
        }
    }

    private fun text(front: String, back: String, reading: String?, sentence: String?) =
        listOf(front, back, reading, sentence)

    private fun JsonNode.text(): List<String?> {
        val t = this["text"]
        assertTrue(t != null && t.isObject, "item carries text: $this")
        return listOf("front", "back", "reading", "sentence").map { k -> t[k].takeUnless { it.isNull }?.asText() }
    }

    @Test
    fun pitchComesOnlyFromTheHeadwordReadingsRow() {
        val entry = json.readTree(
            """{"headword":"今日","headword_reading":"きょう","senses":[],"pitch":[{"reading":"こんにち","positions":[1]}]}""",
        )
        assertEquals(emptyList(), QueueText.dictionaryData(entry).pitchDrops)
    }

    @Test
    fun takobotoShapedNoteRendersFrontAndBackFromTheDictionaryInTheQueue() = reviewApp {
        seedReference()
        val t = token()
        // Takoboto shape: no field values at all, only the matched entry
        val cards = note(t, mapOf("note_type_id" to vocab, "fields" to emptyMap<String, String>(), "entry_seq" to seq), listOf("meaning", "reading"))
        val r = client.call(HttpMethod.Get, "/review/queue", bearer = t)
        assertEquals(200, r.status.value)
        val items = r.json()["items"]
        assertTrue(items.size() >= 1)
        for (item in items) {
            assertTrue(item["card"]["id"].asText() in cards.values)
            // meaning and reading cards read the same as text
            assertEquals(text("食べる", "たべる\nto eat; to live on (e.g. a salary)", "たべる", null), item.text())
        }
    }

    @Test
    fun everyBuiltInKindCarriesItsText() = reviewApp {
        seedReference()
        val t = token()
        val ids = LinkedHashMap<String, String>() // "<base>/<kind>" -> card id
        fun put(base: String, m: Map<String, String>) = m.forEach { (k, id) -> ids["$base/$k"] = id }
        put("vocab", note(t, mapOf("note_type_id" to vocab, "entry_seq" to seq), listOf("meaning", "reading", "listening", "production", "pitch")))
        put("kanji", note(t, mapOf("note_type_id" to kanji, "kanji_literal" to "食"), listOf("keyword", "writing", "components")))
        put(
            "phrase",
            note(
                t,
                mapOf("note_type_id" to phrase, "fields" to mapOf("expression" to "お疲れ様", "reading" to "お 疲[つか]れ 様[さま]", "meaning" to "thanks")),
                listOf("meaning", "production", "listening"),
            ),
        )
        put(
            "grammar",
            note(
                t,
                mapOf("note_type_id" to grammar, "grammar_point_id" to gid, "fields" to mapOf("pattern" to "〜ざるを得ない", "meaning" to "cannot help but")),
                listOf("cloze", "recognition", "spot", "production"),
            ),
        )
        put(
            "sentence",
            note(
                t,
                mapOf("note_type_id" to sentence, "fields" to mapOf("sentence" to "猫[ねこ]が 好[す]きです。", "reading" to "猫[ねこ]が 好[す]きです。", "translation" to "I like cats.")),
                listOf("comprehension", "listening"),
            ),
        )
        put("custom", note(t, mapOf("note_type_id" to custom, "fields" to mapOf("front" to "What is <b>2+2</b>?", "back" to "4")), listOf("basic")))

        val meaning = "to eat; to live on (e.g. a salary)"
        val expected = mapOf(
            "vocab/meaning" to text("食べる", "たべる\n$meaning", "たべる", null),
            "vocab/reading" to text("食べる", "たべる\n$meaning", "たべる", null),
            "vocab/listening" to text("", "食べる\nたべる\n$meaning", "たべる", null),
            "vocab/production" to text(meaning, "食べる\nたべる", "たべる", null),
            "vocab/pitch" to text("食べる\nたべる", "たべꜜる", "たべる", null),
            "kanji/keyword" to text("食", "eat; food\nショク、た.べる", "ショク、た.べる", null),
            "kanji/writing" to text("eat; food", "食\nショク、た.べる", "ショク、た.べる", null),
            "kanji/components" to text("食", "", "ショク、た.べる", null),
            "phrase/meaning" to text("お疲れ様", "おつかれさま\nthanks", "おつかれさま", null),
            "phrase/production" to text("thanks", "お疲れ様\nおつかれさま", "おつかれさま", null),
            "phrase/listening" to text("", "お疲れ様\nおつかれさま\nthanks", "おつかれさま", null),
            "grammar/cloze" to text("行か＿＿＿。", "行か【ざるを得ない】。\n〜ざるを得ない\ncannot help but", null, null),
            "grammar/spot" to text("行かざるを得ない。", "行か【ざるを得ない】。\n〜ざるを得ない\ncannot help but", null, null),
            "grammar/recognition" to text("〜ざるを得ない", "cannot help but", null, null),
            "grammar/production" to text("cannot help but", "〜ざるを得ない", null, null),
            "sentence/comprehension" to text("猫が好きです。", "ねこがすきです。\nI like cats.", "ねこがすきです。", null),
            "sentence/listening" to text("", "猫が好きです。\nねこがすきです。\nI like cats.", "ねこがすきです。", null),
            "custom/basic" to text("What is 2+2?", "4", null, null),
        )
        assertEquals(expected.keys, ids.keys)

        // every kind, through createDrillSession (QueueItem with the same builder as the queue)
        val r = client.call(HttpMethod.Post, "/drill/session", bearer = t, body = mapOf("source" to "cards", "card_ids" to ids.values.toList(), "limit" to 500))
        assertEquals(200, r.status.value, r.json().toString())
        val byCard = r.json()["items"].associateBy { it["card"]["id"].asText() }
        for ((key, id) in ids) assertEquals(expected.getValue(key), byCard.getValue(id).text(), key)

        // and the review queue's items carry the same text
        val q = client.call(HttpMethod.Get, "/review/queue?limit=200", bearer = t)
        assertEquals(200, q.status.value)
        val keyOf = ids.entries.associate { (k, v) -> v to k }
        val items = q.json()["items"]
        assertTrue(items.size() > 0)
        for (item in items) {
            val key = keyOf.getValue(item["card"]["id"].asText())
            assertEquals(expected.getValue(key), item.text(), "queue $key")
        }
    }
}
