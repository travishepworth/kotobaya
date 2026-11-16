package kotobaya.android.shell.api.mining

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.identity.IdentityTestSupport.isValidOp
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.mining.MiningTestSupport.KANJI
import kotobaya.android.shell.api.mining.MiningTestSupport.T0
import kotobaya.android.shell.api.mining.MiningTestSupport.VOCAB
import kotobaya.android.shell.api.mining.MiningTestSupport.X
import kotobaya.android.shell.api.mining.MiningTestSupport.call
import kotobaya.android.shell.api.mining.MiningTestSupport.code
import kotobaya.android.shell.api.mining.MiningTestSupport.device
import kotobaya.android.shell.api.mining.MiningTestSupport.fieldError
import kotobaya.android.shell.api.mining.MiningTestSupport.json
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L5 acceptance `CreateNoteOpsTest` (SHELL.md L-2, L-4, 8.4): `createNote` mints, as this device, ops that
 * validate against `op.schema.json`: one `note.created` and the expected `card.created`s (and `deck.item_put`) in ONE
 * batch (one transaction, one local-change trigger); a retry with the same `id` mints nothing; a refused request mints
 * nothing; applying the ops on another replica, in any order, gives the same rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class CreateNoteOpsTest {
    private val note = "0193b000-0001-7000-8000-0000000000c1"

    private fun ParityDevice.types() = minted().map { ParityJson.str(it.members["op_type"]) }

    private fun ParityDevice.readingCards(on: Boolean) =
        assertEquals(200, call("PATCH", "/api/v1/settings", """{"values":{"cards.reading_enabled":$on}}""").status)

    /** A user deck another device created (decks are SHELL-L7's; this one arrives by pull). */
    private fun ParityDevice.userDeck(id: String) {
        pull(
            listOf(
                kotobaya.android.shell.api.actions.ActionsTestSupport.remote(
                    1, "deck.created", "deck", id, T0 - 60_000,
                    JsonObject(linkedMapOf("id" to JsonString(id), "name" to JsonString("Conversation"), "type" to JsonString("user"))),
                ),
            ),
            "deck",
        )
    }

    @Test
    fun oneNoteCreatedAndItsCardsInOneBatchValidAndTheDevicesOwn() {
        val d = device()
        val before = d.localChanges
        val r = d.call("POST", "/api/v1/notes", """{"id":"$note","note_type_id":"$VOCAB","entry_seq":1000220,"tags":["mined"],"origin":"reader"}""")
        assertEquals(201, r.status)
        assertEquals(listOf("note.created", "card.created"), d.types(), "ADR-035: no reading card while the setting is off")
        assertEquals(before + 1, d.localChanges, "one transaction, one trigger")
        val ops = d.minted()
        ops.forEach { assertTrue(isValidOp(it), "valid op: $it") }
        ops.forEach { assertEquals(JsonString(X), it.members["device_id"]) }
        val created = ParityJson.obj(ops[0].members["payload"])
        assertEquals(JsonString(note), created.members["id"])
        assertEquals(JsonString("reader"), created.members["origin"])
        val card = ParityJson.obj(ops[1].members["payload"])
        assertEquals(JsonString(note), card.members["note_id"])
        assertEquals(JsonString("meaning"), card.members["kind"])
        // the answer is the note as the replica now holds it, created by this device
        val body = r.json()
        assertEquals(JsonString(X), body.members["created_by_device_id"])
        assertEquals(listOf(card.members["id"]), ParityJson.arr(body.members["cards"]).map { ParityJson.obj(it).members["id"] })
        // the cards are in the replica
        assertEquals(1, d.store.read { it.learning.where(LearningTable.CARD, "note_id", JsonString(note)) }.size)
    }

    @Test
    fun sameIdRetryMintsNothingWhateverTheBody() {
        val d = device()
        assertEquals(201, d.call("POST", "/api/v1/notes", """{"id":"$note","note_type_id":"$VOCAB","entry_seq":1000220}""").status)
        val count = d.minted().size
        val changes = d.localChanges
        val retry = d.call("POST", "/api/v1/notes", """{"id":"$note","note_type_id":"$VOCAB","entry_seq":1000440,"tags":["x"]}""")
        assertEquals(200, retry.status)
        assertEquals(JsonString(note), retry.json().members["id"])
        assertEquals(count, d.minted().size)
        assertEquals(changes, d.localChanges)
    }

    @Test
    fun readingEnabledCardKindsAndDeckPlacement() {
        val d = device()
        d.readingCards(true)
        val deck = "0193b000-0004-7000-8000-000000000001"
        d.userDeck(deck)
        val mintedBefore = d.minted().size
        val r = d.call("POST", "/api/v1/notes", """{"id":"$note","note_type_id":"$VOCAB","entry_seq":1000220,"deck_id":"$deck"}""")
        assertEquals(201, r.status)
        val ops = d.minted().drop(mintedBefore)
        assertEquals(listOf("note.created", "card.created", "card.created", "deck.item_put"), ops.map { ParityJson.str(it.members["op_type"]) })
        ops.forEach { assertTrue(isValidOp(it)) }
        assertEquals(listOf("meaning", "reading"), ops.subList(1, 3).map { ParityJson.str(ParityJson.obj(it.members["payload"]).members["kind"]) })
        val put = ParityJson.obj(ops[3].members["payload"])
        assertEquals(JsonString("e:1000220"), put.members["item_key"])
        assertEquals(listOf(JsonString(deck)), (r.json().members["deck_ids"] as JsonArray).items)

        // explicit card_kinds; an empty list creates a note without cards
        val before = d.minted().size
        assertEquals(201, d.call("POST", "/api/v1/notes", """{"note_type_id":"$VOCAB","entry_seq":1000440,"card_kinds":[]}""").status)
        assertEquals(listOf("note.created"), d.minted().drop(before).map { ParityJson.str(it.members["op_type"]) })
    }

    @Test
    fun duplicatesAndRefusalsMintNothing() {
        val d = device()
        assertEquals(201, d.call("POST", "/api/v1/notes", """{"id":"$note","note_type_id":"$VOCAB","entry_seq":1000220}""").status)
        val count = d.minted().size
        val dup = d.call("POST", "/api/v1/notes", """{"note_type_id":"$VOCAB","entry_seq":1000220}""")
        assertEquals(409, dup.status)
        assertEquals("already_exists", dup.code())
        assertEquals(JsonString(note), dup.json().members["existing_id"])
        val existing = d.call("POST", "/api/v1/notes", """{"note_type_id":"$VOCAB","entry_seq":1000220}""", query = "on_duplicate=return_existing")
        assertEquals(200, existing.status)
        assertEquals(JsonString(note), existing.json().members["id"])
        assertEquals("/colour unknown_field", d.call("POST", "/api/v1/notes", """{"note_type_id":"$VOCAB","entry_seq":1000440,"colour":"red"}""").fieldError())
        assertEquals("/kanji_literal invalid_value", d.call("POST", "/api/v1/notes", """{"note_type_id":"$VOCAB","kanji_literal":"学"}""").fieldError())
        assertEquals("/tags/0 invalid_value", d.call("POST", "/api/v1/notes", """{"note_type_id":"$VOCAB","entry_seq":1000440,"tags":["a b"]}""").fieldError())
        assertEquals("/card_kinds/0 invalid_value", d.call("POST", "/api/v1/notes", """{"note_type_id":"$VOCAB","entry_seq":1000440,"card_kinds":["nope"]}""").fieldError())
        assertEquals("/note_type_id not_found", d.call("POST", "/api/v1/notes", """{"note_type_id":"0193ffff-0000-7000-8000-000000000001"}""").fieldError())
        assertEquals("deck_read_only", d.call("POST", "/api/v1/notes", """{"note_type_id":"$VOCAB","entry_seq":1000440,"deck_id":"0193ffff-0000-7000-8000-000000000001"}""").code())
        assertEquals(count, d.minted().size)
        // a kanji note: the literal need not be in the dictionary
        assertEquals(201, d.call("POST", "/api/v1/notes", """{"note_type_id":"$KANJI","kanji_literal":"績"}""").status)
    }

    @Test
    fun theOpsConvergeOnAnotherReplicaWhateverTheBatchOrder() {
        val d = device()
        d.readingCards(true)
        assertEquals(201, d.call("POST", "/api/v1/notes", """{"id":"$note","note_type_id":"$VOCAB","entry_seq":1000220}""").status)
        assertEquals(201, d.call("POST", "/api/v1/notes", """{"note_type_id":"$KANJI","kanji_literal":"学"}""").status)
        val ops = d.minted()
        val tables = listOf(LearningTable.NOTE, LearningTable.CARD)
        fun rows(p: ParityDevice, t: LearningTable) = p.store.read { it.learning.all(t) }.sortedBy { ParityJson.show(it["id"]) }
        // one batch per request (the server logs a batch contiguously, note before its cards); batches in any order
        val batches = ArrayList<MutableList<kotobaya.contracts.sync.JsonObject>>()
        for (op in ops) {
            if (ParityJson.str(op.members["op_type"]) != "card.created") batches += ArrayList<kotobaya.contracts.sync.JsonObject>()
            batches.last() += op
        }
        assertEquals(3, batches.size)
        for (order in listOf(batches, batches.reversed(), listOf(batches[1], batches[2], batches[0]))) {
            val other = device(id = "01931f3e-0000-7000-8000-0000000000d9")
            order.forEach { other.pull(it, "replica") }
            for (t in tables) assertEquals(rows(d, t), rows(other, t), "$t")
        }
    }
}
