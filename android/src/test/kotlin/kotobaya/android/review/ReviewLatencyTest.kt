package kotobaya.android.review

import kotobaya.android.queue.RecordingDriver
import kotobaya.android.queue.SyntheticReviews
import kotobaya.contracts.fsrs.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AND-REVIEW-SNAPPY bench: a collection the size of the phone that reported the lag (9.5k cards, 80k review_log
 * rows; no personal data), on a SQLite file with the production pragmas. Answer to next card state stays under
 * 20 ms on the JVM, and no query whose cost grows with review_log (the queue build, todayReviews) runs per answer.
 */
class ReviewLatencyTest {
    private val now = 1_760_000_000_000L
    private var recording: RecordingDriver? = null
    private val d = AsyncReviewDevice.jdbc(now, wrap = { raw -> RecordingDriver(raw).also { recording = it } })
    private val driver get() = checkNotNull(recording)

    @AfterTest
    fun close() = d.close()

    private fun queryCount(marker: String): Int = synchronized(driver.queries) { driver.queries.count { marker in it.first } }

    @Test
    fun answerToNextCardIsUnder20MsWithAPhoneSizedCollectionAndRunsNoQueueBuild() {
        val day = 86_400_000L
        SyntheticReviews(cards = 9_500, historyRows = 80_000, todayRows = 20, dayStart = now - 3_600_000, now = now).insert(d.db, d.driver)
        // a review backlog (due yesterday) besides the new cards, as on the phone
        d.driver.execute(
            null,
            "UPDATE card SET state = 'review', stability = 12.0, difficulty = 5.0, interval_days = 12, reps = 6, lapses = 0, " +
                "last_review_at = ?, due_at = ? WHERE merged_into_id IS NULL AND rowid % 3 = 0",
            2,
        ) {
            bindLong(0, now - 13 * day)
            bindLong(1, now - day)
        }

        val events = ArrayList<ReviewTiming>()
        val s = d.session { t -> synchronized(events) { events += t } }
        s.start()
        val built = synchronized(events) { events.filterIsInstance<ReviewTiming.Rebuild>().single() }
        val answers = 40
        assertTrue(built.items > answers, "the queue holds more than the answers: ${built.items}")
        val buildsAfterStart = queryCount("AS introduced_new")
        val candidatesAfterStart = queryCount("c.last_review_id IS NOT")
        assertEquals(1, buildsAfterStart, "start builds the queue once")

        var shown = awaitShowing(s)
        val kinds = ArrayList<String>()
        repeat(answers) {
            d.clock.now += 4_000
            kinds += shown.kind.name
            assertTrue(s.answer(shown.token, if (it % 5 == 0) Button.AGAIN else Button.GOOD))
            shown = awaitShowing(s, shown.token)
        }
        val timings = synchronized(events) { events.filterIsInstance<ReviewTiming.Answer>() }
        assertEquals(answers, timings.size)
        val advance = timings.map { it.advanceMs }.sorted()
        println("AND-REVIEW-SNAPPY advance ms: median=${advance[advance.size / 2]} max=${advance.last()}; kinds=${kinds.groupingBy { it }.eachCount()}; " + timings.last().line())
        assertTrue(advance.last() < 20.0, "answer -> next card state under 20 ms: $advance")
        assertTrue(timings.count { it.prefetchHit } >= answers - 2, "the next card is pre-rendered: ${timings.map { it.prefetchHit }}")
        assertEquals(buildsAfterStart, queryCount("AS introduced_new"), "no todayReviews per answer")
        assertEquals(candidatesAfterStart, queryCount("c.last_review_id IS NOT"), "no queue build per answer")
    }
}
