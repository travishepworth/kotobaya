package kotobaya.android.shell.nav

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import androidx.core.view.WindowCompat

/** Haptic kinds of the bridge to `HapticFeedbackConstants` (SHELL.md 6.3). */
object Haptics {
    fun constant(kind: String, sdk: Int = Build.VERSION.SDK_INT): Int? = when (kind) {
        "tick" -> HapticFeedbackConstants.CLOCK_TICK
        "confirm" -> if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        "reject" -> if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        else -> null
    }

    fun perform(view: View, kind: String) {
        constant(kind)?.let { view.performHapticFeedback(it) }
    }
}

/** Status and navigation bar colours from `theme` (SHELL.md 6.3). */
object SystemBars {
    /** `#RRGGBB` to an opaque ARGB int; null when malformed. */
    fun parse(surface: String): Int? {
        if (surface.length != 7 || surface[0] != '#') return null
        val rgb = surface.substring(1).toIntOrNull(16) ?: return null
        return 0xFF000000.toInt() or rgb
    }

    /** Colours the bars (and the padded [container], which shows through behind them with edge-to-edge). */
    fun apply(window: Window, container: View, dark: Boolean, surface: String) {
        val color = parse(surface) ?: return
        container.setBackgroundColor(color)
        @Suppress("DEPRECATION") // ignored on API 35+, where the container background shows through instead
        window.statusBarColor = color
        @Suppress("DEPRECATION")
        window.navigationBarColor = color
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
    }

    /** A new page life: back to the system default until the page sends `theme` again (SHELL-A4 note). */
    fun reset(window: Window, container: View) {
        val dark = (container.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val tv = android.util.TypedValue()
        val bg = if (container.context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)) {
            tv.data
        } else {
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        apply(window, container, dark, "#%06X".format(bg and 0xFFFFFF))
    }
}
