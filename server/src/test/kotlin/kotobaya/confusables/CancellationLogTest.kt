package kotobaya.confusables

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotobaya.server.LogCapture
import kotobaya.sync.SyncFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OBS-1s2: a cancelled runner (shutdown) is not an ERROR with a stack trace; a real failure still is. */
class CancellationLogTest : SyncFixture() {
    private fun suggester(failure: () -> Throwable) = object : ConfusableSuggester(db, sync.opApply, intervalMs = 3_600_000L) {
        override fun runOnce(): Int = throw failure()
    }

    private fun runLoop(s: ConfusableSuggester) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = s.start(scope)
        delay(300)
        job.cancel()
        withTimeout(5_000) { job.join() }
    }

    @Test
    fun cancellationIsNotLoggedAtError() {
        LogCapture("kotobaya.confusables").use { cap ->
            runLoop(suggester { CancellationException("shutdown") })
            assertEquals(emptyList(), cap.events.filter { it.level.toString() == "ERROR" }.map { it.formattedMessage })
        }
    }

    @Test
    fun realFailureIsStillLoggedAtError() {
        LogCapture("kotobaya.confusables").use { cap ->
            runLoop(suggester { IllegalStateException("boom") })
            assertTrue(cap.events.any { it.level.toString() == "ERROR" })
        }
    }
}
