package kotobaya.android.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotobaya.android.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = KotobayaApp::class, sdk = [34])
class AppStartsTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeRendersAndPlaceholdersNavigate() {
        rule.onNodeWithText("Welcome to Kotobaya").assertExists()
        for (name in listOf("Search", "Decks", "Settings")) {
            rule.onNodeWithText(name).performClick()
            rule.onNodeWithText("$name is not built yet.").assertExists()
        }
        // T304b: Review is real now (the furigana toggle is on its toolbar).
        rule.onNodeWithText("Review").performClick()
        rule.onNodeWithText("Furigana").assertExists()
    }
}
