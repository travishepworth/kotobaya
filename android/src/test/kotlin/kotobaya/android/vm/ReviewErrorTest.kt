package kotobaya.android.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.Dispatchers
import kotobaya.android.R
import kotobaya.android.demo.DemoData
import kotobaya.android.demo.DemoStore
import kotobaya.android.review.QueueSource
import kotobaya.android.review.ReviewBackend
import kotobaya.android.review.ReviewPrefs
import kotobaya.android.review.ReviewState
import kotobaya.android.review.ReviewViewModel
import kotobaya.contracts.fsrs.Button
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.UuidV7Source
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AND-REVIEWERR: a failed load shows an error with Retry; a failed answer leaves the card answerable. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReviewErrorTest {
    private val assets get() = ApplicationProvider.getApplicationContext<Application>().assets

    @Test
    fun loadFailureShowsErrorAndRetryReloads() {
        val demo = DemoStore(DemoData.load(assets))
        var failing = true
        val real = demo.reviewBackend()
        val backend = ReviewBackend(
            store = real.store, minter = real.minter, prefs = { ReviewPrefs() }, dictionary = real.dictionary, kanji = real.kanji,
            clock = real.clock, nextStudyDayStart = real.nextStudyDayStart,
            queue = QueueSource { n -> if (failing) error("disk gone") else real.queue.load(n) },
        )
        val vm = ReviewViewModel(backend, io = Dispatchers.Unconfined)
        vm.enter()
        assertEquals(ReviewState.Failed, vm.state.value.review)
        failing = false
        vm.retry()
        assertIs<ReviewState.Showing>(vm.state.value.review)
    }

    @Test
    fun answerFailureReEnablesTheCard() {
        val demo = DemoStore(DemoData.load(assets))
        var failing = true
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        val minter = OpMinter(
            demo.store,
            OpApplier<InMemoryLearningTables> { tx, op, origin ->
                if (failing) error("write failed")
                applier.apply(tx, op, origin)
            },
            Clock { DemoData.NOW_MS },
            UuidV7Source(Random(3)),
        )
        val real = demo.reviewBackend()
        val backend = ReviewBackend(
            store = real.store, minter = minter, prefs = { ReviewPrefs() }, queue = real.queue,
            dictionary = real.dictionary, kanji = real.kanji, clock = real.clock, nextStudyDayStart = real.nextStudyDayStart,
        )
        val vm = ReviewViewModel(backend, io = Dispatchers.Unconfined)
        vm.enter()
        val first = assertIs<ReviewState.Showing>(vm.state.value.review)
        vm.flip()
        vm.answer(first.card.token, Button.GOOD)
        assertEquals(R.string.review_error_action, vm.state.value.actions.message)
        assertEquals(first.card.token, assertIs<ReviewState.Showing>(vm.state.value.review).card.token)
        failing = false
        vm.answer(first.card.token, Button.GOOD)
        assertNotEquals(first.card.token, assertIs<ReviewState.Showing>(vm.state.value.review).card.token)
    }
}
