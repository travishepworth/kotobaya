package kotobaya.android.nav

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import kotlin.test.assertEquals
import kotobaya.android.MainActivity
import kotobaya.android.app.KotobayaApp
import kotobaya.android.ui.theme.KbColorsDark
import kotobaya.android.ui.theme.KbColorsLight
import kotobaya.android.ui.theme.ThemeMode
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The status and navigation bars follow the in-app Appearance choice, not the system night setting (light here). */
@RunWith(RobolectricTestRunner::class)
@Config(application = KotobayaApp::class, sdk = [34])
class SystemBarsTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        Appearance.set(rule.activity, ThemeMode.System)
        stopKoin()
    }

    @Suppress("DEPRECATION")
    private fun assertBars(bg: Int, surface: Int, lightIcons: Boolean) {
        val window = rule.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals(bg, window.statusBarColor)
        assertEquals(surface, window.navigationBarColor)
        assertEquals(lightIcons, controller.isAppearanceLightStatusBars)
        assertEquals(lightIcons, controller.isAppearanceLightNavigationBars)
    }

    @Test
    fun darkOverrideOnLightSystemDarkensTheBars() {
        rule.runOnUiThread { Appearance.set(rule.activity, ThemeMode.Dark) }
        rule.waitForIdle()
        assertBars(KbColorsDark.bg.toArgb(), KbColorsDark.surface.toArgb(), lightIcons = false)
    }

    @Test
    fun lightOverrideAndBackToSystem() {
        rule.runOnUiThread { Appearance.set(rule.activity, ThemeMode.Light) }
        rule.waitForIdle()
        assertBars(KbColorsLight.bg.toArgb(), KbColorsLight.surface.toArgb(), lightIcons = true)
        rule.runOnUiThread { Appearance.set(rule.activity, ThemeMode.Dark) }
        rule.waitForIdle()
        assertBars(KbColorsDark.bg.toArgb(), KbColorsDark.surface.toArgb(), lightIcons = false)
    }
}
