package kotobaya.android.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotobaya.android.db.DbThreadGuard
import kotobaya.android.demo.DemoData
import kotobaya.android.demo.DemoStore
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.pairing.DevicesPort
import kotobaya.android.pairing.DevicesViewModel
import kotobaya.android.pairing.PairResult
import kotobaya.android.pairing.PairingTarget
import kotobaya.android.review.ReviewState
import kotobaya.android.review.ReviewViewModel
import kotobaya.contracts.fsrs.Button
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI-FOUND-APP: behaviour the ViewModels must keep from the composables they replaced, and the BUG-8 review fixes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ViewModelBehaviourTest {
    private val assets get() = ApplicationProvider.getApplicationContext<Application>().assets

    @Test
    fun reviewSessionEndsLikeTheOldComposition() {
        var ended = 0
        val vm = ReviewViewModel(DemoStore(DemoData.load(assets)).reviewBackend(), onSessionEnd = { ended++ }, io = Dispatchers.Unconfined)
        vm.enter()
        val first = assertIs<ReviewState.Showing>(vm.state.value.review)
        vm.flip()
        vm.answer(first.card.token, Button.GOOD)
        val next = assertIs<ReviewState.Showing>(vm.state.value.review)
        assertNotEquals(first.card.token, next.card.token)
        assertTrue(vm.state.value.meta.canUndo)
        vm.leave()
        assertEquals(1, ended)
        // coming back starts a fresh session (nothing to undo); leaving it unanswered ends nothing
        vm.enter()
        assertFalse(vm.state.value.meta.canUndo)
        vm.leave()
        assertEquals(1, ended)
    }

    @Test
    fun reviewWithoutABackendSaysNotReady() {
        val vm = ReviewViewModel(null, io = Dispatchers.Unconfined)
        vm.enter()
        assertFalse(vm.state.value.available)
    }

    @Test
    fun unpairFinishesBeforeTheScreenShowsNotPaired() {
        val pairedWhileUnpairing = mutableListOf<Boolean>()
        lateinit var vm: DevicesViewModel
        var paired = true
        val port = object : DevicesPort {
            override fun isPaired() = paired
            override fun deviceId() = "dev"
            override fun serverBaseUrl() = "https://s.example"
            override suspend fun pair(target: PairingTarget) = PairResult.Paired("dev", "srv")
            override fun unpair() {
                pairedWhileUnpairing += vm.state.value.paired
                paired = false
            }
        }
        vm = DevicesViewModel(port, Dispatchers.Unconfined)
        assertTrue(vm.state.value.paired)
        vm.unpair()
        assertEquals(listOf(true), pairedWhileUnpairing)
        assertFalse(vm.state.value.paired)
    }

    @Test
    fun dictionaryReadsOnTheMainThreadAreReported() {
        val dir = File.createTempFile("dict", "").also { it.delete(); it.mkdirs() }
        val guard = DbThreadGuard { true }
        InstalledDictionary(DictStore(dir), guard).info()
        assertEquals(1, guard.violations.size)
        val offMain = DbThreadGuard { false }
        InstalledDictionary(DictStore(dir), offMain).info()
        assertEquals(0, offMain.violations.size)
    }
}
