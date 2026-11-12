package kotobaya.decks

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.contracts.queue.StudyDay
import kotobaya.contracts.sync.OpType
import kotobaya.notes.NotesModule
import kotobaya.sync.SyncFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T204: decks and deck items over HTTP, with real tokens; every write must arrive as ops. */
class DecksApiTest : SyncFixture() {
    private val decks by lazy { DecksModule(db, sync.opApply) }
    private val notes by lazy { NotesModule(db, sync.opApply) }
    private val customType = "00000000-0000-7000-8000-000000000106"

    private fun deckApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = decks.handlers + notes.handlers, block = block)

    private fun writer() = mint(TokenKind.PERSONAL, listOf("read", "write")).second

    private fun head(): Long = count("select coalesce(max(seq), 0) from app.change")

    private fun opsSince(after: Long): List<String> =
        db.txBlocking { dsl -> dsl.fetch("select op_type from app.change where seq > ? order by seq", after).map { it.get(0, String::class.java) } }

    private suspend fun ApplicationTestBuilder.createDeck(token: String, body: Map<String, Any?>): HttpResponse =
        client.call(HttpMethod.Post, "/decks", bearer = token, body = body)

    private suspend fun ApplicationTestBuilder.userDeck(token: String, name: String = "Inbox"): String =
        createDeck(token, mapOf("type" to "user", "name" to name)).json()["id"].asText()

    private suspend fun ApplicationTestBuilder.note(token: String, tags: List<String> = emptyList()): String =
        client.call(HttpMethod.Post, "/notes", bearer = token, body = mapOf("note_type_id" to customType, "fields" to mapOf("front" to "a"), "tags" to tags))
            .json()["id"].asText()

    private fun dictEntry(seq: Long) {
        db.txBlocking {
            it.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
            it.execute(
                "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) " +
                    "values (?, '食べる', 'たべる', '[{\"text\":\"たべる\"}]', '[{\"gloss\":[{\"text\":\"to eat\"}]}]', 1) on conflict do nothing",
                seq,
            )
        }
    }

    private fun keys(r: HttpResponse): List<String> = r.json()["items"].map { it["item_key"].asText() }

    @Test
    fun aSmartDeckSelectsItsCardsThroughTheCompilerAndAnInvalidQueryIs422InvalidQuery() = deckApp {
        val token = writer()
        val mine = note(token, listOf("mine"))
        note(token, listOf("other"))
        val created = createDeck(token, mapOf("type" to "smart", "name" to "Mine", "query" to "tag:mine"))
        assertEquals(201, created.status.value, created.json().toString())
        val deck = created.json()
        assertEquals("tag:mine", deck["query"].asText())
        assertEquals(0, deck["item_count"].asInt())

        val ids = db.txBlocking { SmartDecks.cardIds(it, deck["id"].asText(), 1_000_000L, StudyDay(0, 86_400_000L)) }
        val expected = db.txBlocking { dsl -> dsl.fetch("select id::text from app.card where note_id = ?::uuid", mine).map { it.get(0, String::class.java) } }
        assertEquals(1, expected.size)
        assertEquals(expected, ids)

        val mark = head()
        val bad = createDeck(token, mapOf("type" to "smart", "name" to "Bad", "query" to "nosuchkey:x"))
        assertProblem(bad, 422, "invalid_query")
        assertProblem(client.call(HttpMethod.Patch, "/decks/${deck["id"].asText()}", bearer = token, body = mapOf("query" to "(tag:mine")), 422, "invalid_query")
        assertEquals(mark, head(), "nothing minted for an invalid query")

        val updated = client.call(HttpMethod.Patch, "/decks/${deck["id"].asText()}", bearer = token, body = mapOf("query" to "tag:other"))
        assertEquals(200, updated.status.value)
        assertEquals("tag:other", updated.json()["query"].asText())
        // type rules
        assertProblem(createDeck(token, mapOf("type" to "user", "name" to "x", "query" to "tag:a")), 422, "validation_failed")
        assertProblem(createDeck(token, mapOf("type" to "media", "name" to "x")), 422, "validation_failed")
        assertProblem(createDeck(token, mapOf("type" to "smart", "name" to "x")), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Patch, "/decks/${userDeck(token)}", bearer = token, body = mapOf("query" to "tag:a")), 422, "validation_failed")
        // item ops and listing on a smart deck
        assertProblem(client.call(HttpMethod.Get, "/decks/${deck["id"].asText()}/items", bearer = token), 409, "not_applicable")
        assertProblem(client.call(HttpMethod.Put, "/decks/${deck["id"].asText()}/items/e:1", bearer = token), 409, "not_applicable")
    }

    @Test
    fun userDeckCrudEmitsOneOpPerCallAndCreateIsIdempotent() = deckApp {
        val token = writer()
        val id = "01931f40-2000-7000-8000-000000000d02"
        val before = head()
        val first = createDeck(token, mapOf("id" to id, "type" to "user", "name" to "Inbox", "priority" to 10, "new_cards_per_day" to 5))
        assertEquals(201, first.status.value)
        assertEquals(listOf("deck.created"), opsSince(before))
        assertEquals(head(), first.headers["Kotobaya-Seq"]?.toLong())
        assertEquals(10, first.json()["priority"].asInt())
        assertEquals(5, first.json()["new_cards_per_day"].asInt())

        val mark = head()
        val retry = createDeck(token, mapOf("id" to id, "type" to "user", "name" to "Changed"))
        assertEquals(200, retry.status.value)
        assertNull(retry.headers["Kotobaya-Seq"])
        assertEquals(first.json(), retry.json())
        assertEquals(mark, head())

        val patch = client.call(HttpMethod.Patch, "/decks/$id", bearer = token, body = mapOf("priority" to -1, "enabled" to false, "settings" to mapOf("order" to "chronological"), "new_cards_per_day" to null))
        assertEquals(200, patch.status.value, patch.json().toString())
        assertEquals(listOf("deck.created", "deck.updated"), opsSince(before))
        assertEquals(-1, patch.json()["priority"].asInt())
        assertEquals("chronological", patch.json()["settings"]["order"].asText())
        assertTrue(patch.json()["new_cards_per_day"].isNull)
        assertProblem(client.call(HttpMethod.Patch, "/decks/$id", bearer = token, body = mapOf("type" to "smart")), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Patch, "/decks/$id", bearer = token, body = emptyMap<String, Any>()), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Patch, "/decks/$id", bearer = token, body = mapOf("new_cards_per_day" to 99999999999)), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Patch, "/decks/$id", bearer = token, body = mapOf("new_cards_per_day" to 2_000_000_000)), 422, "validation_failed")

        val list = client.call(HttpMethod.Get, "/decks?type=user", bearer = token)
        assertEquals(listOf(id), list.json()["items"].map { it["id"].asText() })
        assertProblem(client.call(HttpMethod.Get, "/decks?type=nope", bearer = token), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Get, "/decks/01931f40-2000-7000-8000-00000000ffff", bearer = token), 404, "not_found")
    }

    @Test
    fun deletingADeckHidesItsItemsButKeepsTheRowsAsInTheCascadeExample() = deckApp {
        val token = writer()
        dictEntry(100)
        val deck = userDeck(token)
        client.call(HttpMethod.Post, "/decks/$deck/items", bearer = token, body = mapOf("items" to listOf(mapOf("entry_seq" to 100), mapOf("note_id" to note(token)))))
        assertEquals(2, client.call(HttpMethod.Get, "/decks/$deck", bearer = token).json()["item_count"].asInt())

        val before = head()
        val del = client.call(HttpMethod.Delete, "/decks/$deck", bearer = token)
        assertEquals(204, del.status.value)
        assertEquals(listOf("deck.deleted"), opsSince(before), "one op: items are not tombstoned with their parent")
        assertEquals(2, count("select count(*) from app.deck_item where deck_id = ?::uuid and deleted_at is null", deck))
        assertProblem(client.call(HttpMethod.Get, "/decks/$deck", bearer = token), 410, "gone")
        assertProblem(client.call(HttpMethod.Get, "/decks/$deck/items", bearer = token), 410, "gone")
        assertProblem(client.call(HttpMethod.Put, "/decks/$deck/items/e:100", bearer = token), 410, "gone")
        assertEquals(200, client.call(HttpMethod.Get, "/decks/$deck?include_deleted=true", bearer = token).status.value)
        assertEquals(emptyList(), client.call(HttpMethod.Get, "/decks", bearer = token).json()["items"].toList())
        val again = client.call(HttpMethod.Delete, "/decks/$deck", bearer = token)
        assertEquals(204, again.status.value)
        assertNull(again.headers["Kotobaya-Seq"])
        assertEquals(before + 1, head())
    }

    @Test
    fun mediaAndListDecksAreReadOnlyAndAListIsEnabledOncePerKeyWithABuildJob() = deckApp {
        val token = writer()
        val media = "01931f40-3000-7000-8000-000000000001"
        emit(OpType.MEDIA_CREATED, """{"id":"$media","kind":"anime","title":"Frieren","source":"paste"}""")
        assertProblem(client.call(HttpMethod.Delete, "/decks/$media", bearer = token), 409, "deck_read_only")
        assertProblem(client.call(HttpMethod.Put, "/decks/$media/items/e:1", bearer = token), 409, "deck_read_only")
        assertEquals(200, client.call(HttpMethod.Patch, "/decks/$media", bearer = token, body = mapOf("priority" to 3)).status.value)

        assertEquals(emptyList(), client.call(HttpMethod.Get, "/deck-lists", bearer = token).json()["items"].toList(), "no dictionary release loaded")
        assertProblem(createDeck(token, mapOf("type" to "list", "list_key" to "jlpt-n3")), 422, "validation_failed")
        dictEntry(1)
        val keysOf = client.call(HttpMethod.Get, "/deck-lists", bearer = token).json()["items"].map { it["list_key"].asText() }
        assertTrue("jlpt-n3" in keysOf && keysOf == keysOf.sorted(), keysOf.toString())
        val made = createDeck(token, mapOf("type" to "list", "list_key" to "jlpt-n3", "new_cards_per_day" to 5))
        assertEquals(201, made.status.value, made.json().toString())
        assertEquals("JLPT N3", made.json()["name"].asText())
        assertEquals(0, made.json()["content_version"].asInt())
        assertEquals(1, count("select count(*) from app.job where kind = 'deck-build' and payload->>'deck_id' = ?", made.json()["id"].asText()))
        val mark = head()
        val again = createDeck(token, mapOf("type" to "list", "list_key" to "jlpt-n3"))
        assertEquals(200, again.status.value)
        assertEquals(made.json()["id"], again.json()["id"])
        assertEquals(mark, head())
        assertProblem(client.call(HttpMethod.Put, "/decks/${made.json()["id"].asText()}/items/e:1", bearer = token), 409, "deck_read_only")
        assertEquals(made.json()["id"], client.call(HttpMethod.Get, "/deck-lists", bearer = token).json()["items"].first { it["list_key"].asText() == "jlpt-n3" }["deck_id"])
    }

    @Test
    fun itemsAreAppendedMovedAndRemovedThroughOps() = deckApp {
        val token = writer()
        dictEntry(100)
        dictEntry(200)
        val deck = userDeck(token)
        val n = note(token)
        val before = head()
        val add = client.call(
            HttpMethod.Post, "/decks/$deck/items", bearer = token,
            body = mapOf("items" to listOf(mapOf("entry_seq" to 100), mapOf("entry_seq" to 200, "position" to 5), mapOf("note_id" to n), mapOf("entry_seq" to 100))),
        )
        assertEquals(200, add.status.value, add.json().toString())
        // duplicates inside the array: the last wins and takes its slot
        assertEquals(listOf("e:200", "n:$n", "e:100"), keys(add))
        assertEquals(listOf("deck.item_put", "deck.item_put", "deck.item_put"), opsSince(before))
        assertEquals(listOf(5L, 1024L, 2048L), add.json()["items"].map { it["position"].asLong() })
        assertEquals("to eat", add.json()["items"][0]["entry"]["glosses"][0].asText())
        assertEquals("new", add.json()["items"][0]["status"].asText())
        assertEquals(head(), add.headers["Kotobaya-Seq"]?.toLong())

        // a note with an entry is keyed by entry
        val vocab = client.call(HttpMethod.Post, "/notes", bearer = token, body = mapOf("note_type_id" to "00000000-0000-7000-8000-000000000101", "entry_seq" to 200, "fields" to mapOf("expression" to "x")))
        assertEquals(201, vocab.status.value, vocab.json().toString())
        assertProblem(client.call(HttpMethod.Put, "/decks/$deck/items/n:${vocab.json()["id"].asText()}", bearer = token), 422, "validation_failed")

        val moved = client.call(HttpMethod.Put, "/decks/$deck/items/e:100", bearer = token, body = mapOf("position" to 1))
        assertEquals(200, moved.status.value)
        assertEquals(1, moved.json()["position"].asInt())
        val kept = client.call(HttpMethod.Put, "/decks/$deck/items/e:100", bearer = token)
        assertEquals(1, kept.json()["position"].asInt(), "no body keeps the position of a live item")
        assertProblem(client.call(HttpMethod.Put, "/decks/$deck/items/e:999", bearer = token), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Put, "/decks/$deck/items/n:01931f40-1a2b-7c3d-8e4f-000000000fff", bearer = token), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Put, "/decks/$deck/items/E:100", bearer = token), 422, "validation_failed")
        val bad = head()
        assertProblem(
            client.call(HttpMethod.Post, "/decks/$deck/items", bearer = token, body = mapOf("items" to listOf(mapOf("entry_seq" to 100), mapOf("entry_seq" to 12345)))),
            422, "validation_failed",
        )
        assertEquals(bad, head(), "nothing is applied when an element is invalid")

        val page = client.call(HttpMethod.Get, "/decks/$deck/items?limit=2", bearer = token)
        assertEquals(listOf("e:100", "e:200"), keys(page))
        val next = client.call(HttpMethod.Get, "/decks/$deck/items?limit=2&cursor=${page.json()["next_cursor"].asText()}", bearer = token)
        assertEquals(listOf("n:$n"), keys(next))
        assertTrue(next.json()["next_cursor"].isNull)
        assertEquals(listOf("e:100", "e:200"), keys(client.call(HttpMethod.Get, "/decks/$deck/items?status=new", bearer = token)))

        val mark = head()
        assertEquals(204, client.call(HttpMethod.Delete, "/decks/$deck/items/e:100", bearer = token).status.value)
        assertEquals(mark + 1, head())
        val again = client.call(HttpMethod.Delete, "/decks/$deck/items/e:100", bearer = token)
        assertEquals(204, again.status.value)
        assertNull(again.headers["Kotobaya-Seq"])
        assertEquals(mark + 1, head())
        assertEquals(listOf("e:200", "n:$n"), keys(client.call(HttpMethod.Get, "/decks/$deck/items", bearer = token)))
        // re-adding clears the removal
        assertEquals(1, client.call(HttpMethod.Put, "/decks/$deck/items/e:100", bearer = token, body = mapOf("position" to 1)).json()["position"].asInt())
    }

    @Test
    fun concurrentItemPutsAndRemovalsFromTwoDevicesConvergeBySection7_1RegisterRules() = deckApp {
        val token = writer()
        val deck = userDeck(token)
        val t = 0x0193a1b2c200L
        fun put(device: String, at: Long, key: String, pos: Long) =
            op(OpType.DECK_ITEM_PUT, device, at, """{"deck_id":"$deck","item_key":"$key","position":$pos}""")
        fun remove(device: String, at: Long, key: String) = op(OpType.DECK_ITEM_REMOVED, device, at, """{"deck_id":"$deck","item_key":"$key"}""")
        // key 1: the older put arrives last and loses. key 2: the newer put arrives last and wins.
        pushAsOwner(put(A, t + 50, "e:1", 100))
        pushAsOwner(put(B, t + 10, "e:1", 200))
        pushAsOwner(put(B, t + 10, "e:2", 200))
        pushAsOwner(put(A, t + 50, "e:2", 100))
        // key 3: a removal newer than the put wins even when it arrives first; a late older put does not resurrect it.
        pushAsOwner(remove(B, t + 90, "e:3"))
        pushAsOwner(put(A, t + 60, "e:3", 300))
        // key 4: a put newer than the removal brings it back, whatever the arrival order.
        pushAsOwner(put(A, t + 95, "e:4", 400))
        pushAsOwner(remove(B, t + 70, "e:4"))
        val rows = db.txBlocking { dsl ->
            dsl.fetch("select item_key, position, deleted_at is not null from app.deck_item where deck_id = ?::uuid order by item_key", deck)
                .map { Triple(it.get(0, String::class.java), (it.get(1) as Number).toLong(), it.get(2, Boolean::class.java)) }
        }
        assertEquals(listOf(Triple("e:1", 100L, false), Triple("e:2", 100L, false), Triple("e:3", 0L, true), Triple("e:4", 400L, false)), rows)

        // REST reads see the converged state
        assertEquals(listOf("e:1", "e:2", "e:4"), keys(client.call(HttpMethod.Get, "/decks/$deck/items", bearer = token)))
    }

    @Test
    fun itemsKeyedToAMergedAwayNoteResolveToTheSurvivorAndRemovalCoversEveryKey() = deckApp {
        val token = writer()
        val deck = userDeck(token)
        val loser = note(token)
        val survivor = note(token)
        client.call(HttpMethod.Put, "/decks/$deck/items/n:$loser", bearer = token)
        // the merge itself is T202c; set the state a merge leaves behind
        db.txBlocking { it.execute("update app.note set deleted_at = now(), merged_into_id = ?::uuid where id = ?::uuid", survivor, loser) }
        val shown = client.call(HttpMethod.Get, "/decks/$deck/items", bearer = token)
        assertEquals(listOf("n:$survivor"), keys(shown))
        assertEquals(survivor, shown.json()["items"][0]["note_id"].asText())
        assertEquals(1, client.call(HttpMethod.Get, "/decks/$deck", bearer = token).json()["item_count"].asInt())

        // the survivor is also in the deck: one item, the smaller (position, item_key)
        client.call(HttpMethod.Put, "/decks/$deck/items/n:$survivor", bearer = token, body = mapOf("position" to 7))
        val both = client.call(HttpMethod.Get, "/decks/$deck/items", bearer = token)
        assertEquals(listOf("n:$survivor"), keys(both))
        assertEquals(7, both.json()["items"][0]["position"].asInt())
        assertEquals(1, client.call(HttpMethod.Get, "/decks/$deck", bearer = token).json()["item_count"].asInt())

        // removal: one item_removed per live key of the identity
        val mark = head()
        assertEquals(204, client.call(HttpMethod.Delete, "/decks/$deck/items/n:$survivor", bearer = token).status.value)
        assertEquals(listOf("deck.item_removed", "deck.item_removed"), opsSince(mark))
        assertEquals(emptyList(), client.call(HttpMethod.Get, "/decks/$deck/items", bearer = token).json()["items"].toList())

        // putting a loser key re-adds on the survivor
        val put = client.call(HttpMethod.Put, "/decks/$deck/items/n:$loser", bearer = token)
        assertEquals(200, put.status.value, put.json().toString())
        assertEquals("n:$survivor", put.json()["item_key"].asText())
    }

    @Test
    fun readAndWriteScopesAreEnforced() = deckApp {
        val reader = mint(TokenKind.PERSONAL, listOf("read")).second
        assertProblem(createDeck(reader, mapOf("type" to "user", "name" to "x")), 403, "insufficient_scope")
        assertEquals(200, client.call(HttpMethod.Get, "/decks", bearer = reader).status.value)
        assertProblem(client.call(HttpMethod.Get, "/decks"), 401, "unauthenticated")
    }
}
