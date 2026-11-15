package kotobaya.android.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotobaya.android.demo.DemoData
import kotobaya.android.demo.DemoStore
import kotobaya.android.review.QueueSource
import kotobaya.android.review.ReviewBackend
import kotobaya.android.review.ReviewPrefs
import kotobaya.android.review.ReviewState
import kotobaya.android.review.ReviewViewModel
import kotobaya.contracts.queue.ReviewQueue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ADR-033: the all-decks session waits for its next learning card and resumes by itself at that instant, or on resume. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReviewWaitingTest {
    private val assets get() = ApplicationProvider.getApplicationContext<Application>().assets

    /** Nothing due until [dueAt] (only a learning card waiting), then the demo queue. */
    private fun backend(dueAt: Long, due: () -> Boolean): ReviewBackend<*> {
        val real = DemoStore(DemoData.load(assets)).reviewBackend()
        return ReviewBackend(
            store = real.store, minter = real.minter, prefs = { ReviewPrefs() }, dictionary = real.dictionary, kanji = real.kanji,
            clock = System::currentTimeMillis, nextStudyDayStart = { Long.MAX_VALUE / 4 },
            queue = QueueSource { n -> if (due()) real.queue.load(n) else ReviewQueue(emptyList(), emptyList(), dueAt) },
        )
    }

    @Test
    fun theWaitingSessionShowsTheCardAtItsDueInstant() {
        val dueAt = System.currentTimeMillis() + 400
        val vm = ReviewViewModel(backend(dueAt) { System.currentTimeMillis() >= dueAt }, io = Dispatchers.Unconfined)
        vm.enter()
        assertEquals(dueAt, assertIs<ReviewState.Waiting>(vm.state.value.review).nextDueAt)
        val until = System.currentTimeMillis() + 10_000
        while (vm.state.value.review !is ReviewState.Showing && System.currentTimeMillis() < until) Thread.sleep(20)
        assertIs<ReviewState.Showing>(vm.state.value.review, "resumed by itself")
        vm.leave()
    }

    @Test
    fun theWaitingSessionResumesWhenTheAppComesBack() {
        var due = false
        val vm = ReviewViewModel(backend(System.currentTimeMillis() + 3_600_000) { due }, io = Dispatchers.Unconfined)
        vm.enter()
        assertIs<ReviewState.Waiting>(vm.state.value.review)
        vm.refreshIfEmpty() // ON_RESUME before the due time: still waiting
        assertIs<ReviewState.Waiting>(vm.state.value.review)
        due = true // the app was in the background past the due instant
        vm.refreshIfEmpty()
        assertIs<ReviewState.Showing>(vm.state.value.review)
        vm.leave()
    }
}
