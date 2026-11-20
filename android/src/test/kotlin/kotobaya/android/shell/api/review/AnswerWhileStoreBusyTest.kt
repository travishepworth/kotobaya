package kotobaya.android.shell.api.review

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.ShellHeaders
import kotobaya.android.shell.api.review.AnswerTestSupport.C1
import kotobaya.android.shell.api.review.AnswerTestSupport.T0
import kotobaya.android.shell.api.review.AnswerTestSupport.answer
import kotobaya.android.shell.api.review.AnswerTestSupport.device
import kotobaya.android.shell.api.review.AnswerTestSupport.reviewId
import kotobaya.android.shell.api.review.AnswerTestSupport.reviews
import kotobaya.android.sync.SqlDelightDeviceStore

/**
 * LF-A1b acceptance (SHELL.md L-9, ADR-050): an `answerReview` submitted while a transaction longer than the 5 s write
 * bound holds the writer lock (Rebuild.reapply, StartupReplay, a large bulkCards) waits its bound, is retried once,
 * and then answers `503 shell_rebuilding` with `Retry-After: 2` — never `500` — having written nothing. The same
 * answer sent again once the lock is free is applied.
 */
class AnswerWhileStoreBusyTest {
    @Test
    fun anAnswerSubmittedWhileALongTransactionHoldsTheLockIsRetriedThenAnswers503WithRetryAfter() {
        val d = device()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "long-transaction") {
            d.store.transaction {
                held.countDown()
                release.await(60, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            val before = d.reviews().size
            val started = System.nanoTime()
            val busy = d.answer(reviewId(1), C1, rating = 3, at = T0 + 60_000)
            val waitedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(503, busy.status, busy.body)
            assertTrue(busy.body!!.contains("\"shell_rebuilding\""), busy.body)
            assertEquals("2", busy.headers[ShellHeaders.RETRY_AFTER])
            assertTrue(waitedMs >= 2 * SqlDelightDeviceStore.WRITE_WAIT_MS - 100, "retried once after the first bound: waited $waitedMs ms")
            assertEquals(before, d.reviews().size, "nothing written")
        } finally {
            release.countDown()
            holder.join()
        }
        val ok = d.answer(reviewId(1), C1, rating = 3)
        assertEquals(200, ok.status, ok.body)
    }
}
