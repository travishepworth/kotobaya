package kotobaya.android.review

import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.TestServer
import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.store.PendingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** Kill the process after answering: the op is in the outbox and the cache is updated (SQLite file reopened). */
class ReviewDurabilityTest {
    private val device = "01931f3e-0000-7000-8000-0000000000a1"
    private val t0 = 1_760_000_000_000L

    private fun obj(vararg p: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*p))

    @Test
    fun anAnswerSurvivesAKillAndReopen() {
        val d = SqlDevice(device, TestServer(t0), t0)
        val noteId = "0193a1b2-0001-7000-8000-000000000001"
        val cardId = "0193a1b2-0002-7000-8000-000000000001"
        val reviewId = "0193a1b2-0003-7000-8000-000000000001"
        assertIs<MintResult.Minted>(
            d.stack.minter.mint(
                OpType.NOTE_CREATED,
                obj(
                    "id" to JsonString(noteId),
                    "note_type_id" to JsonString(Reserved.NOTE_TYPE_VOCAB),
                    "fields" to obj("expression" to JsonString("食べる"), "reading" to JsonString("たべる"), "meaning" to JsonString("to eat")),
                ),
            ),
        )
        assertIs<MintResult.Minted>(
            d.stack.minter.mint(OpType.CARD_CREATED, obj("id" to JsonString(cardId), "note_id" to JsonString(noteId), "kind" to JsonString("meaning"))),
        )
        d.clock.now += 5_000
        val s = ReviewSession(
            d.store, d.stack.minter,
            { ReviewQueue(listOf(QueueItem(cardId, QueueItemKind.NEW, null)), emptyList(), null) },
            { d.clock.now }, { 540 }, { reviewId }, { ReviewPrefs() },
        )
        s.start()
        val token = (s.state.value as ReviewState.Showing).card.token
        assertEquals(true, s.answer(token, Button.GOOD))
        d.restart() // the process is killed; only the file remains
        val rows = d.rows().filter { it.opType == "review.logged" }
        assertEquals(1, rows.size)
        assertEquals(PendingState.PENDING, rows.single().state)
        val card = d.store.read { it.learning.get(LearningTable.CARD, listOf(JsonString(cardId))) }!!
        assertNotEquals(JsonString("new"), card["state"])
        assertEquals(JsonString(reviewId), card["last_review_id"])
    }
}
