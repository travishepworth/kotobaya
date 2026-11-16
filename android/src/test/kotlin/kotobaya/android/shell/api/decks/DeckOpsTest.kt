package kotobaya.android.shell.api.decks

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.actions.ActionsTestSupport.obj
import kotobaya.android.shell.api.actions.ActionsTestSupport.remote
import kotobaya.android.shell.api.decks.DecksTestSupport.D1
import kotobaya.android.shell.api.decks.DecksTestSupport.SEQ_A
import kotobaya.android.shell.api.decks.DecksTestSupport.SEQ_B
import kotobaya.android.shell.api.decks.DecksTestSupport.T0
import kotobaya.android.shell.api.decks.DecksTestSupport.X
import kotobaya.android.shell.api.decks.DecksTestSupport.Y
import kotobaya.android.shell.api.decks.DecksTestSupport.call
import kotobaya.android.shell.api.decks.DecksTestSupport.device
import kotobaya.android.shell.api.decks.DecksTestSupport.items
import kotobaya.android.shell.api.decks.DecksTestSupport.json
import kotobaya.android.shell.api.decks.DecksTestSupport.liveItemKeys
import kotobaya.android.shell.api.decks.DecksTestSupport.problem
import kotobaya.android.shell.api.decks.DecksTestSupport.rows
import kotobaya.android.shell.api.identity.IdentityTestSupport.isValidOp
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L7 acceptance `DeckOpsTest` and SHELL.md 8.4 (a)-(c) for the deck writers: the minted ops validate and are the
 * device's own, retries and no-op changes mint nothing (L-4, L-2), and applying the ops out of order (interleaved with
 * another device's) on fresh replicas converges. Robolectric: item answers read dictionary summaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DeckOpsTest {
    private val user = "01a1f82f-0000-7000-8000-00000000d001"
    private val smart = "01a1f82f-0000-7000-8000-00000000d002"

    private fun ParityDevice.mintedTypes(): List<String> = minted().map { ParityJson.str(it.members["op_type"]) }

    private fun exercise(d: ParityDevice) {
        assertEquals(201, d.call("POST", "/api/v1/decks", """{"id":"$user","type":"user","name":"Inbox","priority":3,"settings":{"order":"position","x":null}}""", at = T0 + 1_000).status)
        assertEquals(201, d.call("POST", "/api/v1/decks", """{"id":"$smart","type":"smart","name":"Leeches","query":"tag:leech"}""", at = T0 + 2_000).status)
        assertEquals(200, d.call("PATCH", "/api/v1/decks/$user", """{"name":"Inbox 2","new_cards_per_day":5,"settings":{"add_kanji":"before"}}""", at = T0 + 3_000).status)
        assertEquals(200, d.call("POST", "/api/v1/decks/$user/items", """{"items":[{"entry_seq":$SEQ_A},{"entry_seq":$SEQ_B,"position":10}]}""", at = T0 + 4_000).status)
        assertEquals(200, d.call("PUT", "/api/v1/decks/$user/items/e:1000220", at = T0 + 5_000).status)
        assertEquals(200, d.call("PUT", "/api/v1/decks/$user/items/e:$SEQ_A", """{"position":1}""", at = T0 + 6_000).status)
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$user/items/e:$SEQ_B", at = T0 + 7_000).status)
        assertEquals(200, d.call("PATCH", "/api/v1/decks/$smart", """{"query":"tag:hard"}""", at = T0 + 8_000).status)
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$smart", at = T0 + 9_000).status)
    }

    @Test
    fun everyMintedOpValidatesAndIsTheDevices() {
        val d = device()
        exercise(d)
        val ops = d.minted()
        assertEquals(
            listOf(
                "deck.created", "deck.created", "deck.updated", "deck.item_put", "deck.item_put", "deck.item_put", "deck.item_put",
                "deck.item_removed", "deck.updated", "deck.deleted",
            ),
            ops.map { ParityJson.str(it.members["op_type"]) },
        )
        ops.forEach { op ->
            assertTrue(isValidOp(op), "invalid op ${ParityJson.encode(op)}")
            assertEquals(X, ParityJson.str(op.members["device_id"]))
        }
        assertEquals(ops.map { ParityJson.str(it.members["hlc"]) }.sorted(), ops.map { ParityJson.str(it.members["hlc"]) })
        // the null settings value is dropped at creation (server: create drops nulls)
        assertEquals(
            ParityJson.parse("""{"id":"$user","name":"Inbox","type":"user","priority":3,"settings":{"order":"position"}}"""),
            ParityJson.at(ops[0], "/payload"),
        )
        // appended after the current maximum (none: 0), 1024 apart; explicit positions kept
        assertEquals(listOf(1024.0, 10.0), ops.subList(3, 5).map { (ParityJson.at(it, "/payload/position") as JsonNumber).value })
        // putting an item without position appends; putting a live one with a position moves it
        assertEquals(2048.0, (ParityJson.at(ops[5], "/payload/position") as JsonNumber).value)
        assertEquals(1.0, (ParityJson.at(ops[6], "/payload/position") as JsonNumber).value)
        assertEquals(d.localChanges, ops.size - 1, "one trigger per minting request (the add minted two ops)")
    }

    @Test
    fun addSameItemTwiceMintsOneOpAndNeverADuplicateRow() {
        val d = device()
        val r = d.call("POST", "/api/v1/decks/$D1/items", """{"items":[{"entry_seq":$SEQ_A,"position":5},{"entry_seq":$SEQ_A}]}""")
        assertEquals(200, r.status)
        assertEquals(listOf("deck.item_put"), d.mintedTypes())
        // the last element wins: no position -> appended after 3072
        assertEquals(listOf("e:$SEQ_A" to 4096.0), r.items().map { ParityJson.str(it.members["item_key"]) to (it.members["position"] as JsonNumber).value })
        // again, in a second request: the put is repeated (a put that changes nothing still mints, L-2), the row is one
        assertEquals(200, d.call("POST", "/api/v1/decks/$D1/items", """{"items":[{"entry_seq":$SEQ_A}]}""").status)
        assertEquals(200, d.call("PUT", "/api/v1/decks/$D1/items/e:$SEQ_A").status)
        assertEquals(1, d.liveItemKeys(D1).count { it == "e:$SEQ_A" })
        assertEquals(4, d.call("GET", "/api/v1/decks/$D1").json().members["item_count"].let { (it as JsonNumber).value.toInt() })
        assertEquals(4, d.call("GET", "/api/v1/decks/$D1/items").items().size)
    }

    @Test
    fun removeMissingItemSucceedsAndMintsNothing() {
        val d = device()
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$D1/items/e:$SEQ_A").status)
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$D1/items/n:0193a1b2-0001-7000-8000-0000000000aa").status)
        assertEquals(emptyList(), d.mintedTypes())
        assertEquals(0, d.localChanges)
        // removing twice: the second removal finds nothing live
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$D1/items/e:1000220").status)
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$D1/items/e:1000220").status)
        assertEquals(listOf("deck.item_removed"), d.mintedTypes())
    }

    @Test
    fun deleteDeckThenPulledOlderItemAddKeepsTheItemGone() {
        val d = device()
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$D1", at = T0 + 10_000).status)
        // B added an item before the delete; it arrives later by pull
        val olderPut = remote(
            1, "deck.item_put", "deck_item", "$D1|e:$SEQ_A", T0 + 5_000,
            obj("deck_id" to JsonString(D1), "item_key" to JsonString("e:$SEQ_A"), "position" to JsonNumber(4096.0)),
        )
        d.clock.now = T0 + 20_000
        d.pull(listOf(olderPut), "B's older item add")
        assertEquals("410 gone", d.call("GET", "/api/v1/decks/$D1").problem())
        assertEquals("410 gone", d.call("GET", "/api/v1/decks/$D1/items").problem())
        assertTrue(d.call("GET", "/api/v1/decks").items().none { it.members["id"] == JsonString(D1) })
        assertEquals("410 gone", d.call("PUT", "/api/v1/decks/$D1/items/e:$SEQ_A").problem())
        // the tombstone is permanent: include_deleted shows the deck deleted; a second delete mints nothing
        val shown = d.call("GET", "/api/v1/decks/$D1", query = "include_deleted=true").json()
        assertTrue(shown.members["deleted_at"] is JsonString)
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$D1").status)
        assertEquals(listOf("deck.deleted"), d.mintedTypes())
        // nothing of the deck reaches the queue (its entries are no longer drawn)
        val queue = d.call("GET", "/api/v1/review/queue").json()
        assertEquals(0.0, (ParityJson.at(queue, "/counts/new") as JsonNumber).value)
    }

    @Test
    fun createRetryWithSameIdAnswersTheStoredDeckAndMintsNothing() {
        val d = device()
        val first = d.call("POST", "/api/v1/decks", """{"id":"$user","type":"user","name":"Inbox"}""")
        assertEquals(201, first.status)
        val retry = d.call("POST", "/api/v1/decks", """{"id":"$user","type":"smart","name":"Other","query":"tag:x"}""")
        assertEquals(200, retry.status)
        assertEquals(first.json(), retry.json())
        // a deleted deck is still "existing" (L-4)
        assertEquals(204, d.call("DELETE", "/api/v1/decks/$user").status)
        assertEquals(200, d.call("POST", "/api/v1/decks", """{"id":"$user","type":"user","name":"Inbox"}""").status)
        assertEquals(listOf("deck.created", "deck.deleted"), d.mintedTypes())
    }

    @Test
    fun listDeckIsForwardedAndUserOrSmartNever() {
        val d = device()
        // offline: the forward answers 503 shell_offline, nothing minted
        assertEquals("503 shell_offline", d.call("POST", "/api/v1/decks", """{"type":"list","list_key":"jlpt-n3"}""").problem())
        assertEquals(listOf("createDeck POST /api/v1/decks"), d.forwarded)
        // validated locally first: no forward for a bad request
        assertEquals("422 /list_key required", d.call("POST", "/api/v1/decks", """{"type":"list"}""").problem())
        assertEquals("422 /type invalid_value", d.call("POST", "/api/v1/decks", """{"type":"media","name":"x"}""").problem())
        assertEquals(1, d.forwarded.size)
        assertEquals(emptyList(), d.mintedTypes())
    }

    @Test
    fun opsAppliedOutOfOrderWithAnotherDevicesConverge() {
        val d = device()
        exercise(d)
        val theirs = listOf(
            remote(1, "deck.updated", "deck", user, T0 + 3_500, obj("id" to JsonString(user), "set" to obj("name" to JsonString("Theirs"), "priority" to JsonNumber(9.0)))),
            remote(2, "deck.item_removed", "deck_item", "$user|e:$SEQ_A", T0 + 4_500, obj("deck_id" to JsonString(user), "item_key" to JsonString("e:$SEQ_A"))),
            remote(3, "deck.item_put", "deck_item", "$D1|e:$SEQ_B", T0 + 2_500, obj("deck_id" to JsonString(D1), "item_key" to JsonString("e:$SEQ_B"), "position" to JsonNumber(7.0))),
        )
        d.clock.now = T0 + 20_000
        d.pull(theirs, "B's concurrent writes")
        // a deck's creation precedes every op that names it in any log (causal order; the server rejects the rest
        // as unknown_reference), so the oracles permute everything else
        val (creates, all) = (d.minted() + theirs).partition { ParityJson.str(it.members["op_type"]) == "deck.created" }
        val orders = listOf<(List<JsonObject>) -> List<JsonObject>>(
            { ops -> ops.sortedBy { ParityJson.str(it.members["hlc"]) } },
            { ops -> ops.sortedByDescending { ParityJson.str(it.members["hlc"]) } },
            { ops -> ops.shuffled(Random(21)) },
            { ops -> ops.shuffled(Random(22)) },
        )
        for ((i, order) in orders.withIndex()) {
            val r = device(Y)
            r.clock.now = T0 + 30_000
            (creates + order(all)).forEach { r.pull(listOf(it), "oracle") }
            for (t in listOf(LearningTable.DECK, LearningTable.DECK_ITEM)) assertEquals(d.rows(t), r.rows(t), "order $i: $t")
        }
        // B's later name won over this device's earlier one; this device's later put of SEQ_A beat B's removal
        val deck = d.call("GET", "/api/v1/decks/$user").json()
        assertEquals(JsonString("Theirs"), deck.members["name"])
        assertTrue("e:$SEQ_A" in d.liveItemKeys(user))
    }
}
