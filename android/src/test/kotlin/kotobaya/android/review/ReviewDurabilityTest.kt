package kotobaya.android.review

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
import kotlin.test.assertTrue

/**
 * Kill the process after answering: the op is in the outbox and the cache is updated (SQLite file reopened).
 * Robolectric: the async-commit case runs on the production driver (DurableDriver, WAL + FULL).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReviewDurabilityTest {
    init {
        Class.forName("org.sqlite.JDBC") // the JVM driver beside Robolectric's sandbox (SqlDevice)
    }

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After
    fun tearDown() {
        context.deleteDatabase(kotobaya.android.db.APP_DB_FILE)
    }

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

    /**
     * AND-REVIEW-SNAPPY: the next card is on screen while the answer is still being written. A kill in that window
     * leaves every earlier answer committed and nothing of the one in flight (no half row, no cache change); after
     * the restart that card is due again and answering it writes exactly one review.
     */
    @Test
    fun aKillBetweenTheTapAndTheCommitLosesNothingCommittedAndDuplicatesNothing() {
        val d = AsyncReviewDevice.android(t0, context)
        d.seed(3)
        val s = d.session()
        s.start()
        val a = awaitShowing(s)
        d.clock.now += 3_000
        assertTrue(s.answer(a.token, Button.GOOD))
        val b = awaitShowing(s, a.token)
        d.clock.now += 3_000
        d.gate.holdNext()
        val tap = background { s.answer(b.token, Button.GOOD) }
        d.gate.awaitHeld()
        // b's review is inside its transaction, not committed: the screen already shows the next card
        val c = assertIs<ReviewState.Showing>(s.state.value).card
        assertTrue(c.cardId != a.cardId && c.cardId != b.cardId, "the third card is shown while b is being written")
        assertEquals(listOf(a.cardId), d.ops("review.logged").map { d.str(it, "card_id") })
        val killed = d.snapshot()
        d.gate.release()
        assertTrue(tap.get(10, java.util.concurrent.TimeUnit.SECONDS))

        // the process that lived: both answers, in tap order
        assertEquals(listOf(a.cardId, b.cardId), d.ops("review.logged").map { d.str(it, "card_id") })
        d.close()

        // the process that was killed in the window
        val after = AsyncReviewDevice.android(t0 + 10_000, context, from = killed)
        val logged = after.ops("review.logged")
        assertEquals(listOf(a.cardId), logged.map { after.str(it, "card_id") }, "a survives, b was never written")
        val bCard = after.store.read { it.learning.get(LearningTable.CARD, listOf(JsonString(b.cardId))) }!!
        assertEquals(JsonString("new"), bCard["state"], "no cache change without its op")
        val reviews = after.store.read { tx -> tx.learning.all(LearningTable.REVIEW_LOG) }
        assertEquals(1, reviews.size, "no half-written review row")
        // b is due again after the restart; answering it once writes it once
        val again = after.session()
        again.start()
        var shown = awaitShowing(again)
        while (shown.cardId != b.cardId) {
            assertTrue(again.answer(shown.token, Button.EASY))
            shown = awaitShowing(again, shown.token)
        }
        assertTrue(again.answer(shown.token, Button.GOOD))
        assertEquals(1, after.ops("review.logged").count { after.str(it, "card_id") == b.cardId })
        after.close()
    }
}
