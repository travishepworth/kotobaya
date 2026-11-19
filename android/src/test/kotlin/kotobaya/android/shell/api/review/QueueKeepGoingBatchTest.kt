package kotobaya.android.shell.api.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.android.shell.api.review.QueueTestSupport.device
import kotobaya.android.shell.api.review.QueueTestSupport.items
import kotobaya.android.shell.api.review.QueueTestSupport.json
import kotobaya.android.shell.api.review.QueueTestSupport.liveNotesOf
import kotobaya.android.shell.api.review.QueueTestSupport.long
import kotobaya.android.shell.api.review.QueueTestSupport.queue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L2b: keep going draws `limit` new cards per request (the server's `keepGoingNewBatch = limit`), creates no
 * more notes than that, and `counts` are the same with and without it (ADR-033). Seed `queue-materialize-count`: a deck
 * with three entries without notes, `new_per_day` 2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class QueueKeepGoingBatchTest {
    @Test
    fun keepGoingServesAndMaterializesUpToLimit() {
        val d = device("queue-materialize-count")
        assertEquals(3, d.queue("keep_going=true&limit=20").json().items().size)
    }

    @Test
    fun keepGoingWithSmallLimitCreatesOnlyLimitNotes() {
        val d = device("queue-materialize-count")
        assertEquals(1, d.queue("keep_going=true&limit=1").json().items().size)
        val notes = listOf(1001010L, 1001150L, 1001580L).sumOf { d.liveNotesOf(it).size }
        assertEquals(1, notes)
    }

    @Test
    fun countsAreTheSameWithAndWithoutKeepGoing() {
        val d = device("queue-materialize-count")
        val kg = d.queue("keep_going=true&limit=1").json()
        val normal = d.queue().json()
        for (k in listOf("new", "learning", "review")) assertEquals(normal.long("/counts/$k"), kg.long("/counts/$k"), k)
    }
}
