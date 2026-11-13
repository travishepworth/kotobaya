package kotobaya.android.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotobaya.android.MainActivity
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

    @Test
    fun homeRendersAndPlaceholdersNavigate() {
        rule.onNodeWithText("Welcome to Kotobaya").assertExists()
        // AND-DECKS: the Decks tab is real now (its toolbar has a Refresh button).
        rule.onNodeWithText("Decks").performClick()
        rule.onNodeWithText("Refresh").assertExists()
        // T304b: Review is real now (the furigana toggle is on its toolbar).
        rule.onNodeWithText("Review").performClick()
        rule.onNodeWithText("Furigana").assertExists()
    }
}
