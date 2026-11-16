package kotobaya.android.shell.api.review

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.identity.IdentityTestSupport.isValidOp
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.AnswerTestSupport.C1
import kotobaya.android.shell.api.review.AnswerTestSupport.C2
import kotobaya.android.shell.api.review.AnswerTestSupport.T0
import kotobaya.android.shell.api.review.AnswerTestSupport.X
import kotobaya.android.shell.api.review.AnswerTestSupport.Y
import kotobaya.android.shell.api.review.AnswerTestSupport.answer
import kotobaya.android.shell.api.review.AnswerTestSupport.card
import kotobaya.android.shell.api.review.AnswerTestSupport.cards
import kotobaya.android.shell.api.review.AnswerTestSupport.device
import kotobaya.android.shell.api.review.AnswerTestSupport.json
import kotobaya.android.shell.api.review.AnswerTestSupport.mintedTypes
import kotobaya.android.shell.api.review.AnswerTestSupport.problem
import kotobaya.android.shell.api.review.AnswerTestSupport.reviewId
import kotobaya.android.shell.api.review.AnswerTestSupport.reviews
import kotobaya.android.shell.api.review.AnswerTestSupport.undo
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString

/**
 * SHELL-L3 acceptance `AnswerIdempotencyTest` (SHELL.md L-4, 10.11; SYNC.md 7.8): a review answered in the WebView is
 * neither lost nor counted twice, however often and however concurrently the bridge request is repeated — after a
 * lost response, after the process died between the commit and the response, or with another body.
 */
class AnswerIdempotencyTest {
    private val r1 = reviewId(1)

    @Test
    fun nRetriesOfOneAnswerGiveOneOpOneRowAndTheCardOfASingleAnswer() {
        val once = device()
        assertEquals(200, once.answer(r1, C1, at = T0 + 1_000, extra = """"button":"good","duration_ms":4200,"utc_offset_min":540""").status)

        val d = device()
        val first = d.answer(r1, C1, at = T0 + 1_000, extra = """"button":"good","duration_ms":4200,"utc_offset_min":540""")
        assertEquals(200, first.status)
        repeat(6) { i ->
            val retry = d.answer(r1, C1, at = T0 + 2_000 + i * 60_000L, extra = """"button":"good","duration_ms":4200,"utc_offset_min":540""")
            assertEquals(200, retry.status)
            assertEquals(first.json(), retry.json(), "retry $i answers with the stored review and the same card")
        }
        assertEquals(listOf("review.logged"), d.mintedTypes())
        assertEquals(1, d.reviews().size)
        assertEquals(once.reviews(), d.reviews())
        assertEquals(once.cards(), d.cards(), "the card is the card after exactly one answer")
        assertEquals(JsonNumber(1.0), d.card(C1)["reps"])
        assertEquals(1, d.localChanges, "one sync trigger")
        assertEquals(1, d.dueChanges, "one due recompute")
    }

