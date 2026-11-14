package kotobaya.android.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotobaya.android.demo.DemoData
import kotobaya.android.demo.DemoStore
import kotobaya.android.review.QueueSource
import kotobaya.android.review.ReviewBackend
import kotobaya.android.review.ReviewPrefs
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.android.review.ReviewState
import kotobaya.android.review.ReviewViewModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AND-REVIEW-STALE: an Empty Review session reloads when a sync completes; a card on screen is never restarted. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReviewStaleTest {
    private val assets get() = ApplicationProvider.getApplicationContext<Application>().assets

    private class Rig(assets: android.content.res.AssetManager) {
        val signal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var due = false
        var loads = 0
        val backend: ReviewBackend<*>

        init {
            val real = DemoStore(DemoData.load(assets)).reviewBackend()
            backend = ReviewBackend(
                store = real.store, minter = real.minter, prefs = { ReviewPrefs() }, dictionary = real.dictionary, kanji = real.kanji,
                clock = real.clock, nextStudyDayStart = { Long.MAX_VALUE / 4 }, dueChanged = signal,
                queue = QueueSource { n ->
                    loads++
                    if (due) real.queue.load(n) else ReviewQueue(emptyList(), emptyList(), null)
                },
            )
        }
    }

    @Test
    fun emptySessionBecomesNonEmptyAfterSyncCompleted() {
        val r = Rig(assets)
        val vm = ReviewViewModel(r.backend, io = Dispatchers.Unconfined)
        vm.enter()
        assertEquals(ReviewState.Empty, vm.state.value.review)
        r.due = true
        r.signal.tryEmit(Unit)
        assertIs<ReviewState.Showing>(vm.state.value.review)
        vm.leave()
    }

    @Test
    fun cardInProgressIsNotRestartedBySync() {
        val r = Rig(assets)
        r.due = true
        val vm = ReviewViewModel(r.backend, io = Dispatchers.Unconfined)
        vm.enter()
        val shown = assertIs<ReviewState.Showing>(vm.state.value.review)
        val loads = r.loads
        r.signal.tryEmit(Unit)
        vm.refreshIfEmpty()
        assertEquals(loads, r.loads)
        assertEquals(shown.card.token, assertIs<ReviewState.Showing>(vm.state.value.review).card.token)
        vm.leave()
    }
}
