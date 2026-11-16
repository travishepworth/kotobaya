package kotobaya.android.shell.web

import android.content.Context
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import kotobaya.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class ShellThemeTest {
    private fun isLight(): Boolean {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val t = ctx.resources.newTheme().apply { applyStyle(R.style.Theme_Kotobaya, true) }
        val tv = TypedValue()
        t.resolveAttribute(android.R.attr.isLightTheme, tv, true)
        return tv.data != 0
    }

    @Config(qualifiers = "night")
    @Test
    fun nightIsDark() = assertEquals(false, isLight())

    @Config(qualifiers = "notnight")
    @Test
    fun dayIsLight() = assertEquals(true, isLight())
}
