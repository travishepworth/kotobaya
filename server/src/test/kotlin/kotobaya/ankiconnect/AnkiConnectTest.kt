package kotobaya.ankiconnect

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.blobs.InMemoryBlobStore
import kotobaya.contracts.sync.OpType
import kotobaya.server.json
import kotobaya.sync.SyncFixture
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T603: `POST /ankiconnect` over HTTP with real tokens; every write must arrive as ops. */
class AnkiConnectTest : SyncFixture() {
    private val store = InMemoryBlobStore()
    private val anki by lazy { AnkiConnectModule(db, sync.opApply, store) }

    private fun ankiApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        app(ankiConnect = anki.actions, extraHandlers = sync.handlers, block = block)

    private fun key() = mint(TokenKind.PERSONAL, listOf("write")).second

    private suspend fun ApplicationTestBuilder.act(key: String, action: String, params: Map<String, Any?> = emptyMap()): JsonNode =
        json.readTree(
            client.post("/api/v1/ankiconnect") {
                setBody(json.writeValueAsString(mapOf("action" to action, "version" to 6, "key" to key, "params" to params)))
            }.bodyAsText(),
        )

    private fun ok(r: JsonNode): JsonNode {
        assertTrue(r["error"].isNull, r.toString())
        return r["result"]
    }

    private fun head(): Long = count("select coalesce(max(seq), 0) from app.change")

    private fun opsSince(after: Long): List<String> =
        db.txBlocking { dsl -> dsl.fetch("select op_type from app.change where seq > ? order by seq", after).map { it.get(0, String::class.java) } }

    private fun userDeck(name: String = "Inbox"): String {
        val id = UUID.randomUUID().toString()
        emit(OpType.DECK_CREATED, """{"id":"$id","name":"$name","type":"user"}""")
        return id
    }

    private fun dictEntry(seq: Long, headword: String = "食べる", reading: String = "たべる") {
        db.txBlocking {
            it.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
            it.execute(
                "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) values (?, ?, ?, '[{\"text\":\"$reading\"}]', '[]', 1)",
                seq, headword, reading,
            )
            it.execute("insert into ref.dict_entry_form (seq, kind, ord, form, norm) values (?, 'kanji', 0, ?, ?)", seq, headword, headword)
            it.execute("insert into ref.dict_entry_form (seq, kind, ord, form, norm) values (?, 'reading', 0, ?, ?)", seq, reading, reading)
        }
    }

    private fun note(expression: String, deck: String = "Inbox", allowDuplicate: Boolean = false, extra: Map<String, Any?> = emptyMap()) = mapOf(
        "note" to mapOf(
            "deckName" to deck, "modelName" to "Vocab", "tags" to listOf("yomitan"),
            "fields" to mapOf("expression" to expression, "reading" to "たべる", "meaning" to "to eat"),
            "options" to mapOf("allowDuplicate" to allowDuplicate),
        ) + extra,
    )

    @Test
    fun addNoteEmitsExactlyTheThreeOpKindsAndIsVisibleViaPull() = ankiApp {
        val key = key()
        dictEntry(1358280)
        val deck = userDeck()
        assertEquals(listOf("Inbox"), ok(act(key, "deckNames")).map { it.asText() })
        assertEquals(listOf("expression", "reading", "meaning"), ok(act(key, "modelFieldNames", mapOf("modelName" to "Vocab"))).map { it.asText() }.take(3))
        assertTrue(ok(act(key, "modelNames")).map { it.asText() }.contains("Vocab"))
        val before = head()
        val id = ok(act(key, "addNote", note("食べる"))).asLong()
        assertEquals(listOf("note.created", "card.created", "card.created", "deck.item_put"), opsSince(before))
        assertEquals(1, count("select count(*) from app.deck_item where deck_id = ?::uuid and item_key = 'e:1358280'", deck))
        assertEquals(1358280L, count("select entry_seq from app.note where origin = 'ankiconnect'"))
        assertEquals(id, count("select $ANKI_ID_SQL from app.note n"))

        val device = insertDevice()
        val (_, deviceToken) = mint(TokenKind.DEVICE, listOf("read", "write", "review"), deviceId = device)
        val pull = json.readTree(client.call(HttpMethod.Get, "/sync/pull?since=0", bearer = deviceToken).bodyAsText())
        val created = pull["ops"].first { it["op_type"].asText() == "note.created" }
        assertEquals("ankiconnect", created["payload"]["origin"].asText())
        assertEquals(1358280L, created["payload"]["entry_seq"].asLong())
    }

