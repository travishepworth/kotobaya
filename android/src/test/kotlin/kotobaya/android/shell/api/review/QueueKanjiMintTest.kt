package kotobaya.android.shell.api.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.QueueTestSupport.at
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
        // kanji first: 先 生 先生 学 校 学校 (the words have no kanji_literal, the kanji notes do)
        assertEquals(listOf("先", "生", null, "学", "校", null), first.items().map { (it.at("/card/note/kanji_literal") as? JsonString)?.value })
    }

    @Test
    fun aKanjiNoteMintedByTheServerForTheSameLiteralConvergesToOneLiveNote() {
        val d = device("queue-kanji-first-deckless")
        d.queue()
        val mineId = str(d.kanjiNotesCreated().map { it.members["payload"] as JsonObject }.first { str(it.members["kanji_literal"]) == "学" }.members["id"])
        val myCards = d.cardsOf(mineId)
        assertTrue(myCards.isNotEmpty())
        // the server minted 学 first; its log for this device holds its note and cards, then the merge of the device's (SYNC.md 8.1)
        val serverNote = "0193c0de-0000-7000-8000-0000000000aa"
        var wall = d.clock.now + 60_000
        var n = 0
        fun opId() = "0193c0de-0000-7000-8000-%012x".format(0xb0 + ++n)
        val ops = ArrayList<JsonObject>()
        ops += op(
            opId(), "note.created", "note", serverNote, wall++,
            obj("id" to JsonString(serverNote), "note_type_id" to JsonString("00000000-0000-7000-8000-000000000102"), "kanji_literal" to JsonString("学"), "origin" to JsonString("auto_kanji")),
        )
        ops += op(opId(), "note.merged", "note", mineId, wall++, obj("loser_id" to JsonString(mineId), "survivor_id" to JsonString(serverNote)))
        myCards.forEachIndexed { i, c ->
            val survivor = "0193c0de-0000-7000-8000-%012x".format(0xa0 + i)
            ops += op(opId(), "card.created", "card", survivor, wall++, obj("id" to JsonString(survivor), "note_id" to JsonString(serverNote), "kind" to c["kind"]!!))
            ops += op(opId(), "card.merged", "card", str(c["id"]), wall++, obj("loser_id" to c["id"]!!, "survivor_id" to JsonString(survivor)))
        }
        d.clock.now = wall
        d.pull(ops, "server mints 学 first")
        val before = d.kanjiNotesCreated().size
        d.queue(at = wall + 1_000)
        val nullish = { v: kotobaya.contracts.sync.JsonValue? -> v == null || v == kotobaya.contracts.sync.JsonNull }
        val live = d.notes().filter { (it["kanji_literal"] as? JsonString)?.value == "学" && nullish(it["deleted_at"]) && nullish(it["merged_into_id"]) }
        assertEquals(listOf(serverNote), live.map { str(it["id"]) }, "one surviving note for 学")
        assertEquals(before, d.kanjiNotesCreated().size, "the converged kanji is not minted again")
    }

    /** SRV-28 `aSuspendedDueStartedReviewCardIsNeitherServedNorCounted`: the builder alone drops a started card that is suspended. */
    @Test
    fun aSuspendedDueStartedReviewCardIsNeitherServedNorCounted() {
        val d = device("queue-reviews-unlimited")
        assertEquals(200, d.request("PATCH", "/api/v1/settings", "", kotobaya.android.shell.parity.ParityJson.parse("""{"values":{"scheduler.reviews_unlimited":true}}""")).status)
        val all = d.queue(at = d.clock.now + 1_000).json()
        val ids = all.items().map { str(it.at("/card/id")) }
        assertEquals(3, ids.size)
        val bulk = """{"card_ids":["${ids[0]}"],"action":{"type":"suspend"}}"""
        assertEquals(200, d.request("POST", "/api/v1/cards/bulk", "", kotobaya.android.shell.parity.ParityJson.parse(bulk)).status)
        val q = d.queue(at = d.clock.now + 1_000).json()
        assertEquals(ids.drop(1).toSet(), q.items().map { str(it.at("/card/id")) }.toSet())
        assertEquals(2L, q.long("/counts/review"))
        assertEquals(2L, q.long("/loop/ready/review"))
        assertEquals(2L, q.long("/loop/until_stop"))
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
