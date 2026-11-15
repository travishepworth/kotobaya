package kotobaya.android.decks

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.NoteRequest
import kotobaya.android.notes.DEVICE_A
import kotobaya.android.notes.T0
import kotobaya.android.notes.creator
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.TestServer
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.store.UuidV7Source

class DeckMembershipTest {
    private fun device() = SqlDevice(DEVICE_A, TestServer(T0), T0)

    private fun membership(d: SqlDevice) =
        DeckMembership(d.store, d.stack.minter, UuidV7Source(Random(99)), d.clock)

    private fun deck(d: SqlDevice, id: String, name: String, priority: Int, type: String = "user") {
        val payload = linkedMapOf<String, JsonValue>(
            "id" to JsonString(id), "name" to JsonString(name), "type" to JsonString(type), "priority" to JsonNumber(priority.toDouble()),
        )
        if (type == "media") payload["media_id"] = JsonString(id)
        assertIs<MintResult.Minted>(d.stack.minter.mint(OpType.DECK_CREATED, JsonObject(payload)))
    }

    private fun liveItems(d: SqlDevice, deckId: String): List<String> = d.store.read { tx ->
        tx.learning.where(LearningTable.DECK_ITEM, "deck_id", JsonString(deckId))
            .filter { (it["deleted_at"] ?: JsonNull) == JsonNull }.map { (it["item_key"] as JsonString).value }
    }

    private val a = "0193a1b2-0004-7000-8000-00000000000a"
    private val b = "0193a1b2-0004-7000-8000-00000000000b"
    private val c = "0193a1b2-0004-7000-8000-00000000000c"

    @Test
    fun aNoteWithAnEntryUsesTheEntryKeyAndACustomNoteUsesItsId() {
        val d = device()
        deck(d, a, "A", 1)
        val m = membership(d)
        val make = creator(d)
        val withEntry = assertIs<CreateResult.Created>(make.create(NoteRequest(word = "食べる", reading = "たべる")))
        val custom = assertIs<CreateResult.Created>(make.create(NoteRequest(word = "ほげほげ")))

        assertEquals("e:1358280", m.itemKey(DeckTarget(noteId = withEntry.noteId)))
        assertEquals("n:${custom.noteId}", m.itemKey(DeckTarget(noteId = custom.noteId)))
        assertIs<MintResult.Minted>(m.set(a, DeckTarget(noteId = withEntry.noteId), true))
        assertIs<MintResult.Minted>(m.set(a, DeckTarget(noteId = custom.noteId), true))
        assertEquals(setOf("e:1358280", "n:${custom.noteId}"), liveItems(d, a).toSet())
        assertEquals(listOf(a), m.decks(DeckTarget(entrySeq = 1358280)).filter { it.member }.map { it.id })
        assertNull(m.set(a, DeckTarget(entrySeq = 1358280), true), "adding twice is a no-op")
    }

    @Test
    fun pickerListsOnlyLiveUserDecksByPriorityAndTogglingOffRemovesTheItem() {
        val d = device()
        deck(d, a, "Zeta", 2)
        deck(d, b, "Alpha", 1)
        deck(d, c, "Media", 0, type = "media")
        val m = membership(d)
        val target = DeckTarget(entrySeq = 1489780)

        assertEquals(listOf(b, a), m.decks(target).map { it.id })
        assertIs<MintResult.Minted>(m.set(a, target, true))
        assertEquals(listOf(false, true), m.decks(target).map { it.member })
        assertNull(m.set(c, target, true), "media decks take no manual items")
        assertEquals(listOf("e:1489780"), liveItems(d, a))

        assertIs<MintResult.Minted>(m.set(a, target, false))
        assertEquals(emptyList(), liveItems(d, a))
        assertEquals(listOf(false, false), m.decks(target).map { it.member })
        assertNotNull(m.set(a, target, true), "it can be put back")
        assertEquals(listOf("e:1489780"), liveItems(d, a))
    }

    @Test
    fun addToMyNotesWithADeckCreatesTheNoteAndTheDeckItemInOneAction() {
        val d = device()
        deck(d, a, "Mined", 1)
        val r = assertIs<CreateResult.Created>(creator(d).create(NoteRequest(word = "猫", reading = "ねこ", deckId = a)))
        assertEquals(OpType.NOTE_CREATED, r.ops.first().opType)
        assertEquals(OpType.DECK_ITEM_PUT, r.ops.last().opType)
        assertEquals(listOf("e:1489780"), liveItems(d, a))
        assertEquals(listOf("Mined"), membership(d).memberOf(DeckTarget(noteId = r.noteId)))
    }
}
