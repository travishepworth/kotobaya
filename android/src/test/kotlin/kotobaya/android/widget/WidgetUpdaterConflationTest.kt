package kotobaya.android.widget

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AND-PERF: a burst of due triggers refreshes the widget one at a time, and collapses into one more refresh. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WidgetUpdaterConflationTest {
    @Test
    fun aBurstOfTriggersRunsAtMostOneRefreshAtATime() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val refreshes = AtomicInteger()
        val firstRunning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val updater = WidgetUpdater(
            ApplicationProvider.getApplicationContext(),
            scope,
            refresh = {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
                firstRunning.countDown()
                release.await(5, TimeUnit.SECONDS)
                inFlight.decrementAndGet()
                refreshes.incrementAndGet()
                done.countDown()
            },
            refreshTile = {},
        )
        updater.onDueChanged()
        assertTrue(firstRunning.await(5, TimeUnit.SECONDS))
        repeat(20) { updater.onDueChanged() }
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        scope.cancel()
        assertEquals(1, maxInFlight.get())
        assertEquals(2, refreshes.get())
    }
}