    @Test
    fun aDuplicateIsRefusedAndAllowDuplicateReturnsTheExistingIdWithoutASecondNote() = ankiApp {
        val key = key()
        dictEntry(1358280)
        userDeck()
        val first = ok(act(key, "addNote", note("食べる"))).asLong()
        val before = head()
        val dup = act(key, "addNote", note("食べる"))
        assertTrue(dup["result"].isNull)
        assertEquals("cannot create note because it is a duplicate", dup["error"].asText())
        assertEquals(before, head())
        assertEquals(listOf(false), ok(act(key, "canAddNotes", mapOf("notes" to listOf(note("食べる")["note"])))).map { it.asBoolean() })
        assertEquals(first, ok(act(key, "addNote", note("食べる", allowDuplicate = true))).asLong())
        assertEquals(1, count("select count(*) from app.note"))
        assertEquals(1, count("select count(*) from app.card where kind = 'meaning'"))

        // No dictionary match: the first field decides; allowDuplicate then creates a second note.
        val plain = ok(act(key, "addNote", note("ぬるぽ"))).asLong()
        assertTrue(act(key, "addNote", note("ぬるぽ"))["error"].asText().contains("duplicate"))
        val second = ok(act(key, "addNote", note("ぬるぽ", allowDuplicate = true))).asLong()
        assertTrue(plain != second)
        assertEquals(3, count("select count(*) from app.note"))
        assertTrue(act(key, "addNote", note("x") + mapOf("note" to mapOf("modelName" to "Nope", "fields" to emptyMap<String, String>())))["error"].asText().startsWith("model was not found"))
    }

    @Test
    fun ankiIdRoundTripsThroughFindNotes() = ankiApp {
        val key = key()
        dictEntry(1358280)
        userDeck()
        val id = ok(act(key, "addNote", note("食べる"))).asLong()
        assertTrue(id < (1L shl 53))
        val noteId = UUID.fromString(db.txBlocking { it.fetchValue("select id::text from app.note") as String })
        assertEquals(id, ankiId(noteId))
        assertEquals(listOf(id), ok(act(key, "findNotes", mapOf("query" to "nid:$id"))).map { it.asLong() })
        assertEquals(listOf(id), ok(act(key, "findNotes", mapOf("query" to "\"expression:食べる\""))).map { it.asLong() })
        assertEquals(listOf(id), ok(act(key, "findNotes", mapOf("query" to "deck:\"Inbox\" expression:食べる"))).map { it.asLong() })
        for (q in listOf("nid:${id + 1}", "expression:飲む", "deck:Other", "食べる", "expression:食べる deck:Other")) {
            assertEquals(emptyList(), ok(act(key, "findNotes", mapOf("query" to q))).toList(), q)
        }
    }

    @Test
    fun storeMediaFileRewritesTheFilenameInALaterAddNote() = ankiApp {
        val key = key()
        val bytes = "mp3-bytes".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val stored = ok(act(key, "storeMediaFile", mapOf("filename" to "yomitan_食べる.mp3", "data" to Base64.getEncoder().encodeToString(bytes)))).asText()
        assertEquals("$hash.mp3", stored)
        assertEquals("audio", db.txBlocking { it.fetchValue("select kind from app.blob where hash = ?", hash) as String })
        assertTrue(store.objects.containsKey(hash))
        assertTrue(act(key, "storeMediaFile", mapOf("filename" to "a.png", "url" to "http://127.0.0.1/a.png"))["error"].asText().contains("url"))

        val pic = Base64.getEncoder().encodeToString("png".toByteArray())
        val fields = mapOf(
            "expression" to "食べる", "reading" to "たべる",
            "meaning" to "[sound:$stored] [sound:yomitan_食べる.mp3] <img src=\"$stored\"> [sound:unknown.mp3]",
        )
        ok(act(key, "addNote", mapOf("note" to mapOf("deckName" to "x", "modelName" to "Vocab", "fields" to fields, "picture" to listOf(mapOf("data" to pic, "filename" to "p.png", "fields" to listOf("sentence")))))))
        val stored2 = json.readTree(db.txBlocking { it.fetchValue("select fields::text from app.note") as String })
        assertEquals("[sound:$hash] [sound:$hash] <img src=\"blob:$hash\"> [sound:unknown.mp3]", stored2["meaning"].asText())
        assertTrue(stored2["sentence"].asText().startsWith("<img src=\"blob:"))
    }

    @Test
    fun aRevokedKeyIsRefusedAndAReadOnlyKeyLacksTheScope() = ankiApp {
        val (id, key) = mint(TokenKind.PERSONAL, listOf("write"))
        assertEquals(listOf("Kotobaya"), ok(act(key, "deckNames")).map { it.asText() })
        client.call(HttpMethod.Delete, "/tokens/$id", bearer = admin())
        val r = act(key, "addNote", note("食べる"))
        assertEquals("valid api key must be provided", r["error"].asText())
        assertEquals(0, count("select count(*) from app.note"))
        val readOnly = mint(TokenKind.PERSONAL, listOf("read")).second
        assertEquals("the api key lacks the write scope", act(readOnly, "deckNames")["error"].asText())
    }
}