    @Test
    fun aRetryWithAnotherBodyOrAnotherCardStillAnswersWithTheStoredReview() {
        val d = device()
        val first = d.answer(r1, C1, rating = 3, at = T0 + 1_000).json()
        val other = d.answer(r1, C2, rating = 1, at = T0 + 2_000, extra = """"mode":"drill","button":"again"""")
        assertEquals(200, other.status)
        assertEquals(first, other.json())
        assertEquals(JsonString(C1), ParityJson.at(other.json(), "/review/card_id"))
        assertEquals(JsonString("new"), d.card(C2)["state"], "the other card is untouched")
        assertEquals(1, d.minted().size)
    }

    /** The response of the commit never reached the SPA (bridge torn down, process killed): only the replica knows. */
    @Test
    fun aRetryAfterTheReplicaAlreadyHoldsTheReviewMintsNothingEvenWithoutTheFirstResponse() {
        val d = device()
        // the first request commits; its response is dropped
        d.answer(r1, C1, at = T0 + 1_000)
        val cardAfterFirst = d.card(C1)
        val mintedAfterFirst = d.minted()
        // a later retry, hours after
        val retry = d.answer(r1, C1, at = T0 + 3 * AnswerTestSupport.HOUR)
        assertEquals(200, retry.status)
        assertEquals(mintedAfterFirst, d.minted())
        assertEquals(cardAfterFirst, d.card(C1))
        assertEquals(JsonString(kotobaya.android.shell.api.identity.timestamp(T0 + 1_000)), ParityJson.at(retry.json(), "/review/reviewed_at"))
    }

    @Test
    fun concurrentRetriesOfOneAnswerMintOneOp() {
        val d = device()
        d.clock.now = T0 + 1_000
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val results = (1..8).map { pool.submit<Int> { start.await(); d.answer(r1, C1).status } }
        start.countDown()
        val statuses = results.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(List(8) { 200 }, statuses)
        assertEquals(listOf("review.logged"), d.mintedTypes())
        assertEquals(1, d.reviews().size)
        assertEquals(JsonNumber(1.0), d.card(C1)["reps"])
    }

    @Test
    fun undoRetriesMintOneUndoAndARetriedAnswerOfAnUndoneReviewLogsNothing() {
        val d = device()
        val fresh = d.card(C1)
        d.answer(r1, C1, at = T0 + 1_000)
        val undone = d.undo(r1, at = T0 + 2_000)
        assertEquals(200, undone.status)
        repeat(4) { i ->
            val again = d.undo(r1, at = T0 + 3_000 + i * 1_000L)
            assertEquals(200, again.status)
            assertEquals(undone.json(), again.json())
        }
        assertEquals(listOf("review.logged", "review.undone"), d.mintedTypes())
        assertEquals(JsonNumber((T0 + 2_000).toDouble()), d.reviews().single()["undone_at"], "the first undo's time stays")
        assertEquals(fresh, d.card(C1), "the card is new again, exactly as before the answer")
        // the SPA retries the answer it thinks was lost: the stored (undone) review, no second review.logged
        val retry = d.answer(r1, C1, at = T0 + 10_000)
        assertEquals(200, retry.status)
        assertNotEquals(JsonNull, ParityJson.at(retry.json(), "/review/undone_at"))
        assertEquals(2, d.minted().size)
        assertEquals(fresh, d.card(C1))
        assertEquals(2, d.dueChanges, "the answer and the one undo")
    }

    @Test
    fun aRefusedRequestMintsNothingAndChangesNothing() {
        val d = device()
        val before = d.cards()
        assertEquals("422 /id required", d.request("POST", "/api/v1/review/answer", "", ParityJson.parse("""{"card_id":"$C1","rating":3}""")).problem())
        assertEquals("422 /button button_rating_mismatch", d.answer(r1, C1, rating = 3, extra = """"button":"again"""").problem())
        assertEquals("422 /nope unknown_field", d.answer(r1, C1, extra = """"nope":1""").problem())
        assertEquals(
            "422 /rating invalid_type",
            d.request("POST", "/api/v1/review/answer", "", ParityJson.parse("""{"id":"$r1","card_id":"$C1","rating":2.5}""")).problem(),
        )
        assertEquals("404 not_found", d.answer(r1, "0193b000-00ee-7000-8000-00000000dead").problem())
        assertEquals("404 not_found", d.undo(r1).problem())
        assertEquals("422 /review_id required", d.request("POST", "/api/v1/review/undo", "", JsonObject(emptyMap())).problem())
        assertEquals(0, d.minted().size)
        assertEquals(0, d.reviews().size)
        assertEquals(before, d.cards())
        assertEquals(0, d.localChanges)
        assertEquals(0, d.dueChanges)
    }

    /** SYNC.md 7.8 on the receiving side: the device's ops delivered twice, and to a replica that already has them. */
    @Test
    fun theMintedOpsAppliedTwiceOnAnotherReplicaChangeNothingTheSecondTime() {
        val d = device()
        d.answer(r1, C1, at = T0 + 1_000)
        d.answer(reviewId(2), C1, rating = 1, at = T0 + 700_000)
        d.undo(reviewId(2), at = T0 + 701_000)
        d.answer(reviewId(3), C2, rating = 4, at = T0 + 702_000, extra = """"mode":"cram"""")
        val ops = d.minted()
        assertEquals(4, ops.size)
        assertTrue(ops.all(::isValidOp))

        val y = device(Y)
        y.clock.now = T0 + 800_000
        y.pull(ops, "first delivery")
        val reviews = y.reviews()
        val cards = y.cards()
        assertEquals(d.reviews(), reviews)
        assertEquals(d.cards(), cards)
        y.pull(ops, "second delivery")
        y.pull(ops.reversed(), "third delivery, reversed")
        assertEquals(reviews, y.reviews())
        assertEquals(cards, y.cards())
        // and back onto the device that minted them (a pull returns a device its own ops)
        d.pull(ops, "own ops come back")
        assertEquals(reviews, d.reviews())
        assertEquals(cards, d.cards())
        assertEquals(X, ParityJson.str(ops[0].members["device_id"]))
    }
}
