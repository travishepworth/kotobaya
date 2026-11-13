package kotobaya.android.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/**
 * The user's theme choice (docs/DESIGN.md §2.5): follow the system by default, or force light or dark from
 * Settings › Appearance. Persisting the choice is the settings screen's job; [storageKey] is the stable value to store.
 */
enum class ThemeMode(val storageKey: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    /** Whether this mode renders dark, given whether the system is currently dark. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        /** The mode for a stored [storageKey]; unknown or missing values fall back to [System]. */
        fun fromStorageKey(key: String?): ThemeMode = entries.firstOrNull { it.storageKey == key } ?: System
    }
}

private val LocalKbColors = staticCompositionLocalOf { KbColorsLight }
private val LocalKbTextStyles = staticCompositionLocalOf { kbTextStyles() }
private val LocalKbDark = staticCompositionLocalOf { false }
private val LocalKbReducedMotion = staticCompositionLocalOf { false }

/**
 * Kotobaya's Compose theme: picks [KbColorsLight] or [KbColorsDark] from [mode], provides them (and the text styles)
 * through composition locals, and wraps [MaterialTheme] with the matching colour scheme, typography and [KbShapes]
 * so stock Material components fit the palette. No dynamic colour: the palette is the brand.
 *
 * [sans] and [serif] are the Japanese faces (bundled Noto Sans JP / Noto Serif JP once UI-APP adds them). Read the
 * tokens inside through [KbTheme].
 */
@Composable
fun KotobayaTheme(
    mode: ThemeMode = ThemeMode.System,
    sans: FontFamily = FontFamily.Default,
    serif: FontFamily = FontFamily.Serif,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark(isSystemInDarkTheme())
    val colors = if (dark) KbColorsDark else KbColorsLight
    val textStyles = remember(sans, serif) { kbTextStyles(sans, serif) }
    val typography = remember(sans, serif) { kbTypography(sans, serif) }
    val reducedMotion = systemReducedMotion()
    CompositionLocalProvider(
        LocalKbColors provides colors,
        LocalKbTextStyles provides textStyles,
        LocalKbDark provides dark,
        LocalKbReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(colorScheme = colors.toColorScheme(dark), typography = typography, shapes = KbShapes, content = content)
    }
}

/** True when the system animator duration scale is 0 (Android's "remove animations"). */
@Composable
private fun systemReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * The single entry point for tokens inside [KotobayaTheme], like `MaterialTheme`:
 * `KbTheme.colors.gradeGood`, `KbTheme.type.jpDisplay`, `KbTheme.space.s4`, `KbTheme.motion.BASE`.
 */
object KbTheme {
    /** Semantic colours of the current theme (light or dark). */
    val colors: KbColors
        @Composable @ReadOnlyComposable
        get() = LocalKbColors.current

    /** Text styles with the theme's fonts; Japanese roles carry `LocaleList("ja")`. */
    val type: KbTextStyles
        @Composable @ReadOnlyComposable
        get() = LocalKbTextStyles.current

    /** Whether the current theme is dark (after the manual override). */
    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalKbDark.current

    /** Whether movement should become fades only (system animations off). See [kbTween]. */
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalKbReducedMotion.current

    val space: KbSpace get() = KbSpace
    val radius: KbRadius get() = KbRadius
    val shapes: KbShapeSet get() = KbShapeSet
    val elevation: KbElevation get() = KbElevation
    val size: KbSize get() = KbSize
    val motion: KbMotion get() = KbMotion
    val breakpoint: KbBreakpoint get() = KbBreakpoint
}
