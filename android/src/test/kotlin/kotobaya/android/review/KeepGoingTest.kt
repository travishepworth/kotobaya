package kotobaya.android.review

import androidx.test.core.app.ApplicationProvider
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.queue.QueueItemKind
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** C-26 on a device: the finished page offers Keep going iff the keep-going queue is non-empty (production loader). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class KeepGoingTest {
    private val t0 = 1_760_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val d = AsyncReviewDevice.android(t0, context)
    private var reviewIds = 0

    @After
    fun close() {
        d.close()
        context.deleteDatabase(kotobaya.android.db.APP_DB_FILE)
    }

    private fun setting(key: String, value: String) {
        d.driver.execute(null, "INSERT OR REPLACE INTO settings (key, value, hlc, updated_at) VALUES ('$key', '$value', 'h', 0)", 0)
    }

    private fun session(): ReviewSession<SqlLearningTables> = ReviewSession(
        d.store, d.stack.minter, LocalQueueLoader(d.db, { d.clock.now }).asQueueSource(), { d.clock.now }, { 540 },
        { "0193a1b2-0003-7000-8000-%012d".format(++reviewIds) }, { ReviewPrefs() },
        reads = d.reads,
    )

    private fun answerShown(s: ReviewSession<*>): ShownCard {
        val card = assertIs<ReviewState.Showing>(s.state.value).card
        d.clock.now += 2_000
        assertTrue(s.answer(card.token, Button.EASY))
        return card
    }

    @Test
    fun finishedOffersKeepGoingOnlyWhenTheKeepGoingQueueHasCards() {
        // nothing at all: done, no button
        val none = session()
        none.start()
        assertEquals(ReviewState.Empty, none.state.value)
        assertFalse(none.meta.value.moreAvailable)

        // 3 new cards, 1 allowed per day: the daily limit leaves the others for keep going
        setting("scheduler.new_per_day", "1")
        d.seed(3)
        val s = session()
        s.start()
        val first = answerShown(s)
        assertEquals(QueueItemKind.NEW, first.kind)
        assertIs<ReviewState.Summary>(s.state.value)
        assertTrue(s.meta.value.moreAvailable, "done for now, Keep going offered")
        assertFalse(s.meta.value.keepingGoing)

        // keep going serves new cards past the daily limit, refilling until the pool is empty
        s.startKeepGoing()
        val served = ArrayList<String>()
        while (s.state.value is ReviewState.Showing) served += answerShown(s).cardId
        assertEquals(2, served.size)
        assertTrue(first.cardId !in served)
        assertTrue(s.meta.value.keepingGoing)
        assertFalse(s.meta.value.moreAvailable, "truly out: no button")

        // a new visit (session) starts in normal mode
        val again = session()
        again.start()
        assertFalse(again.meta.value.keepingGoing)
    }
}
