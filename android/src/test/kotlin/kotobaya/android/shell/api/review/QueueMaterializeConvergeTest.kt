package kotobaya.android.shell.api.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.QueueTestSupport.at
import kotobaya.android.shell.api.review.QueueTestSupport.cardsOf
import kotobaya.android.shell.api.review.QueueTestSupport.device
import kotobaya.android.shell.api.review.QueueTestSupport.items
import kotobaya.android.shell.api.review.QueueTestSupport.json
import kotobaya.android.shell.api.review.QueueTestSupport.liveNotesOf
import kotobaya.android.shell.api.review.QueueTestSupport.long
import kotobaya.android.shell.api.review.QueueTestSupport.notes
import kotobaya.android.shell.api.review.QueueTestSupport.obj
import kotobaya.android.shell.api.review.QueueTestSupport.op
import kotobaya.android.shell.api.review.QueueTestSupport.queue
import kotobaya.android.shell.api.review.QueueTestSupport.str
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L2 acceptance "MaterializeConvergeTest" (SYNC.md 8.1 "Deck entries materialized twice"): the device
 * materializes a deck entry offline; the server had already materialized the same entry, so when the device's
 * `note.created` reaches it the device's note is the loser. The device then pulls the server's note and cards and the
 * server's `note.merged` / `card.merged`, and ends with one surviving note for the entry, served by the queue, without
 * materializing it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class QueueMaterializeConvergeTest {
    private val entry = 1001010L
    private val serverNote = "0193b000-00aa-7000-8000-000000000001"
    private val serverCard = "0193b000-00aa-7000-8000-000000000002"
    private val vocabType = "00000000-0000-7000-8000-000000000101"

    @Test
    fun aDeviceNoteOfAnEntryTheServerMaterializedFirstMergesIntoTheServersNote() {
        val d = device("queue-materialize-count")
        val first = d.queue().json()
        assertEquals(2, first.items().size)
        val deviceNote = str(d.liveNotesOf(entry).single()["id"])
        val deviceCards = d.cardsOf(deviceNote)
        assertTrue(deviceCards.isNotEmpty())
        val mintedBefore = d.minted().size

        // what the server's log holds for this device after its push (SYNC.md 8.1, "server first")
        var wall = d.clock.now + 60_000
        var n = 0
        fun opId() = "0193b000-00ff-7000-8000-%012x".format(++n)
        val ops = ArrayList<JsonObject>()
        ops += op(
            opId(), "note.created", "note", serverNote, wall++,
            obj(
                "id" to JsonString(serverNote), "note_type_id" to JsonString(vocabType), "entry_seq" to JsonNumber(entry.toDouble()),
                "fields" to JsonObject(emptyMap()), "origin" to JsonString("deck"),
            ),
        )
        ops += op(opId(), "card.created", "card", serverCard, wall++, obj("id" to JsonString(serverCard), "note_id" to JsonString(serverNote), "kind" to JsonString("meaning")))
        ops += op(opId(), "note.merged", "note", deviceNote, wall++, obj("loser_id" to JsonString(deviceNote), "survivor_id" to JsonString(serverNote)))
        var extra = 0
        for (c in deviceCards.sortedWith(compareBy({ str(it["kind"]) }, { str(it["id"]) }))) {
            val kind = str(c["kind"])
            val survivor = if (kind == "meaning") {
                serverCard
            } else {
                "0193b000-00aa-7000-8000-%012x".format(100 + ++extra).also { id ->
                    ops += op(opId(), "card.created", "card", id, wall++, obj("id" to JsonString(id), "note_id" to JsonString(serverNote), "kind" to JsonString(kind)))
                }
            }
            ops += op(opId(), "card.merged", "card", str(c["id"]), wall++, obj("loser_id" to c["id"]!!, "survivor_id" to JsonString(survivor)))
        }
        d.clock.now = wall
        d.pull(ops, "server materialization and merge")

        assertEquals(listOf(serverNote), d.liveNotesOf(entry).map { str(it["id"]) }, "one surviving note for the entry")
        val loser = d.notes().single { str(it["id"]) == deviceNote }
        assertEquals(JsonString(serverNote), loser["merged_into_id"])

        val after = d.queue(at = wall + 1_000).json()
        val servedNotes = after.items().map { str(it.at("/card/note_id")) }
        assertTrue(serverNote in servedNotes, "the survivor's card is served: $servedNotes")
        assertTrue(deviceNote !in servedNotes, "the loser's card is not served")
        assertEquals(2L, after.long("/counts/new"))
        assertEquals(0L, after.long("/to_materialize"))
        assertEquals(mintedBefore, d.minted().size, "the merged entry is not materialized again")
    }
}
