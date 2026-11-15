package kotobaya.android.review

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.render.CardSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AND-REVIEW-SNAPPY: the screen moves on at the tap, the answer is written afterwards on the tapping thread. Every
 * answer is written exactly once, in tap order, under rapid taps, double taps, undo and a failing write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReviewAsyncCommitTest {
    private val t0 = 1_760_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val d = AsyncReviewDevice.android(t0, context)

    @After
    fun close() {
        d.close()
        context.deleteDatabase(kotobaya.android.db.APP_DB_FILE)
    }

    private fun loggedCards() = d.ops("review.logged").map { d.str(it, "card_id") }

    @Test
    fun rapidAnswersAreWrittenExactlyOnceInTapOrderWhileTheFirstIsStillBeingWritten() {
        d.seed(6)
        val s = d.session()
        s.start()
        d.gate.holdNext()
        val tapped = ArrayList<String>()
        val calls = ArrayList<java.util.concurrent.Future<Boolean>>()
        var shown = awaitShowing(s)
        repeat(5) { i ->
            d.clock.now += 1_000
            tapped += shown.cardId
            val token = shown.token
            // a double tap: the same token twice, from two threads at once
            calls += background { s.answer(token, Button.GOOD) }
            calls += background { s.answer(token, Button.EASY) }
            if (i == 0) d.gate.awaitHeld() // the first answer is inside its transaction for the rest of the taps
            if (i < 4) shown = awaitShowing(s, token)
        }
        // the screen moved through all five cards while none of them was committed
        assertEquals(emptyList(), loggedCards())
        d.gate.release()
        val results = calls.map { it.get(10, TimeUnit.SECONDS) }
        assertEquals(5, results.count { it }, "one accepted answer per card: $results")
        assertEquals(tapped, loggedCards(), "written once each, in tap order")
        assertEquals(5, s.meta.value.answered)

        // undo takes back the last answers first
        val ids = d.ops("review.logged").map { d.str(it, "id") }
        assertTrue(s.undo())
        assertTrue(s.undo())
        assertEquals(listOf(ids[4], ids[3]), d.ops("review.undone").map { d.str(it, "review_id") })
        assertEquals(tapped[3], assertIs<ReviewState.Showing>(s.state.value).card.cardId)
        assertEquals(3, s.meta.value.answered)
    }

    @Test
    fun undoWhileAnEarlierAnswerIsBeingWrittenUndoesTheLastAnswerAfterBothAreWritten() {
        d.seed(3)
        val s = d.session()
        s.start()
        val a = awaitShowing(s)
        d.gate.holdNext()
        val first = background { s.answer(a.token, Button.GOOD) }
        d.gate.awaitHeld()
        val b = awaitShowing(s, a.token)
        val second = background { s.answer(b.token, Button.HARD) }
        awaitShowing(s, b.token)
        val undo = background { s.undo() }
        d.gate.release()
        assertTrue(first.get(10, TimeUnit.SECONDS))
        assertTrue(second.get(10, TimeUnit.SECONDS))
        assertTrue(undo.get(10, TimeUnit.SECONDS))
        assertEquals(listOf(a.cardId, b.cardId), loggedCards())
        val bReview = d.ops("review.logged").map { d.str(it, "id") }.last()
        assertEquals(listOf(bReview), d.ops("review.undone").map { d.str(it, "review_id") })
        assertEquals(b.cardId, assertIs<ReviewState.Showing>(s.state.value).card.cardId, "the undone card is shown again")
        assertEquals(1, s.meta.value.answered)
    }

    @Test
    fun aFailedWriteRollsTheScreenBackAndWithdrawsTheAnswersTappedAfterIt() {
        d.seed(3)
        val s = d.session()
        s.start()
        val a = awaitShowing(s)
        s.flip()
        d.gate.holdNext()
        d.gate.failHeld = true
        val first = background { s.answer(a.token, Button.GOOD) }
        d.gate.awaitHeld()
        val b = awaitShowing(s, a.token)
        val second = background { s.answer(b.token, Button.GOOD) }
        awaitShowing(s, b.token)
        d.gate.release()
        val e = assertIs<ExecutionException>(runCatching { first.get(10, TimeUnit.SECONDS) }.exceptionOrNull())
        assertIs<ReviewCommitFailed>(e.cause)
        assertFalse(second.get(10, TimeUnit.SECONDS), "tapped after the failed one: withdrawn")
        assertEquals(emptyList(), loggedCards(), "nothing written")
        // the screen is back on a, as it was when answered, and a can be answered again
        val back = assertIs<ReviewState.Showing>(s.state.value)
        assertEquals(a.cardId, back.card.cardId)
        assertEquals(CardSide.BACK, back.side)
        assertEquals(0, s.meta.value.answered)
        assertTrue(s.answer(back.card.token, Button.GOOD))
        val next = awaitShowing(s, back.card.token)
        assertEquals(b.cardId, next.cardId, "the withdrawn answer's card comes next, in the old order")
        assertTrue(s.answer(next.token, Button.GOOD))
        assertEquals(listOf(a.cardId, b.cardId), loggedCards())
    }

    @Test
    fun theTimingLineCoversEveryPhase() {
        d.seed(2)
        val lines = ArrayList<ReviewTiming>()
        val s = d.session { synchronized(lines) { lines += it } }
        s.start()
        val a = awaitShowing(s)
        s.flip()
        assertTrue(s.answer(a.token, Button.GOOD))
        val answer = synchronized(lines) { lines.filterIsInstance<ReviewTiming.Answer>().single() }
        assertEquals("committed", answer.outcome)
        assertTrue(answer.prefetchHit, "the second card was pre-rendered while the first was showing")
        val text = answer.line()
        for (phase in listOf("advance=", "select=", "render=", "commit=", "requeue=", "prefetch=")) assertTrue(phase in text, text)
        assertTrue(synchronized(lines) { lines.any { it is ReviewTiming.Reveal } })
        assertTrue(synchronized(lines) { lines.any { it is ReviewTiming.Rebuild && it.why == "start" } })
    }
}
