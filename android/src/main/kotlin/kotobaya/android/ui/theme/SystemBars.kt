package kotobaya.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Makes the system bars follow the theme in use (after the Settings › Appearance override), not the system night
 * setting the XML theme resolves against: status bar = `bg`, navigation bar = `surface`, dark icons on the light
 * theme. Call inside [KotobayaTheme]. With [statusBar] false (the share sheet, which floats over the source app) only
 * the navigation bar is touched.
 */
@Composable
fun KbSystemBars(statusBar: Boolean = true) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val dark = KbTheme.isDark
    val bg = KbTheme.colors.bg.toArgb()
    val surface = KbTheme.colors.surface.toArgb()
    SideEffect {
        val window = view.context.findWindow() ?: return@SideEffect
        applySystemBars(window, dark, bg, surface, statusBar)
    }
}

@Suppress("DEPRECATION") // Bar colours still apply below Android 15's forced edge-to-edge; the icon appearance always does.
internal fun applySystemBars(window: Window, dark: Boolean, bg: Int, surface: Int, statusBar: Boolean) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (statusBar) {
        window.statusBarColor = bg
        controller.isAppearanceLightStatusBars = !dark
    }
    window.navigationBarColor = surface
    controller.isAppearanceLightNavigationBars = !dark
}

private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}
