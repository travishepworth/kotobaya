package kotobaya.android.shell.api.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.QueueTestSupport.device
import kotobaya.android.shell.api.review.QueueTestSupport.items
import kotobaya.android.shell.api.review.QueueTestSupport.json
import kotobaya.android.shell.api.review.QueueTestSupport.long
import kotobaya.android.shell.api.review.QueueTestSupport.notes
import kotobaya.android.shell.api.review.QueueTestSupport.obj
import kotobaya.android.shell.api.review.QueueTestSupport.op
import kotobaya.android.shell.api.review.QueueTestSupport.queue
import kotobaya.android.shell.api.review.QueueTestSupport.str
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-040 on the device (contracts/core README "C-27", step A): the kanji notes a served build needs are minted as the
 * device (`origin` `auto_kanji`), once however often the queue is fetched, and a kanji note the user deleted is never
 * created again. Seed: `queue-kanji-first-deckless` (no kanji note; the words 学校 and 先生 need 学 校 先 生).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class QueueKanjiMintTest {
    private fun ParityDevice.kanjiNotesCreated(): List<JsonObject> =
        minted().filter { str(it.members["op_type"]) == "note.created" }.filter { str((it.members["payload"] as JsonObject).members["origin"]) == "auto_kanji" }

    private fun literals(ops: List<JsonObject>) = ops.map { str((it.members["payload"] as JsonObject).members["kanji_literal"]) }

    @Test
    fun theKanjiAreMintedOnceAsTheDeviceAndServedBeforeTheirWords() {
        val d = device("queue-kanji-first-deckless")
        val first = d.queue().json()
        assertEquals(listOf("先", "生", "学", "校"), literals(d.kanjiNotesCreated()).sortedBy { "先生学校".indexOf(it) })
        assertEquals(0L, first.long("/to_materialize"))
        d.kanjiNotesCreated().forEach { assertEquals(QueueTestSupport.DEVICE, str(it.members["device_id"])) }
        val before = d.minted().size
        val second = d.queue(at = d.clock.now + 1_000).json()
        assertEquals(before, d.minted().size, "the second fetch mints nothing")
        assertEquals(first.items().size, second.items().size)
    }

    @Test
    fun aKanjiNoteTheUserDeletedIsNeverCreatedAgain() {
        val d = device("queue-kanji-first-deckless")
        d.queue()
        val minted = d.kanjiNotesCreated().map { it.members["payload"] as JsonObject }
        assertTrue(minted.size == 4, "four kanji notes")
        val gone = minted.first { str(it.members["kanji_literal"]) == "校" }
        val goneId = str(gone.members["id"])
        // the user deletes the note of 校 on another replica; its pull arrives before the next fetch
        d.pull(listOf(op("0193c0de-0000-7000-8000-000000000001", "note.deleted", "note", goneId, d.clock.now + 500, obj("id" to JsonString(goneId)))), "user deletes 校")
        val before = d.kanjiNotesCreated().size
        d.queue(at = d.clock.now + 2_000)
        assertEquals(before, d.kanjiNotesCreated().size, "no kanji note is created for a literal whose note the user deleted")
        val after = d.notes().filter { (it["kanji_literal"] as? JsonString)?.value == "校" }
        assertEquals(1, after.size)
    }
}
