package kotobaya.android.review.actions

import androidx.test.core.app.ApplicationProvider
import kotobaya.android.review.AsyncReviewDevice
import kotobaya.android.review.ReviewState
import kotobaya.contracts.fsrs.Button
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A toolbar action on the card on screen (production store and queue loader) moves the session on to another card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ActionAdvanceTest {
    private val t0 = 1_760_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val d = AsyncReviewDevice.android(t0, context)
    private val actions = ReviewActions(d.store, d.stack.minter, { d.clock.now }, { d.clock.now + 86_400_000 })

    @After
    fun close() {
        d.close()
        context.deleteDatabase(kotobaya.android.db.APP_DB_FILE)
    }

    @Test
    fun suspendingTheShownCardShowsAnotherAndItNeverComesBack() {
        d.seed(4)
        val s = d.session()
        s.start()
        // answer one first, so an answer may still be pending when the action runs
        val first = assertIs<ReviewState.Showing>(s.state.value).card
        d.clock.now += 2_000
        assertTrue(s.answer(first.token, Button.GOOD))
        val shown = assertIs<ReviewState.Showing>(s.state.value).card
        assertIs<ActionResult.Done>(actions.suspend(shown.cardId))
        s.start()
        val next = assertIs<ReviewState.Showing>(s.state.value).card
        assertNotEquals(shown.cardId, next.cardId)

        val served = ArrayList<String>()
        while (s.state.value is ReviewState.Showing) {
            val c = assertIs<ReviewState.Showing>(s.state.value).card
            served += c.cardId
            d.clock.now += 2_000
            assertTrue(s.answer(c.token, Button.EASY))
            check(served.size < 50)
        }
        assertTrue(shown.cardId !in served, "suspended card came back: $served")
    }
}
