package kotobaya.notes

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.sync.SyncFixture
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T201b: notes and note types over HTTP, with real tokens; every write must arrive as ops. */
class NotesApiTest : SyncFixture() {
    private val notes by lazy { NotesModule(db, sync.opApply) }

    private val vocabType = "00000000-0000-7000-8000-000000000101"
    private val customType = "00000000-0000-7000-8000-000000000106"

    private fun notesApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = notes.handlers, block = block)

    private fun writer() = mint(TokenKind.PERSONAL, listOf("read", "write")).second

    /** Op types logged after [after], in seq order. */
    private fun opsSince(after: Long): List<String> =
        db.txBlocking { dsl -> dsl.fetch("select op_type from app.change where seq > ? order by seq", after).map { it.get(0, String::class.java) } }

    private fun head(): Long = count("select coalesce(max(seq), 0) from app.change")

    private fun seqHeader(r: HttpResponse): Long? = r.headers["Kotobaya-Seq"]?.toLong()

    private suspend fun ApplicationTestBuilder.createNote(token: String, body: Map<String, Any?>, query: String = ""): HttpResponse =
        client.call(HttpMethod.Post, "/notes$query", bearer = token, body = body)

    private fun userDeck(id: String = UUID.randomUUID().toString()): String {
        emit(OpType.DECK_CREATED, """{"id":"$id","name":"Mining","type":"user"}""")
        return id
    }

    private fun dictEntry(seq: Long) {
        db.txBlocking {
            it.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
            it.execute(
                "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) " +
                    "values (?, '食べる', 'たべる', '[{\"text\":\"たべる\"}]', '[]', 1) on conflict do nothing",
                seq,
            )
        }
    }

    private fun kinds(note: JsonNode): List<String> = note["cards"].map { it["kind"].asText() }

    @Test
    fun createNoteForEachBuiltInNoteTypeCreatesTheDefaultCardsAndEmitsExactlyTheDeclaredOps() = notesApp {
        val token = writer()
        val expected = mapOf(
            vocabType to listOf("meaning", "reading"),
            "00000000-0000-7000-8000-000000000102" to listOf("keyword"),
            "00000000-0000-7000-8000-000000000103" to listOf("meaning"),
            "00000000-0000-7000-8000-000000000104" to listOf("cloze"),
            "00000000-0000-7000-8000-000000000105" to listOf("comprehension"),
            customType to listOf("basic"),
        )
        val deck = userDeck()
        for ((type, defaults) in expected) {
            val before = head()
            val extra = if (type == "00000000-0000-7000-8000-000000000102") mapOf("kanji_literal" to "績") else emptyMap()
            val r = createNote(token, mapOf("note_type_id" to type, "fields" to mapOf("expression" to "x"), "deck_id" to deck) + extra)
            assertEquals(201, r.status.value, "$type: ${r.json()}")
            assertEquals(listOf("note.created") + defaults.map { "card.created" } + "deck.item_put", opsSince(before), type)
            assertEquals(head(), seqHeader(r), "Kotobaya-Seq is the last seq of the call")
            assertEquals(defaults, kinds(r.json()), type)
            assertEquals(type, r.json()["note_type_id"].asText())
        }
        // positions: current maximum + 1024, per deck
        assertEquals(
            listOf(1024L, 2048L, 3072L, 4096L, 5120L, 6144L),
            db.txBlocking { dsl -> dsl.fetch("select position from app.deck_item where deck_id = ?::uuid order by position", deck).map { (it.get(0) as Number).toLong() } },
        )
    }

    @Test
    fun aVocabNoteWithAnEntryIsKeyedByEntryInTheDeckAndExplicitCardKindsWin() = notesApp {
        val token = writer()
        dictEntry(1358280)
        val deck = userDeck()
        val r = createNote(token, mapOf("note_type_id" to vocabType, "entry_seq" to 1358280, "card_kinds" to listOf("pitch", "listening"), "deck_id" to deck))
        assertEquals(201, r.status.value, r.json().toString())
        assertEquals(listOf("listening", "pitch"), kinds(r.json()))
        assertEquals(1, count("select count(*) from app.deck_item where deck_id = ?::uuid and item_key = 'e:1358280'", deck))
        val none = createNote(token, mapOf("note_type_id" to customType, "card_kinds" to emptyList<String>()))
        assertEquals(emptyList(), kinds(none.json()))
        assertProblem(createNote(token, mapOf("note_type_id" to customType, "card_kinds" to listOf("nope"))), 422, "validation_failed")
        assertProblem(createNote(token, mapOf("note_type_id" to vocabType, "entry_seq" to 99999999)), 422, "validation_failed")
    }

    @Test
    fun anIdempotentRetryReturnsTheOriginalWithoutNewOpsOrSeqHeader() = notesApp {
        val token = writer()
        val id = "01931f40-1a2b-7c3d-8e4f-000000000a01"
        val first = createNote(token, mapOf("id" to id, "note_type_id" to customType, "fields" to mapOf("front" to "a")))
        assertEquals(201, first.status.value)
        val before = head()
        val retry = createNote(token, mapOf("id" to id, "note_type_id" to customType, "fields" to mapOf("front" to "changed")))
        assertEquals(200, retry.status.value)
        assertNull(retry.headers["Kotobaya-Seq"])
        assertEquals(before, head())
        assertEquals(first.json(), retry.json())
        assertEquals(1, count("select count(*) from app.card where note_id = ?::uuid", id))

        val typeId = "01931f40-1a2b-7c3d-8e4f-000000000b01"
        val body = mapOf("id" to typeId, "name" to "Mine", "base_type" to "custom", "fields" to listOf(mapOf("key" to "a", "label" to "A", "kind" to "text")), "templates" to mapOf("basic" to mapOf("default" to true, "front" to null, "back" to null)))
        assertEquals(201, client.call(HttpMethod.Post, "/note-types", bearer = token, body = body).status.value)
        val mark = head()
        val again = client.call(HttpMethod.Post, "/note-types", bearer = token, body = body)
        assertEquals(200, again.status.value)
        assertNull(again.headers["Kotobaya-Seq"])
        assertEquals(mark, head())
    }

    @Test
    fun readAndWriteScopesAreEnforcedAndNothingIsEmittedWithoutWrite() = notesApp {
        val reader = mint(TokenKind.PERSONAL, listOf("read")).second
        val writer = writer()
        val note = createNote(writer, mapOf("note_type_id" to customType)).json()["id"].asText()
        val before = head()
        val writes = listOf(
            Triple(HttpMethod.Post, "/notes", mapOf("note_type_id" to customType)),
            Triple(HttpMethod.Patch, "/notes/$note", mapOf("mnemonic" to "m")),
            Triple(HttpMethod.Delete, "/notes/$note", null),
            Triple(HttpMethod.Post, "/note-types", mapOf("name" to "x")),
            Triple(HttpMethod.Patch, "/note-types/$customType", mapOf("css" to "")),
            Triple(HttpMethod.Delete, "/note-types/$customType", null),
        )
        for ((m, path, body) in writes) {
            val r = client.call(m, path, bearer = reader, body = body)
            assertProblem(r, 403, "insufficient_scope")
        }
        assertEquals(before, head())
        for (path in listOf("/notes", "/notes/$note", "/note-types", "/note-types/$customType")) {
            assertEquals(200, client.call(HttpMethod.Get, path, bearer = reader).status.value, path)
        }
        assertProblem(client.call(HttpMethod.Get, "/notes"), 401, "unauthenticated")
    }

    @Test
    fun togglingAnOptionalKindOnNeverBackfillsAndNewNotesGetOnlyTheMissingKindsOfTheRequest() = notesApp {
        val token = writer()
        val first = createNote(token, mapOf("note_type_id" to vocabType)).json()
        assertEquals(listOf("meaning", "reading"), kinds(first))
        val before = head()
        // The contract: updateNoteType emits note_type.updated only; `default` governs new notes.
        val r = client.call(HttpMethod.Patch, "/note-types/$vocabType", bearer = token, body = mapOf("templates" to mapOf("listening" to mapOf("default" to true, "front" to null, "back" to null))))
        assertEquals(200, r.status.value, r.json().toString())
        assertEquals(listOf("note_type.updated"), opsSince(before))
        assertEquals(2, count("select count(*) from app.card where note_id = ?::uuid", first["id"].asText()))
        assertEquals(listOf("listening", "meaning", "reading"), kinds(createNote(token, mapOf("note_type_id" to vocabType)).json()))
        // turning it back off creates and deletes nothing
        val off = client.call(HttpMethod.Patch, "/note-types/$vocabType", bearer = token, body = mapOf("templates" to mapOf("listening" to mapOf("default" to false, "front" to null, "back" to null))))
        assertEquals(200, off.status.value)
        assertEquals(listOf("meaning", "reading"), kinds(createNote(token, mapOf("note_type_id" to vocabType)).json()))
    }

    @Test
    fun tagsAreStoredSortedAndDeduplicatedAndWhitespaceOrLeechIsRefusedNotNormalised() = notesApp {
        val token = writer()
        val ok = createNote(token, mapOf("note_type_id" to customType, "tags" to listOf("frieren", "anime", "frieren")))
        assertEquals(listOf("anime", "frieren"), ok.json()["tags"].map { it.asText() })
        val before = head()
        for (bad in listOf("a　b", "a b", "a b", "　", "leech", "")) {
            val r = createNote(token, mapOf("note_type_id" to customType, "tags" to listOf("fine", bad)))
            assertProblem(r, 422, "validation_failed")
            assertEquals("/tags/1", r.json()["errors"][0]["pointer"].asText(), bad)
        }
        assertEquals(before, head())
    }

    @Test
    fun aDuplicateReferenceIsAnErrorNamingTheHolderOrTheHolderItself() = notesApp {
        val token = writer()
        dictEntry(1358280)
        val deck = userDeck()
        val first = createNote(token, mapOf("note_type_id" to vocabType, "entry_seq" to 1358280)).json()
        val before = head()
        val dup = createNote(token, mapOf("note_type_id" to vocabType, "entry_seq" to 1358280))
        assertProblem(dup, 409, "already_exists")
        assertEquals(first["id"].asText(), dup.json()["existing_id"].asText())
        assertEquals(before, head())
        val ret = createNote(token, mapOf("note_type_id" to vocabType, "entry_seq" to 1358280, "deck_id" to deck), "?on_duplicate=return_existing")
        assertEquals(200, ret.status.value)
        assertEquals(first["id"], ret.json()["id"])
        assertEquals(listOf("deck.item_put"), opsSince(before))
    }

    @Test
    fun updateNoteWritesOneNoteUpdatedWithTheTouchedRegistersOnly() = notesApp {
        val token = writer()
        val note = createNote(token, mapOf("note_type_id" to customType, "fields" to mapOf("front" to "a", "back" to "b"), "tags" to listOf("x", "y"))).json()["id"].asText()
        suspend fun patch(body: Any?) = client.call(HttpMethod.Patch, "/notes/$note", bearer = token, body = body)

        var before = head()
        val r = patch(mapOf("fields" to mapOf("back" to ""), "add_tags" to listOf("z", "x"), "remove_tags" to listOf("y", "absent"), "mnemonic" to "m"))
        assertEquals(200, r.status.value, r.json().toString())
        assertEquals(listOf("note.updated"), opsSince(before))
        assertEquals(head(), seqHeader(r))
        assertEquals(listOf("x", "z"), r.json()["tags"].map { it.asText() })
        assertEquals("", r.json()["fields"]["back"].asText())
        assertEquals("a", r.json()["fields"]["front"].asText())

        before = head()
        val src = patch(mapOf("source_sentence" to "パンを食べる"))
        assertEquals("パンを食べる", src.json()["source_sentence"].asText())
        assertTrue(src.json()["source_media_id"].isNull)

        assertProblem(patch(emptyMap<String, Any>()), 422, "validation_failed")
        assertProblem(patch(mapOf("tags" to listOf("a"), "add_tags" to listOf("b"))), 422, "validation_failed")
        assertProblem(patch(mapOf("origin" to "x")), 422, "validation_failed")
        assertProblem(patch(mapOf("entry_seq" to 5)), 422, "validation_failed") // a custom note has no entry
        assertProblem(patch(mapOf("add_tags" to (1..101).map { "t$it" }.take(100) + "t200")), 422, "validation_failed")
        assertProblem(patch(mapOf("tags" to listOf("a　"))), 422, "validation_failed")
        assertEquals(1, opsSince(before).size, "only the source_sentence update; rejected patches emit nothing")
    }

    @Test
    fun tooManyTagsAfterAddTagsIsOutOfRangeAtAddTags() = notesApp {
        val token = writer()
        val id = createNote(token, mapOf("note_type_id" to customType, "tags" to (1..100).map { "a$it" })).json()["id"].asText()
        client.call(HttpMethod.Patch, "/notes/$id", bearer = token, body = mapOf("add_tags" to (1..100).map { "b$it" }))
        val before = head()
        val r = client.call(HttpMethod.Patch, "/notes/$id", bearer = token, body = mapOf("add_tags" to listOf("c1")))
        assertProblem(r, 422, "validation_failed")
        assertEquals("out_of_range", r.json()["errors"][0]["code"].asText())
        assertEquals("/add_tags", r.json()["errors"][0]["pointer"].asText())
        assertEquals(before, head())
    }

    @Test
    fun deleteNoteTombstonesOnceAndAMergeLoserIsAnsweredBySurvivor() = notesApp {
        val token = writer()
        val a = createNote(token, mapOf("note_type_id" to customType)).json()["id"].asText()
        val b = createNote(token, mapOf("note_type_id" to customType)).json()["id"].asText()
        val before = head()
        val del = client.call(HttpMethod.Delete, "/notes/$a", bearer = token)
        assertEquals(204, del.status.value)
        assertEquals(listOf("note.deleted"), opsSince(before))
        assertNotNull(seqHeader(del))
        val again = client.call(HttpMethod.Delete, "/notes/$a", bearer = token)
        assertEquals(204, again.status.value)
        assertNull(again.headers["Kotobaya-Seq"])
        assertProblem(client.call(HttpMethod.Get, "/notes/$a", bearer = token), 410, "gone")
        assertEquals(200, client.call(HttpMethod.Get, "/notes/$a?include_deleted=true", bearer = token).status.value)
        assertProblem(client.call(HttpMethod.Patch, "/notes/$a", bearer = token, body = mapOf("mnemonic" to "m")), 410, "gone")

        // A merge loser (the merge itself is T202c; set the state a merge leaves behind).
        val loser = createNote(token, mapOf("note_type_id" to customType)).json()["id"].asText()
        db.txBlocking { it.execute("update app.note set deleted_at = now(), merged_into_id = ?::uuid where id = ?::uuid", b, loser) }
        val mark = head()
        val got = client.call(HttpMethod.Get, "/notes/$loser", bearer = token)
        assertEquals(200, got.status.value)
        assertEquals(b, got.json()["id"].asText())
        assertEquals("/api/v1/notes/$b", got.headers["Content-Location"])
        assertEquals(204, client.call(HttpMethod.Delete, "/notes/$loser", bearer = token).status.value)
        assertEquals(mark, head())
        assertEquals(0, count("select count(*) from app.note where id = ?::uuid and deleted_at is not null", b))
        val patched = client.call(HttpMethod.Patch, "/notes/$loser", bearer = token, body = mapOf("mnemonic" to "via loser"))
        assertEquals(b, patched.json()["id"].asText())
        assertEquals("via loser", patched.json()["mnemonic"].asText())
        assertEquals("/api/v1/notes/$b", patched.headers["Content-Location"])
    }

    @Test
    fun noteTypesBuiltInsAreProtectedAndInUseTypesCannotBeDeleted() = notesApp {
        val token = writer()
        assertProblem(client.call(HttpMethod.Delete, "/note-types/$vocabType", bearer = token), 409, "builtin_immutable")
        val removed = mapOf("fields" to listOf(mapOf("key" to "expression", "label" to "E", "kind" to "text"), mapOf("key" to "reading", "label" to "R", "kind" to "text"), mapOf("key" to "meaning", "label" to "M", "kind" to "text")))
        val before = head()
        assertProblem(client.call(HttpMethod.Patch, "/note-types/$vocabType", bearer = token, body = removed), 409, "builtin_immutable")
        assertEquals(before, head())

        val vocabLike = mapOf("name" to "Takoboto", "base_type" to "vocab", "templates" to mapOf("meaning" to mapOf("default" to true, "front" to "<div>{{expression}}</div>", "back" to null)))
        val bad = client.call(HttpMethod.Post, "/note-types", bearer = token, body = vocabLike + ("fields" to listOf(mapOf("key" to "expression", "label" to "E", "kind" to "text"))))
        assertProblem(bad, 422, "validation_failed")
        assertEquals("/fields", bad.json()["errors"][0]["pointer"].asText())
        assertProblem(client.call(HttpMethod.Post, "/note-types", bearer = token, body = vocabLike + ("fields" to listOf(mapOf("key" to "a", "label" to "A", "kind" to "text"), mapOf("key" to "a", "label" to "B", "kind" to "text")))), 422, "validation_failed")

        val created = client.call(HttpMethod.Post, "/note-types", bearer = token, body = vocabLike + ("fields" to removed["fields"]))
        assertEquals(201, created.status.value, created.json().toString())
        val tid = created.json()["id"].asText()
        assertEquals(false, created.json()["builtin"].asBoolean())
        val note = createNote(token, mapOf("note_type_id" to tid)).json()["id"].asText()
        assertProblem(client.call(HttpMethod.Delete, "/note-types/$tid", bearer = token), 409, "in_use")
        // own type: dropping a field is allowed, but a vocab type still needs its three keys
        assertProblem(client.call(HttpMethod.Patch, "/note-types/$tid", bearer = token, body = mapOf("fields" to listOf(mapOf("key" to "expression", "label" to "E", "kind" to "text")))), 422, "validation_failed")
        val renamed = client.call(HttpMethod.Patch, "/note-types/$tid", bearer = token, body = mapOf("name" to "Takoboto 2", "css" to ".x{}"))
        assertEquals("Takoboto 2", renamed.json()["name"].asText())

        client.call(HttpMethod.Delete, "/notes/$note", bearer = token)
        val mark = head()
        val del = client.call(HttpMethod.Delete, "/note-types/$tid", bearer = token)
        assertEquals(204, del.status.value)
        assertEquals(listOf("note_type.deleted"), opsSince(mark))
        assertEquals(204, client.call(HttpMethod.Delete, "/note-types/$tid", bearer = token).status.value)
        assertEquals(1, opsSince(mark).size)
        assertProblem(client.call(HttpMethod.Get, "/note-types/$tid", bearer = token), 410, "gone")
        assertEquals(200, client.call(HttpMethod.Get, "/note-types/$tid?include_deleted=true", bearer = token).status.value)
        assertProblem(createNote(token, mapOf("note_type_id" to tid)), 422, "validation_failed")
        val ids = client.call(HttpMethod.Get, "/note-types", bearer = token).json()["items"].map { it["id"].asText() }
        assertTrue(tid !in ids)
        assertEquals(vocabType, ids.first(), "built-ins first")
        assertProblem(client.call(HttpMethod.Get, "/note-types/00000000-0000-7000-8000-0000000009ff", bearer = token), 404, "not_found")
    }

    @Test
    fun listNotesFiltersAndPaginatesNewestFirst() = notesApp {
        val token = writer()
        dictEntry(1358280)
        val ids = (1..5).map { i ->
            syncNow.set(syncNow.get().plusSeconds(10))
            createNote(token, mapOf("note_type_id" to customType, "fields" to mapOf("front" to "word$i"), "tags" to listOf(if (i % 2 == 0) "even" else "odd"))).json()["id"].asText()
        }
        val vocab = createNote(token, mapOf("note_type_id" to vocabType, "entry_seq" to 1358280, "fields" to mapOf("expression" to "食べる"))).json()["id"].asText()

        val page1 = client.call(HttpMethod.Get, "/notes?type=custom&limit=2", bearer = token).json()
        assertEquals(ids.reversed().take(2), page1["items"].map { it["id"].asText() })
        assertEquals(listOf("basic"), kinds(page1["items"][0]))
        val page2 = client.call(HttpMethod.Get, "/notes?type=custom&limit=2&cursor=${page1["next_cursor"].asText()}", bearer = token).json()
        assertEquals(ids.reversed().drop(2).take(2), page2["items"].map { it["id"].asText() })
        val page3 = client.call(HttpMethod.Get, "/notes?type=custom&limit=2&cursor=${page2["next_cursor"].asText()}", bearer = token).json()
        assertEquals(ids.take(1), page3["items"].map { it["id"].asText() })
        assertTrue(page3["next_cursor"].isNull)
        assertProblem(client.call(HttpMethod.Get, "/notes?limit=2&cursor=${page1["next_cursor"].asText()}", bearer = token), 422, "validation_failed")

        suspend fun query(q: String) = client.call(HttpMethod.Get, "/notes?$q", bearer = token).json()["items"].map { it["id"].asText() }
        assertEquals(listOf(ids[3], ids[1]), query("tag=even"))
        assertEquals(listOf(vocab), query("entry_seq=1358280"))
        assertEquals(listOf(vocab), query("q=%E9%A3%9F%E3%81%B9"))
        assertEquals(listOf(ids[2]), query("q=WORD3"))
        assertEquals(listOf(vocab), query("note_type_id=$vocabType"))
        assertProblem(client.call(HttpMethod.Get, "/notes?tag=leech", bearer = token), 422, "validation_failed")
        client.call(HttpMethod.Delete, "/notes/${ids[0]}", bearer = token)
        assertTrue(ids[0] !in query("type=custom"))
        assertTrue(ids[0] in query("type=custom&include_deleted=true"))
    }
}
