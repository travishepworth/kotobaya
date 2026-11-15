package kotobaya.android.review

import androidx.test.core.app.ApplicationProvider
import kotobaya.contracts.fsrs.Button
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AND-BACK (ADR-030): back after an answer undoes it once (the Undo path: one review.undone op); the next back leaves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReviewBackTest {
    private val t0 = 1_760_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val d = AsyncReviewDevice.android(t0, context)
    private var now = t0

    @After
    fun close() {
        d.close()
        context.deleteDatabase(kotobaya.android.db.APP_DB_FILE)
    }

    /** What ReviewViewModel.backUndo does with the session; true = consumed (undone), false = the screen is left. */
    private fun back(s: ReviewSession<*>, policy: ReviewBackPolicy): Boolean {
        if (!policy.shouldUndo(s.state.value is ReviewState.Showing, s.meta.value.canUndo)) return false
        return s.undo()
    }

    @Test
    fun backAfterAnAnswerUndoesExactlyOnceAndTheSecondBackLeaves() {
        d.seed(3)
        val s = d.session()
        s.start()
        val policy = ReviewBackPolicy(now = { now })
        val first = assertIs<ReviewState.Showing>(s.state.value).card
        d.clock.now += 1_000
        assertTrue(s.answer(first.token, Button.GOOD))
        val second = assertIs<ReviewState.Showing>(s.state.value).card
        d.clock.now += 1_000
        assertTrue(s.answer(second.token, Button.GOOD))
        assertEquals(2, s.meta.value.answered)

        assertTrue(back(s, policy), "first back undoes")
        assertEquals(1, d.ops("review.undone").size)
        assertEquals(second.cardId, assertIs<ReviewState.Showing>(s.state.value).card.cardId)
        now += 1_000
        assertFalse(back(s, policy), "second back within 3 s leaves, though an answer remains")
        assertEquals(1, d.ops("review.undone").size, "no second undo")
        now += 5_000
        assertTrue(back(s, policy), "after the window, back undoes again")
        assertEquals(2, d.ops("review.undone").size)
    }

    @Test
    fun backWithNothingToUndoLeavesAndANewAnswerStartsANewBurst() {
        d.seed(3)
        val s = d.session()
        s.start()
        val policy = ReviewBackPolicy(now = { now })
        assertFalse(back(s, policy), "nothing answered: leave")
        val c = assertIs<ReviewState.Showing>(s.state.value).card
        d.clock.now += 1_000
        s.answer(c.token, Button.GOOD)
        assertTrue(back(s, policy))
        val c2 = assertIs<ReviewState.Showing>(s.state.value).card
        d.clock.now += 1_000
        s.answer(c2.token, Button.GOOD)
        policy.answered() // what the view model does on every answer
        now += 500
        assertTrue(back(s, policy), "an answer after an undo re-arms back")
        assertEquals(2, d.ops("review.undone").size)
    }
}
