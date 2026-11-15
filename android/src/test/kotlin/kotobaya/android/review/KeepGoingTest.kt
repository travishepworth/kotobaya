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

    private fun session(
        rebuildEveryMs: Long = ReviewSession.REBUILD_EVERY_MS,
        source: QueueSource = LocalQueueLoader(d.db, { d.clock.now }).asQueueSource(),
    ): ReviewSession<SqlLearningTables> = ReviewSession(
        d.store, d.stack.minter, source, { d.clock.now }, { 540 },
        { "0193a1b2-0003-7000-8000-%012d".format(++reviewIds) }, { ReviewPrefs() },
        rebuildEveryMs = rebuildEveryMs,
        reads = d.reads,
    )

    /** Turns [ids] into review cards due an hour ago (due today). */
    private fun makeDueReviews(ids: List<String>) {
        val day = 86_400_000L
        for (id in ids) {
            d.driver.execute(
                null,
                "UPDATE card SET state = 'review', stability = 12.0, difficulty = 5.0, interval_days = 12, reps = 6, lapses = 0, " +
                    "last_review_at = ?, due_at = ? WHERE id = ?",
                3,
            ) {
                bindLong(0, d.clock.now - 13 * day)
                bindLong(1, d.clock.now - 3_600_000)
                bindString(2, id)
            }
        }
    }

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

    /**
     * AND-KEEPGOING-FIX acceptance: 300 reviews due (cap 200), 40 new (20/day, 10/session). The normal session ends
     * after the capped queue with Keep going offered; Keep going then serves every remaining due review and every new
     * card in one uninterrupted stream, through periodic "refresh" rebuilds and batch boundaries, and only then ends.
     */
    @Test
    fun keepGoingServesEveryDueReviewAndEveryNewCardWithoutStopping() {
        setting("scheduler.reviews_per_day", "200")
        setting("scheduler.new_per_day", "20")
        setting("scheduler.new_per_session", "10")
        val reviews = d.seed(300)
        makeDueReviews(reviews)
        val news = d.seed(40)
        val s = session(rebuildEveryMs = 20_000)
        s.start()

        // the normal (capped) session
        val normal = LinkedHashSet<String>()
        var guard = 0
        while (s.state.value is ReviewState.Showing) {
            val card = assertIs<ReviewState.Showing>(s.state.value).card
            d.clock.now += 2_000
            assertTrue(s.answer(card.token, Button.GOOD))
            normal += card.cardId
            check(++guard < 5_000) { "normal session did not end" }
        }
        assertIs<ReviewState.Summary>(s.state.value)
        assertTrue(s.meta.value.moreAvailable, "done for now, Keep going offered")
        assertFalse(s.meta.value.keepingGoing)
        assertEquals(200, normal.count { it in reviews }, "review cap")
        assertEquals(10, normal.count { it in news }, "session new cap")

        // keep going: one stream to the very end
        s.startKeepGoing()
        val kept = LinkedHashSet<String>()
        var answers = 0
        while (s.state.value is ReviewState.Showing) {
            val card = assertIs<ReviewState.Showing>(s.state.value).card
            d.clock.now += 2_000
            assertTrue(s.answer(card.token, Button.GOOD))
            kept += card.cardId
            answers++
            check(answers < 5_000) { "keep going did not end" }
        }
        assertEquals(reviews.toSet(), (normal + kept).filterTo(HashSet()) { it in reviews }, "every due review served")
        assertEquals(news.toSet(), (normal + kept).filterTo(HashSet()) { it in news }, "every new card served")
        assertEquals(100, kept.count { it in reviews && it !in normal })
        assertEquals(30, kept.count { it in news && it !in normal })
        assertTrue(answers >= 130, "answers while keeping going: $answers")
        assertIs<ReviewState.Summary>(s.state.value)
        assertTrue(s.meta.value.keepingGoing)
        assertFalse(s.meta.value.moreAvailable, "truly out: no button")

        // the end is real: a keep-going build has nothing left
        val left = LocalQueueLoader(d.db, { d.clock.now }).load(keepGoing = true).queue
        assertTrue(left.items.isEmpty() && left.toMaterialize.isEmpty(), "nothing left: ${left.items}")
    }

    /**
     * Defect 1: a keep-going build is never final (it holds one new batch). Here every keep-going build holds a single
     * new card (`keepGoingNewBatch` 1): the build made while the last card shows ("ahead") holds only that card, and
     * answering it must rebuild rather than end the session.
     */
    @Test
    fun aKeepGoingBuildThatSawEveryAnswerButOneIsNotFinal() {
        setting("scheduler.new_per_day", "1")
        val news = d.seed(5)
        val loader = LocalQueueLoader(d.db, { d.clock.now }).asQueueSource()
        val batchOfOne = object : QueueSource {
            override fun load(newShownThisSession: Int) = loader.load(newShownThisSession)

            override fun loadSession(newShownThisSession: Int, keepGoing: Boolean): SessionQueue {
                val built = loader.loadSession(newShownThisSession, keepGoing)
                if (!keepGoing) return built
                val firstNew = built.queue.items.firstOrNull { it.kind == QueueItemKind.NEW }
                val items = built.queue.items.filter { it.kind != QueueItemKind.NEW || it === firstNew }
                return built.copy(queue = built.queue.copy(items = items))
            }
        }
        val s = session(source = batchOfOne)
        s.start()
        val first = answerShown(s)
        assertIs<ReviewState.Summary>(s.state.value)
        assertTrue(s.meta.value.moreAvailable)

        s.startKeepGoing()
        val served = ArrayList<String>()
        while (s.state.value is ReviewState.Showing) {
            served += answerShown(s).cardId
            check(served.size < 100)
        }
        assertEquals(news.toSet(), (served + first.cardId).toSet(), "every new card served")
        assertIs<ReviewState.Summary>(s.state.value)
        assertFalse(s.meta.value.moreAvailable)
    }
}
