package kotobaya.android.shell.api.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.review.QueueTestSupport.device
import kotobaya.android.shell.api.review.QueueTestSupport.items
import kotobaya.android.shell.api.review.QueueTestSupport.json
import kotobaya.android.shell.api.review.QueueTestSupport.long
import kotobaya.android.shell.api.review.QueueTestSupport.queue
import kotobaya.android.shell.api.review.QueueTestSupport.str
import kotobaya.contracts.sync.JsonBool
import kotobaya.android.shell.api.review.QueueTestSupport.at
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LOOP-AND: `getReviewQueue` renders the core loop (ADR-037) on the SQLDelight replica, offline. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class QueueRouteTest {
    /**
     * A1: reviews unlimited, 3 learning cards in a step that has not elapsed, new cards in a deck. Nothing is ready,
     * yet the queue serves new cards (learning cards later today never make the loop wait), and `loop` says so.
     */
    @Test
    fun loopUnlimitedLaterLearningServesNew() {
        val d = device("queue-loop-unlimited-later-new")
        val q = d.queue("limit=10").json()
        assertEquals("reviewing", str(q.at("/loop/phase")))
        assertEquals(0L, q.long("/loop/ready/total"))
        assertEquals(3L, q.long("/loop/later_today/count"))
        assertEquals(10, q.items().size)
        assertTrue(q.items().all { (it.at("/is_new") as? JsonBool)?.value == true }, "every served card is new")
        assertEquals(true, (q.at("/loop/new/available") as? JsonBool)?.value)
    }
}
