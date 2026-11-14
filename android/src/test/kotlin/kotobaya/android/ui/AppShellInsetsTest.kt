package kotobaya.android.ui

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.test.assertEquals
import kotobaya.android.nav.AppShell
import kotobaya.android.nav.Tab
import kotobaya.android.nav.navTag
import kotobaya.android.ui.theme.KotobayaTheme
import kotobaya.android.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-INSETS: with edge-to-edge, every screen (all render inside [AppShell]) starts below the status bar, the bottom
 * bar sits above the navigation bar, and nothing pads twice. Insets are dispatched to the Compose view by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // default SDK sandbox, like StyleguideTest: the sdk-34 one carries app-start tests
class AppShellInsetsTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var view: View

    private fun shell(immersive: Boolean) {
        rule.setContent {
            view = LocalView.current
            KotobayaTheme(ThemeMode.Light) {
                AppShell(selected = Tab.TODAY, onSelect = {}, immersive = immersive) {
                    Box(Modifier.fillMaxSize().testTag(CONTENT)) {
                        // A screen's own bottom padding (e.g. Review's grade bar): only applies when no bar does.
                        Box(Modifier.fillMaxSize().navigationBarsPadding().testTag(INNER))
                    }
                }
            }
        }
        rule.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun phoneTabStartsBelowStatusBarAndBottomBarClearsNavBar() {
        shell(immersive = false)
        val content = bounds(CONTENT)
        assertEquals(STATUS.toFloat(), content.top)
        assertEquals(content.bottom, bounds(INNER).bottom, "content must not pad for the nav bar the bottom bar covers")
        val rootBottom = view.height.toFloat()
        assertEquals(true, bounds(navTag(Tab.TODAY)).bottom <= rootBottom - NAV, "bottom bar sits above the nav bar")
    }

    @Test
    fun immersivePhoneStartsBelowStatusBarAndScreenPadsNavBar() {
        shell(immersive = true)
        val content = bounds(CONTENT)
        assertEquals(STATUS.toFloat(), content.top)
        assertEquals(content.bottom - NAV, bounds(INNER).bottom)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun railLayoutStartsBelowStatusBar() {
        shell(immersive = false)
        assertEquals(STATUS.toFloat(), bounds(CONTENT).top)
        assertEquals(true, bounds(navTag(Tab.TODAY)).top >= STATUS, "rail starts below the status bar")
        assertEquals(bounds(CONTENT).bottom, bounds(INNER).bottom, "nav bar already padded by the shell")
    }

    private companion object {
        const val CONTENT = "content"
        const val INNER = "inner"
        const val STATUS = 72
        const val NAV = 48
    }
}
