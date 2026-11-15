package kotobaya.review

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.dict.dictHandlers
import kotobaya.notes.NotesModule
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import org.jooq.ExecuteListener
import org.jooq.impl.DSL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C-25: `QueueItem.decks` / `deck_count` / `kanji_examples` and `deck_ids` on `DictEntry`, `DictEntrySummary` and
 * `Note`; and that the queue's extras cost a fixed number of statements per page.
 */
class QueueExtrasTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache())

    private val notes by lazy { NotesModule(db, sync.opApply) }
    private val review by lazy { ReviewModule(db, sync.opApply) }
    private fun reviewApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        app(extraHandlers = notes.handlers + review.handlers + dictHandlers(db), block = block)

    private val vocabType = "00000000-0000-7000-8000-000000000101"
    private val kanjiType = "00000000-0000-7000-8000-000000000102"
    private val phraseType = "00000000-0000-7000-8000-000000000103"

    private val taberu = 1358280L
    private val shokuji = 1358500L
    private val shokudou = 1358600L
    private val shokuhin = 1358700L

    // draw order: d2 (priority 1), d3 (5, lower id), d1 (5), d4 (9), d5 (20)
    private val gone = "01931f40-2000-7000-8000-00000000a000"
    private val d3 = "01931f40-2000-7000-8000-00000000a001"
    private val d2 = "01931f40-2000-7000-8000-00000000a002"
    private val d1 = "01931f40-2000-7000-8000-00000000a003"
    private val d4 = "01931f40-2000-7000-8000-00000000a004"
    private val d5 = "01931f40-2000-7000-8000-00000000a005"
    private val smart = "01931f40-2000-7000-8000-00000000a006"

    private fun token() = mint(TokenKind.PERSONAL, listOf("read", "write", "review")).second

    private fun seedReference() = db.txBlocking { d ->
        d.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
        d.execute("insert into ref.dict_release (id, version, built_at) values (2, 'u', now()) on conflict do nothing")
        // seq, headword, reading, rank, first-sense glosses
        val entries = listOf(
            listOf(taberu, "食べる", "たべる", 312, """[{"text":"to eat"}]"""),
            listOf(shokuji, "食事", "しょくじ", 480, """[{"text":"meal"},{"text":"dinner"}]"""),
            listOf(shokudou, "食堂", "しょくどう", 1500, """[{"text":"dining room"}]"""),
            listOf(shokuhin, "食品", "しょくひん", 900, """[{"text":"food"}]"""),
            listOf(1358900L, "食う", "くう", 2, """[{"text":"to eat (vulgar)"}]"""), // blacklisted below
            listOf(1358910L, "食欲", "しょくよく", 1, """[{"text":"appetite"}]"""), // retired below
            listOf(1358920L, "喰らう", "くらう", 3, """[{"text":"to eat"}]"""), // indexed under 食 through another form
        )
        for ((seq, hw, reading, rank, gloss) in entries) {
            d.execute(
                "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, freq_rank, added_release_id) " +
                    "values (?, ?, ?, ?::jsonb, ?::jsonb, ?, 1)",
                seq, hw, reading, """[{"text":"$reading"}]""", """[{"gloss":$gloss},{"gloss":[{"text":"second sense"}]}]""", rank,
            )
            d.execute("insert into ref.entry_kanji (seq, literal) values (?, '食')", seq)
        }
        d.execute("update ref.dict_entry set retired_release_id = 2 where seq = 1358910")
        d.execute(
            "insert into app.word_status (entry_seq, status, override_status, override_hlc, override_origin, updated_at) values " +
                "(1358900, 'blacklisted', 'blacklisted', '000000000001-0000-00000000-0000-7000-8000-000000000001', 'manual', now())",
        )
        d.execute("insert into ref.kanji (literal, meanings, on_readings, kun_readings) values ('食', array['eat','food'], array['ショク','ジキ'], array['く.う','た.べる'])")
    }

    private fun seedDecks() = db.txBlocking { d ->
        fun deck(id: String, name: String, priority: Int, type: String = "user", deleted: Boolean = false) = d.execute(
            "insert into app.deck (id, name, type, priority, query, created_at, updated_at, deleted_at) values (?::uuid, ?, ?, ?, ?, now(), now(), " +
                (if (deleted) "now()" else "null") + ")",
            id, name, type, priority, if (type == "smart") "state:new" else null,
        )
        deck(d1, "Core", 5); deck(d2, "Frieren", 1); deck(d3, "Anime", 5); deck(d4, "Mined", 9); deck(d5, "Later", 20)
        deck(gone, "Gone", 0, deleted = true); deck(smart, "Smart", 0, type = "smart")
    }

    private fun entryItem(deck: String, seq: Long, deleted: Boolean = false) = db.txBlocking { d ->
        d.execute(
            "insert into app.deck_item (deck_id, item_key, entry_seq, added_at, deleted_at) values (?::uuid, ?, ?, now(), " +
                (if (deleted) "now()" else "null") + ")",
            deck, "e:$seq", seq,
        )
    }

    private fun noteItem(deck: String, noteId: String) = db.txBlocking { d ->
        d.execute("insert into app.deck_item (deck_id, item_key, note_id, added_at) values (?::uuid, ?, ?::uuid, now())", deck, "n:$noteId", noteId)
    }

    /** A note and one card through ops; returns (note id, card id). */
    private fun note(body: Map<String, Any?>, kind: String): Pair<String, String> {
        val noteId = Uuid7.generate().toString()
        emit(OpType.NOTE_CREATED, json.writeValueAsString(body + ("id" to noteId)))
        val cardId = Uuid7.generate().toString()
        emit(OpType.CARD_CREATED, json.writeValueAsString(mapOf("id" to cardId, "note_id" to noteId, "kind" to kind)))
        return noteId to cardId
    }

    private fun JsonNode.strings() = map { it.asText() }

    private class Seeded(val vocab: Pair<String, String>, val kanji: Pair<String, String>, val phrase: Pair<String, String>)

    private fun seedAll(): Seeded {
        seedReference()
        seedDecks()
        val vocab = note(mapOf("note_type_id" to vocabType, "entry_seq" to taberu), "meaning")
        val kanji = note(mapOf("note_type_id" to kanjiType, "kanji_literal" to "食"), "keyword")
        val phrase = note(mapOf("note_type_id" to phraseType, "fields" to mapOf("expression" to "お疲れ様", "meaning" to "thanks")), "meaning")
        for (deck in listOf(d1, d2, d3, d4, gone)) entryItem(deck, taberu)
        entryItem(d5, taberu, deleted = true)
        entryItem(d1, shokudou)
        noteItem(d4, phrase.first)
        // an item naming a merge loser of the phrase note counts for the survivor
        val loser = Uuid7.generate().toString()
        db.txBlocking { d ->
            val device = d.fetchValue("select created_by_device_id::text from app.note where id = ?::uuid", phrase.first) as String
            d.execute(
                "insert into app.note (id, note_type_id, type, created_by_device_id, created_at, updated_at, deleted_at, merged_into_id) " +
                    "values (?::uuid, ?::uuid, 'phrase', ?::uuid, now(), now(), now(), ?::uuid)",
                loser, phraseType, device, phrase.first,
            )
        }
        noteItem(d2, loser)
        return Seeded(vocab, kanji, phrase)
    }

    @Test
    fun queueItemsCarryDecksAndKanjiExamples() = reviewApp {
        val s = seedAll()
        val t = token()
        val cards = listOf(s.vocab.second, s.kanji.second, s.phrase.second)
        val r = client.call(HttpMethod.Post, "/drill/session", bearer = t, body = mapOf("source" to "cards", "card_ids" to cards, "limit" to 500))
        assertEquals(200, r.status.value, r.json().toString())
        val byCard = r.json()["items"].associateBy { it["card"]["id"].asText() }
        assertEquals(cards.toSet(), byCard.keys)

        // vocab: four live decks hold e:<seq>; the first three in draw order (priority, then id), with name and priority
        val vocab = byCard.getValue(s.vocab.second)
        assertEquals(listOf(d2, d3, d1), vocab["decks"].map { it["id"].asText() })
        assertEquals(listOf("Frieren", "Anime", "Core"), vocab["decks"].map { it["name"].asText() })
        assertEquals(listOf(1, 5, 5), vocab["decks"].map { it["priority"].asInt() })
        assertEquals(4, vocab["deck_count"].asInt())
        assertFalse(vocab.has("kanji_examples"), "only kanji notes carry kanji_examples")
        assertEquals(listOf(d2, d3, d1, d4), vocab["entry"]["deck_ids"].strings())

        // kanji: mine first (食べる has a note, 食堂 is in a deck), one ON and one KUN, then by frequency;
        // the blacklisted 食う, the retired 食欲 and 喰らう (headword without 食) are never shown
        val kanji = byCard.getValue(s.kanji.second)
        assertEquals(
            listOf("$taberu 食べる たべる kun to eat", "$shokudou 食堂 しょくどう on dining room", "$shokuji 食事 しょくじ on meal; dinner"),
            kanji["kanji_examples"].map { e -> listOf("seq", "headword", "reading", "reading_type", "gloss").joinToString(" ") { e[it].asText() } },
        )
        assertEquals(0, kanji["decks"].size())
        assertEquals(0, kanji["deck_count"].asInt())

        // phrase: n:<id> items, one of them through a merge loser
        val phrase = byCard.getValue(s.phrase.second)
        assertEquals(listOf(d2, d4), phrase["decks"].map { it["id"].asText() })
        assertEquals(2, phrase["deck_count"].asInt())

        // the review queue builds its items the same way
        val q = client.call(HttpMethod.Get, "/review/queue?limit=200", bearer = t)
        assertEquals(200, q.status.value)
        // C-27: the queue also materializes deck entries without notes (e.g. 食堂); compare the seeded cards only.
        val items = q.json()["items"].filter { it["card"]["id"].asText() in byCard }
        assertTrue(items.isNotEmpty())
        for (item in items) {
            val drill = byCard.getValue(item["card"]["id"].asText())
            for (key in listOf("decks", "deck_count", "kanji_examples")) assertEquals(drill[key], item[key], key)
        }

        // Note.deck_ids is the whole list the queue item's decks are cut from
        suspend fun noteDecks(id: String): List<String> {
            val n = client.call(HttpMethod.Get, "/notes/$id", bearer = t)
            assertEquals(200, n.status.value)
            return n.json()["deck_ids"].strings()
        }
        assertEquals(listOf(d2, d3, d1, d4), noteDecks(s.vocab.first))
        assertEquals(emptyList(), noteDecks(s.kanji.first))
        assertEquals(listOf(d2, d4), noteDecks(s.phrase.first))

        // DictEntry and DictEntrySummary
        val e = client.call(HttpMethod.Get, "/dict/entries/$taberu", bearer = t)
        assertEquals(200, e.status.value)
        assertEquals(listOf(d2, d3, d1, d4), e.json()["deck_ids"].strings())
        val words = client.call(HttpMethod.Get, "/kanji/%E9%A3%9F/words", bearer = t).json()["items"].associateBy { it["seq"].asLong() }
        assertEquals(listOf(d2, d3, d1, d4), words.getValue(taberu)["deck_ids"].strings())
        assertEquals(listOf(d1), words.getValue(shokudou)["deck_ids"].strings())
        assertEquals(emptyList(), words.getValue(shokuji)["deck_ids"].strings())
    }

    @Test
    fun kanjiMissingFromTheDictionaryStillGetsExamplesAndATombstonedNoteHasNoDecks() = reviewApp {
        val s = seedAll()
        val t = token()
        db.txBlocking { it.execute("delete from ref.kanji where literal = '食'") }
        val r = client.call(HttpMethod.Post, "/drill/session", bearer = t, body = mapOf("source" to "cards", "card_ids" to listOf(s.kanji.second), "limit" to 500))
        val item = r.json()["items"].single()
        assertTrue(item["kanji"].isNull)
        // no readings: nothing is ON or KUN, the order is the pick order
        assertEquals(
            listOf("食べる other", "食堂 other", "食事 other"),
            item["kanji_examples"].map { it["headword"].asText() + " " + it["reading_type"].asText() },
        )

        db.txBlocking { it.execute("update app.note set deleted_at = now() where id = ?::uuid", s.phrase.first) }
        assertEquals(emptyList(), db.txBlocking { d -> kotobaya.notes.NoteViews(d).noteRow(s.phrase.first) }!!["deck_ids"].strings())
        assertNull(db.txBlocking { d -> kotobaya.decks.DeckMembership.ofNotes(d, listOf(s.phrase.first))[s.phrase.first] })
    }

    @Test
    fun extrasCostAFixedNumberOfStatementsPerPage() = reviewApp {
        val s = seedAll()
        fun statements(noteIds: List<String>, seqs: List<Long>, literals: List<String>): Int = db.txBlocking { d ->
            val n = AtomicInteger()
            val counting = DSL.using(d.configuration().derive(ExecuteListener.onExecuteStart { n.incrementAndGet() }))
            val extras = QueueExtras.load(counting, noteIds, seqs, literals)
            // reading the loaded extras costs nothing more
            for (id in noteIds) extras.putDecks(json.createObjectNode(), id)
            for (l in literals) extras.kanjiExamples(l, null)
            for (q in seqs) extras.entryDeckIds(q)
            n.get()
        }
        assertEquals(3, statements(listOf(s.vocab.first), listOf(taberu), listOf("食")))
        // 40 more notes, entries and kanji on the page: still three statements
        val more = (1..40).map { note(mapOf("note_type_id" to phraseType, "fields" to mapOf("expression" to "句$it", "meaning" to "m$it")), "meaning").first }
        for (id in more) noteItem(d1, id)
        val literals = listOf("食") + (0 until 40).map { String(Character.toChars(0x4E00 + it)) }
        val all = listOf(s.vocab.first, s.kanji.first, s.phrase.first) + more
        assertEquals(3, statements(all, listOf(taberu, shokuji, shokudou, shokuhin) + (1L..40L), literals))
        assertEquals(0, statements(emptyList(), emptyList(), emptyList()))
        val decks = db.txBlocking { d -> kotobaya.decks.DeckMembership.ofNotes(d, all) }
        assertEquals(setOf(s.vocab.first, s.phrase.first) + more, decks.keys)
    }
}
