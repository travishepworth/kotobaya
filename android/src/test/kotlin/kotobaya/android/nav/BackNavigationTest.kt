package kotobaya.android.nav

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotobaya.android.decks.DeckTarget
import kotobaya.android.search.DeckSheet
import kotobaya.android.search.DeckSheetMode
import kotobaya.android.search.SearchDetail
import kotobaya.android.search.SearchIntents
import kotobaya.android.search.SearchResults
import kotobaya.android.search.SearchUiState
import kotobaya.android.search.ui.SearchScreen
import kotobaya.android.ui.theme.KotobayaTheme
import kotobaya.android.ui.theme.ThemeMode
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AND-BACK (ADR-030): the tab and pushed-page back stacks, and Search's back order (sheet, detail, query, leave). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    private var applyObserver: ObserverHandle? = null

    /**
     * Compose applies global-snapshot writes through a process-wide dispatcher created by the first Compose test of
     * the Robolectric sandbox and bound to that test's main looper state. After the looper is reset between tests the
     * apply can be lost: `Snapshot.current.hasPendingChanges()` then stays true with an empty looper and the recomposer
     * idle, and `waitForIdle` spins until AppNotIdleException (60 s) under the full suite (never alone). Every test here
     * therefore registers its own write observer that posts `sendApplyNotifications` to the current main looper.
     */
    @Before
    fun applyGlobalWritesOnThisTestsLooper() {
        val handler = Handler(Looper.getMainLooper())
        applyObserver = Snapshot.registerGlobalWriteObserver { handler.post { Snapshot.sendApplyNotifications() } }
        rule.runOnUiThread { Snapshot.sendApplyNotifications() }
    }

    @After
    fun stopApplyingGlobalWrites() {
        applyObserver?.dispose()
    }

    private fun host() {
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.HOME) {
                listOf(Routes.HOME, Routes.REVIEW, Routes.SEARCH, Routes.DECKS, Routes.SETTINGS).forEach { r ->
                    composable(r) { Text(r) }
                }
            }
        }
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun go(route: String) {
        rule.runOnUiThread { navigateTo(nav, route) }
        rule.waitForIdle()
    }

    private val at get() = nav.currentBackStackEntry?.destination?.route

    @Test
    fun backFromAnyTabGoesToHomeNotThroughTheOtherTabs() {
        host()
        go(Routes.SEARCH)
        go(Routes.DECKS)
        go(Routes.SETTINGS)
        assertEquals(Routes.SETTINGS, at)
        back()
        assertEquals(Routes.HOME, at)
    }

    @Test
    fun theDictionaryOpenedFromAReviewStacksOnItAndBackReturnsToTheReview() {
        host()
        rule.runOnUiThread { nav.navigate(Routes.REVIEW) { launchSingleTop = true } }
        rule.waitForIdle()
        // AppNav's rule for a dictionary jump from a pushed page
        rule.runOnUiThread { nav.navigate(Routes.SEARCH) { launchSingleTop = true } }
        rule.waitForIdle()
        assertEquals(Routes.SEARCH, at)
        back()
        assertEquals(Routes.REVIEW, at)
        back()
        assertEquals(Routes.HOME, at)
    }

    /** Records what Search asked for. */
    private class Spy : SearchIntents {
        val calls = ArrayList<String>()
        override fun closeDetail() { calls += "detail" }
        override fun closeDeckSheet() { calls += "sheet" }
        override fun clearQuery() { calls += "query" }
    }

    private fun search(state: SearchUiState, spy: Spy) {
        rule.setContent { KotobayaTheme(ThemeMode.Light) { SearchScreen(state, spy) } }
    }

    @Test
    fun searchBackClosesTheDetail() {
        val spy = Spy()
        search(SearchUiState(query = "取る", results = SearchResults.Failed(false), detail = SearchDetail.Word(1, kotobaya.android.word.WordLoad.NotFound)), spy)
        back()
        assertEquals(listOf("detail"), spy.calls)
    }

    @Test
    fun searchBackClearsTheQueryWhenNoDetailIsOpen() {
        val spy = Spy()
        search(SearchUiState(query = "取る", results = SearchResults.Failed(false)), spy)
        back()
        assertEquals(listOf("query"), spy.calls)
    }

    @Test
    fun anOpenSheetClosesBeforeTheDetailOrAnythingElse() {
        val spy = Spy()
        search(
            SearchUiState(
                query = "取る",
                detail = SearchDetail.Word(1, kotobaya.android.word.WordLoad.NotFound),
                sheet = DeckSheet(DeckSheetMode.TOGGLE, DeckTarget(entrySeq = 1)),
            ),
            spy,
        )
        back()
        assertEquals(listOf("sheet"), spy.calls)
    }
}
