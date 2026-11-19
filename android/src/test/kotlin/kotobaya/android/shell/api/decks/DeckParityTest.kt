package kotobaya.android.shell.api.decks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.actions.ActionsTestSupport.iso
import kotobaya.android.shell.api.decks.DecksTestSupport.D1
import kotobaya.android.shell.api.decks.DecksTestSupport.DECK_CREATED_MS
import kotobaya.android.shell.api.decks.DecksTestSupport.SEQ_A
import kotobaya.android.shell.api.decks.DecksTestSupport.T0
import kotobaya.android.shell.api.decks.DecksTestSupport.UNKNOWN
import kotobaya.android.shell.api.decks.DecksTestSupport.call
import kotobaya.android.shell.api.decks.DecksTestSupport.device
import kotobaya.android.shell.api.decks.DecksTestSupport.items
import kotobaya.android.shell.api.decks.DecksTestSupport.json
import kotobaya.android.shell.api.decks.DecksTestSupport.problem
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L7 parity scenarios (SHELL.md L-3) for list/get/items and each write, against the answers of the server's
 * `DeckApi` / `DeckItemApi` / `DeckViews` for the same request on the same data: statuses, problem codes and pointers,
 * and bodies. The deck scenarios have no `contracts/api/shell/parity` file yet (HANDOFF: contract follow-up), so the
 * server's answers are written out here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DeckParityTest {
    private fun coreWords(itemCount: Int = 3, extra: String = ""): JsonObject = ParityJson.obj(
        ParityJson.parse(
            """{"id":"$D1","name":"Core words","type":"user","language":"ja","priority":0,"enabled":true,
               "new_cards_per_day":null,"reviews_per_day":null,"settings":{},"media_id":null,"list_key":null,"query":null,
               "content_version":0,"content_blob_hash":null,"item_count":$itemCount,
               "progress":{"learned":0,"total":$itemCount,"percent":0},
               "created_at":"${iso(DECK_CREATED_MS)}","updated_at":"${iso(DECK_CREATED_MS)}","deleted_at":null$extra}""",
        ),
    )

    @Test
    fun listAndGetDecks() {
        val d = device()
        assertEquals(listOf(coreWords()), d.call("GET", "/api/v1/decks").items())
        assertEquals(listOf(coreWords()), d.call("GET", "/api/v1/decks", query = "type=user").items())
        assertEquals(emptyList(), d.call("GET", "/api/v1/decks", query = "type=media").items())
        assertEquals(coreWords(), d.call("GET", "/api/v1/decks/$D1").json())
        assertEquals("422 /type invalid_value", d.call("GET", "/api/v1/decks", query = "type=other").problem())
        assertEquals("422 /include_deleted invalid_value", d.call("GET", "/api/v1/decks", query = "include_deleted=yes").problem())
        assertEquals("422 /foo unknown_field", d.call("GET", "/api/v1/decks", query = "foo=1").problem())
        assertEquals("404 not_found", d.call("GET", "/api/v1/decks/$UNKNOWN").problem())
        assertEquals("404 not_found", d.call("GET", "/api/v1/decks/not-a-uuid").problem())
    }

    /** ADR-041: `Deck.progress` is core `DeckProgress` over the deck's words: known counts as learned, blacklisted is in neither number. */
    @Test
    fun deckProgressCountsKnownAndLeavesOutBlacklisted() {
        val d = device()
        fun progress() = d.call("GET", "/api/v1/decks/$D1").json().members["progress"]
        assertEquals(ParityJson.parse("""{"learned":0,"total":3,"percent":0}"""), progress())
        assertEquals(200, d.call("PUT", "/api/v1/words/1001010/status", """{"status":"known"}""", at = T0 + 1_000).status)
        assertEquals(ParityJson.parse("""{"learned":1,"total":3,"percent":33}"""), progress())
        assertEquals(200, d.call("PUT", "/api/v1/words/1001150/status", """{"status":"blacklisted"}""", at = T0 + 2_000).status)
        assertEquals(ParityJson.parse("""{"learned":1,"total":2,"percent":50}"""), progress())
        val smart = "01a1f82f-0000-7000-8000-00000000d002"
        assertEquals(201, d.call("POST", "/api/v1/decks", """{"id":"$smart","type":"smart","name":"S","query":"tag:a"}""", at = T0 + 3_000).status)
        assertEquals(kotobaya.contracts.sync.JsonNull, d.call("GET", "/api/v1/decks/$smart").json().members["progress"])
    }

    @Test
    fun listDecksIsInDrawOrderAndHidesTombstonesUnlessAsked() {
        val d = device()
        val first = "01a1f82f-0000-7000-8000-00000000d001"
        assertEquals(201, d.call("POST", "/api/v1/decks", """{"id":"$first","type":"user","name":"First","priority":-5}""").status)
        assertEquals(listOf(first, D1), d.call("GET", "/api/v1/decks").items().map { ParityJson.str(it.members["id"]) })
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$first", at = T0 + 1_000).status)
        assertEquals(listOf(D1), d.call("GET", "/api/v1/decks").items().map { ParityJson.str(it.members["id"]) })
        val all = d.call("GET", "/api/v1/decks", query = "include_deleted=true").items()
        assertEquals(listOf(first, D1), all.map { ParityJson.str(it.members["id"]) })
        assertEquals(JsonString(iso(T0 + 1_000)), all[0].members["deleted_at"])
        assertEquals("410 gone", d.call("GET", "/api/v1/decks/$first").problem())
    }

    @Test
    fun listDeckItemsPagesSortsAndFilters() {
        val d = device()
        val page = d.call("GET", "/api/v1/decks/$D1/items").json()
        val items = (page.members["items"] as kotobaya.contracts.sync.JsonArray).items.map(ParityJson::obj)
        assertEquals(listOf("e:1001010", "e:1001150", "e:1001580"), items.map { ParityJson.str(it.members["item_key"]) })
        assertEquals(JsonNull, page.members["next_cursor"])
        val first = items[0]
        assertEquals(JsonString(D1), first.members["deck_id"])
        assertEquals(JsonNumber(1001010.0), first.members["entry_seq"])
        assertEquals(JsonNull, first.members["note_id"])
        assertEquals(JsonNumber(1024.0), first.members["position"])
        assertEquals(JsonNumber(0.0), first.members["occurrence_count"])
        assertEquals(JsonString("new"), first.members["status"])
        assertEquals(
            setOf("deck_id", "item_key", "entry_seq", "note_id", "position", "occurrence_count", "added_at", "entry", "status"),
            first.members.keys,
        )
        val entry = ParityJson.obj(first.members["entry"])
        assertEquals(
            setOf("seq", "headword", "headword_reading", "is_common", "glosses", "pos", "freq_rank", "retired", "status", "note_id", "deck_ids"),
            entry.members.keys,
        )
        assertEquals(ParityJson.parse("""["$D1"]"""), entry.members["deck_ids"])

        // keyset pages: 2 + 1, the same items in the same order
        val p1 = d.call("GET", "/api/v1/decks/$D1/items", query = "limit=2").json()
        val cursor = ParityJson.str(p1.members["next_cursor"])
        val p2 = d.call("GET", "/api/v1/decks/$D1/items", query = "limit=2&cursor=$cursor").json()
        val keys = (ParityJson.arr(p1.members["items"]) + ParityJson.arr(p2.members["items"])).map { ParityJson.str(ParityJson.obj(it).members["item_key"]) }
        assertEquals(listOf("e:1001010", "e:1001150", "e:1001580"), keys)
        assertEquals(JsonNull, p2.members["next_cursor"])
        // a cursor of another request shape is refused
        assertEquals("422 /cursor invalid_value", d.call("GET", "/api/v1/decks/$D1/items", query = "limit=2&sort=freq_rank&cursor=$cursor").problem())

        // status filter on the effective word status
        assertEquals(emptyList(), d.call("GET", "/api/v1/decks/$D1/items", query = "status=known").items())
        assertEquals(200, d.call("PUT", "/api/v1/words/1001150/status", """{"status":"known"}""").status)
        assertEquals(listOf("e:1001150"), d.call("GET", "/api/v1/decks/$D1/items", query = "status=known,ignored").items().map { ParityJson.str(it.members["item_key"]) })

        // freq_rank: most frequent first, NULL last, ties by identity
        val byFreq = d.call("GET", "/api/v1/decks/$D1/items", query = "sort=freq_rank").items()
        val ranks = byFreq.map { (ParityJson.obj(it.members["entry"]).members["freq_rank"] as? JsonNumber)?.value }
        assertEquals(ranks.filterNotNull().sorted(), ranks.filterNotNull())
        assertTrue(ranks.dropWhile { it != null }.all { it == null })

        assertEquals("422 /status invalid_value", d.call("GET", "/api/v1/decks/$D1/items", query = "status=new,nope").problem())
        assertEquals("422 /sort invalid_value", d.call("GET", "/api/v1/decks/$D1/items", query = "sort=name").problem())
        assertEquals("422 /limit out_of_range", d.call("GET", "/api/v1/decks/$D1/items", query = "limit=501").problem())
        assertEquals("404 not_found", d.call("GET", "/api/v1/decks/$UNKNOWN/items").problem())
    }

    @Test
    fun smartDeckHasNoItems() {
        val d = device()
        val smart = "01a1f82f-0000-7000-8000-00000000d002"
        assertEquals(201, d.call("POST", "/api/v1/decks", """{"id":"$smart","type":"smart","name":"S","query":"is:new"}""").status)
        assertEquals("409 not_applicable", d.call("GET", "/api/v1/decks/$smart/items").problem())
        assertEquals("409 not_applicable", d.call("PUT", "/api/v1/decks/$smart/items/e:$SEQ_A").problem())
        assertEquals("409 not_applicable", d.call("DELETE", "/api/v1/decks/$smart/items/e:$SEQ_A").problem())
        assertEquals(JsonNumber(0.0), d.call("GET", "/api/v1/decks/$smart").json().members["item_count"])
    }

    @Test
    fun createDeck() {
        val d = device()
        val r = d.call("POST", "/api/v1/decks", """{"type":"user","name":"Inbox","new_cards_per_day":null,"enabled":false}""", at = T0 + 5_000)
        assertEquals(201, r.status)
        val body = r.json()
        val id = ParityJson.str(body.members["id"])
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id), id)
        assertEquals(
            ParityJson.parse(
                """{"id":"$id","name":"Inbox","type":"user","language":"ja","priority":0,"enabled":false,
                   "new_cards_per_day":null,"reviews_per_day":null,"settings":{},"media_id":null,"list_key":null,"query":null,
                   "content_version":0,"content_blob_hash":null,"item_count":0,
                   "progress":{"learned":0,"total":0,"percent":0},
                   "created_at":"${iso(T0 + 5_000)}","updated_at":"${iso(T0 + 5_000)}","deleted_at":null}""",
            ),
            body,
        )
        assertEquals("422 /type required", d.call("POST", "/api/v1/decks", """{"name":"x"}""").problem())
        assertEquals("422 /name required", d.call("POST", "/api/v1/decks", """{"type":"user"}""").problem())
        assertEquals("422 /query unexpected", d.call("POST", "/api/v1/decks", """{"type":"user","name":"x","query":"a"}""").problem())
        assertEquals("422 /query required", d.call("POST", "/api/v1/decks", """{"type":"smart","name":"x"}""").problem())
        assertEquals("422 /priority invalid_value", d.call("POST", "/api/v1/decks", """{"type":"user","name":"x","priority":1000001}""").problem())
        assertEquals("422 /settings/order invalid_value", d.call("POST", "/api/v1/decks", """{"type":"user","name":"x","settings":{"order":"random"}}""").problem())
        assertEquals("422 /color unknown_field", d.call("POST", "/api/v1/decks", """{"type":"user","name":"x","color":"red"}""").problem())
        assertEquals("422 /name invalid_value", d.call("POST", "/api/v1/decks", """{"type":"user","name":"a\u0000b"}""").problem())
        assertEquals("422 /query unbalanced_paren", d.call("POST", "/api/v1/decks", """{"type":"smart","name":"x","query":"(tag:a"}""").problem())
        assertEquals("400 invalid_request", d.call("POST", "/api/v1/decks", "[]").problem())
    }

    @Test
    fun updateDeck() {
        val d = device()
        val r = d.call("PATCH", "/api/v1/decks/$D1", """{"name":"Core","priority":2,"reviews_per_day":50,"settings":{"order":"global_frequency"}}""", at = T0 + 7_000)
        assertEquals(200, r.status)
        val body = r.json()
        assertEquals(JsonString("Core"), body.members["name"])
        assertEquals(JsonNumber(2.0), body.members["priority"])
        assertEquals(JsonNumber(50.0), body.members["reviews_per_day"])
        assertEquals(ParityJson.parse("""{"order":"global_frequency"}"""), body.members["settings"])
        assertEquals(JsonString(iso(T0 + 7_000)), body.members["updated_at"])
        assertEquals(body, d.call("GET", "/api/v1/decks/$D1").json())
        // a null settings value removes the key; a null limit removes the limit
        val cleared = d.call("PATCH", "/api/v1/decks/$D1", """{"settings":{"order":null},"reviews_per_day":null}""", at = T0 + 8_000).json()
        assertEquals(ParityJson.parse("{}"), cleared.members["settings"])
        assertEquals(JsonNull, cleared.members["reviews_per_day"])

        assertEquals("422  required", d.call("PATCH", "/api/v1/decks/$D1", "{}").problem())
        assertEquals("422 /type immutable", d.call("PATCH", "/api/v1/decks/$D1", """{"type":"smart"}""").problem())
        assertEquals("422 /settings required", d.call("PATCH", "/api/v1/decks/$D1", """{"settings":{}}""").problem())
        assertEquals("422 /query invalid_value", d.call("PATCH", "/api/v1/decks/$D1", """{"query":"tag:a"}""").problem())
        assertEquals("404 not_found", d.call("PATCH", "/api/v1/decks/$UNKNOWN", """{"name":"x"}""").problem())
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$D1").status)
        assertEquals("410 gone", d.call("PATCH", "/api/v1/decks/$D1", """{"name":"x"}""").problem())
    }

    @Test
    fun deleteDeck() {
        val d = device()
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$D1").status)
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$D1").status)
        assertEquals("404 not_found", d.call("DELETE", "/api/v1/decks/$UNKNOWN").problem())
    }

    @Test
    fun addPutAndRemoveItems() {
        val d = device()
        val added = d.call("POST", "/api/v1/decks/$D1/items", """{"items":[{"entry_seq":$SEQ_A},{"entry_seq":1001010,"position":1}]}""", at = T0 + 3_000)
        assertEquals(200, added.status)
        val items = added.items()
        assertEquals(listOf("e:$SEQ_A" to 4096.0, "e:1001010" to 1.0), items.map { ParityJson.str(it.members["item_key"]) to (it.members["position"] as JsonNumber).value })
        assertEquals(JsonString(iso(T0 + 3_000)), items[0].members["added_at"])
        assertNotEquals(JsonNull, items[0].members["entry"])

        // put: an existing live item without position keeps it; a new note item of a note without entry is n:<id>
        assertEquals(JsonNumber(4096.0), d.call("PUT", "/api/v1/decks/$D1/items/e:$SEQ_A").json().members["position"])
        assertEquals(JsonNumber(8.0), d.call("PUT", "/api/v1/decks/$D1/items/e:$SEQ_A", """{"position":8}""").json().members["position"])

        assertEquals("422 /item_key invalid_value", d.call("PUT", "/api/v1/decks/$D1/items/x:1").problem())
        assertEquals("422 /item_key invalid_value", d.call("PUT", "/api/v1/decks/$D1/items/e:9999999").problem())
        assertEquals("422 /item_key invalid_value", d.call("PUT", "/api/v1/decks/$D1/items/n:0193a1b2-0001-7000-8000-0000000000aa").problem())
        assertEquals("422 /position invalid_value", d.call("PUT", "/api/v1/decks/$D1/items/e:$SEQ_A", """{"position":1.5}""").problem())
        assertEquals("422 /items/1/entry_seq invalid_value", d.call("POST", "/api/v1/decks/$D1/items", """{"items":[{"entry_seq":$SEQ_A},{"entry_seq":9999999}]}""").problem())
        assertEquals("422 /items/0 invalid_value", d.call("POST", "/api/v1/decks/$D1/items", """{"items":[{"entry_seq":$SEQ_A,"note_id":"$UNKNOWN"}]}""").problem())
        assertEquals("422 /items/0/note_id invalid_value", d.call("POST", "/api/v1/decks/$D1/items", """{"items":[{"note_id":"$UNKNOWN"}]}""").problem())
        assertEquals("422 /items out_of_range", d.call("POST", "/api/v1/decks/$D1/items", """{"items":[]}""").problem())
        assertEquals("404 not_found", d.call("POST", "/api/v1/decks/$UNKNOWN/items", """{"items":[{"entry_seq":$SEQ_A}]}""").problem())

        assertEquals(204, d.call("DELETE", "/api/v1/decks/$D1/items/e:$SEQ_A").status)
        assertEquals(listOf("e:1001010", "e:1001150", "e:1001580"), d.call("GET", "/api/v1/decks/$D1/items").items().map { ParityJson.str(it.members["item_key"]) })
    }

    @Test
    fun noteItemsResolveToTheEntryForm() {
        val d = device()
        // materialize the deck's first entries into notes (C-27), then add one of those notes by id
        d.call("GET", "/api/v1/review/queue")
        val noteId = d.call("GET", "/api/v1/decks/$D1/items").items().first().let { ParityJson.obj(it.members["entry"]).members["note_id"] }
        assertTrue(noteId is JsonString, "the first entry has a note after materialization")
        val nid = (noteId as JsonString).value
        val other = "01a1f82f-0000-7000-8000-00000000d003"
        assertEquals(201, d.call("POST", "/api/v1/decks", """{"id":"$other","type":"user","name":"O"}""").status)
        val r = d.call("POST", "/api/v1/decks/$other/items", """{"items":[{"note_id":"$nid"}]}""")
        assertEquals(listOf("e:1001010"), r.items().map { ParityJson.str(it.members["item_key"]) })
        assertEquals("422 /item_key invalid_value", d.call("PUT", "/api/v1/decks/$other/items/n:$nid").problem())
        // removing by the note key removes the entry-form row of the same word
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$other/items/n:$nid").status)
        assertEquals(emptyList(), d.call("GET", "/api/v1/decks/$other/items").items())
    }
}
