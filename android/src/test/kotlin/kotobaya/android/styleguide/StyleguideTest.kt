package kotobaya.android.styleguide

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotobaya.android.ui.theme.KbBreakpoint
import kotobaya.android.ui.theme.KbColors
import kotobaya.android.ui.theme.KbElevation
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbSize
import kotobaya.android.ui.theme.KbSpace
import kotobaya.android.ui.theme.KbTextStyles
import kotobaya.android.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The styleguide lists every generated token, and every page renders in light and dark (make check). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // default SDK sandbox, like SyncUiTest: the sdk-34 one carries app-start tests
class StyleguideTest {
    @get:Rule
    val rule = createComposeRule()

    private fun fieldsOf(c: Class<*>): Set<String> =
        c.declaredFields.filter { it.name != "INSTANCE" && !it.isSynthetic && !it.name.startsWith("$") }
            .map { it.name }.toSet()

    @Test
    fun catalogueListsEveryColourToken() {
        assertEquals(fieldsOf(KbColors::class.java), StyleguideTokens.allColors.map { it.name }.toSet())
        assertEquals(StyleguideTokens.allColors.size, StyleguideTokens.allColors.map { it.name }.toSet().size, "listed twice")
    }

    @Test
    fun catalogueListsEveryTypeRole() {
        assertEquals(fieldsOf(KbTextStyles::class.java), StyleguideTokens.typeRoles.map { it.name }.toSet())
    }

    @Test
    fun catalogueListsEveryDimensionToken() {
        assertEquals(fieldsOf(KbSpace::class.java), StyleguideTokens.space.map { it.name }.toSet())
        assertEquals(fieldsOf(KbRadius::class.java), StyleguideTokens.radius.map { it.name }.toSet())
        assertEquals(fieldsOf(KbElevation::class.java), StyleguideTokens.elevation.map { it.name }.toSet())
        assertEquals(fieldsOf(KbSize::class.java), StyleguideTokens.size.map { it.name }.toSet())
        assertEquals(fieldsOf(KbBreakpoint::class.java), StyleguideTokens.breakpoint.map { it.name }.toSet())
    }

    @Test
    fun everyPageRendersInLightAndDark() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        var current by mutableStateOf(StyleguidePage.ColorsBase to ThemeMode.Light)
        rule.setContent { StyleguidePageFrame(current.first, current.second) }
        for (page in StyleguidePage.entries) {
            for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
                current = page to mode
                rule.waitForIdle()
                rule.onNodeWithText(context.getString(page.title)).assertExists()
            }
        }
    }

    @Test
    fun fullScreenRendersWithModeSwitch() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        rule.setContent { StyleguideScreen() }
        rule.onNodeWithText(context.getString(kotobaya.android.R.string.sg_theme_dark)).assertExists()
    }
}
