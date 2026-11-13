package kotobaya.android.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlin.test.assertTrue
import kotobaya.android.MainActivity
import kotobaya.android.R
import kotobaya.android.nav.Tab
import kotobaya.android.nav.navTag
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = KotobayaApp::class, sdk = [34])
class AppStartsTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() = stopKoin()

    private fun str(id: Int) = rule.activity.getString(id)

    @Test
    fun todayRendersAndTabsNavigate() {
        // UI-APP: Today is the start screen.
        assertTrue(rule.onAllNodesWithText(str(R.string.today_title)).fetchSemanticsNodes().isNotEmpty())
        // The Decks tab (its list has a Refresh button).
        rule.onNodeWithTag(navTag(Tab.DECKS)).performClick()
        rule.onNodeWithContentDescription(str(R.string.decks_refresh)).assertExists()
        // Review: immersive on phones (no bottom bar), with the furigana toggle and a close button that leaves it.
        rule.onNodeWithTag(navTag(Tab.REVIEW)).performClick()
        rule.onNodeWithContentDescription(str(R.string.review_furigana)).assertExists()
        rule.onNodeWithTag(navTag(Tab.SEARCH)).assertDoesNotExist()
        rule.onNodeWithContentDescription(str(R.string.review_close)).performClick()
        rule.onNodeWithTag(navTag(Tab.SEARCH)).assertExists()
    }
}
