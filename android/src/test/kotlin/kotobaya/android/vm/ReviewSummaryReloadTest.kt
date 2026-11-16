package kotobaya.android.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotobaya.android.demo.DemoData
import kotobaya.android.demo.DemoStore
import kotobaya.android.review.QueueSource
import kotobaya.android.review.ReviewBackend
import kotobaya.android.review.ReviewPrefs
import kotobaya.android.review.ReviewState
import kotobaya.android.review.ReviewViewModel
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.queue.ReviewQueue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RC-AND finding 2: a finished session (Summary) reloads like Empty / Waiting do, so cards that fall due later (a sync,
 * the app coming back, the study day rolling over) show up instead of "session over" while Today counts them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReviewSummaryReloadTest {
    private val assets get() = ApplicationProvider.getApplicationContext<Application>().assets

    private inner class Rig(nextDay: (Long) -> Long = { Long.MAX_VALUE / 4 }) {
        val signal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        @Volatile var due = false
        private var loads = 0
        val backend: ReviewBackend<*>

        init {
            val real = DemoStore(DemoData.load(assets)).reviewBackend()
            backend = ReviewBackend(
                store = real.store, minter = real.minter, prefs = { ReviewPrefs() }, dictionary = real.dictionary, kanji = real.kanji,
                clock = System::currentTimeMillis, nextStudyDayStart = nextDay, dueChanged = signal,
                queue = QueueSource { n ->
                    loads++
                    val q = real.queue.load(n)
                    when {
                        due -> q
                        loads == 1 -> ReviewQueue(q.items.take(1), emptyList(), null) // one card, then nothing
                        else -> ReviewQueue(emptyList(), emptyList(), null)
                    }
                },
            )
        }
    }

    /** Enters and answers the one card: the session ends in its summary. */
    private fun finished(r: Rig): ReviewViewModel {
        val vm = ReviewViewModel(r.backend, io = Dispatchers.Unconfined)
        vm.enter()
        val card = assertIs<ReviewState.Showing>(vm.state.value.review).card
        vm.flip()
        vm.answer(card.token, Button.EASY)
        assertIs<ReviewState.Summary>(vm.state.value.review)
        return vm
    }

    @Test
    fun summaryReloadsWhenASyncChangedTheDueState() {
        val r = Rig()
        val vm = finished(r)
        r.due = true
        r.signal.tryEmit(Unit)
        assertIs<ReviewState.Showing>(vm.state.value.review)
        vm.leave()
    }

    @Test
    fun summaryReloadsOnResume() {
        val r = Rig()
        val vm = finished(r)
        vm.refreshIfEmpty() // nothing new yet: still the summary
        assertIs<ReviewState.Summary>(vm.state.value.review)
        r.due = true
        vm.refreshIfEmpty()
        assertIs<ReviewState.Showing>(vm.state.value.review)
        vm.leave()
    }

    @Test
    fun summaryReloadsAtTheDayRollover() {
        val rollAt = System.currentTimeMillis() + 5_000 // after the summary is reached
        val r = Rig(nextDay = { rollAt })
        val vm = finished(r)
        r.due = true // cards of the new day; nothing tells the screen but its own wake-up
        assertIs<ReviewState.Summary>(vm.state.value.review)
        val until = System.currentTimeMillis() + 20_000
        while (vm.state.value.review !is ReviewState.Showing && System.currentTimeMillis() < until) Thread.sleep(20)
        assertIs<ReviewState.Showing>(vm.state.value.review, "reloaded by itself at the new study day")
        vm.leave()
    }
}
