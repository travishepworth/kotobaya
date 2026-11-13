package kotobaya.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/** The Japanese locale: Han characters render with Japanese glyph forms, never Chinese variants. */
val JapaneseLocale: LocaleList = LocaleList("ja")

/** [this] marked as Japanese text (glyph variants and line breaking). Use on every Japanese `Text`. */
fun TextStyle.ja(): TextStyle = copy(localeList = JapaneseLocale)

/**
 * Every type role of docs/DESIGN.md §2.2 with fonts applied ([KbType] is family-less). The `jp…` roles and
 * [reading] are Japanese ([ja] applied); [overline] is Latin only. Get them through `KbTheme.type`.
 */
@Immutable
data class KbTextStyles(
    val jpHero: TextStyle,
    val jpDisplay: TextStyle,
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val titleSm: TextStyle,
    val jpBody: TextStyle,
    val reading: TextStyle,
    val bodyLg: TextStyle,
    val body: TextStyle,
    val bodySm: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
) {
    /** Furigana for [base]: [KbType.RUBY_SCALE] × its size, never below [KbType.RUBY_MIN_SP]. */
    fun ruby(base: TextStyle): TextStyle {
        val size = if (base.fontSize.isSpecified) base.fontSize.value * KbType.RUBY_SCALE else KbType.RUBY_MIN_SP
        val sp: TextUnit = maxOf(size, KbType.RUBY_MIN_SP).sp
        return base.copy(fontSize = sp, lineHeight = sp * 1.2f, textAlign = TextAlign.Center, letterSpacing = 0.sp).ja()
    }
}

/** [KbTextStyles] for the given faces (bundled Noto Sans JP / Noto Serif JP, or the system fallback). */
fun kbTextStyles(sans: FontFamily = FontFamily.Default, serif: FontFamily = FontFamily.Serif): KbTextStyles =
    KbTextStyles(
        jpHero = KbType.jpHero.copy(fontFamily = serif).ja(),
        jpDisplay = KbType.jpDisplay.copy(fontFamily = sans).ja(),
        display = KbType.display.copy(fontFamily = sans, fontFeatureSettings = "tnum"),
        headline = KbType.headline.copy(fontFamily = sans),
        title = KbType.title.copy(fontFamily = sans),
        titleSm = KbType.titleSm.copy(fontFamily = sans),
        jpBody = KbType.jpBody.copy(fontFamily = sans).ja(),
        reading = KbType.reading.copy(fontFamily = sans).ja(),
        bodyLg = KbType.bodyLg.copy(fontFamily = sans),
        body = KbType.body.copy(fontFamily = sans),
        bodySm = KbType.bodySm.copy(fontFamily = sans),
        label = KbType.label.copy(fontFamily = sans),
        caption = KbType.caption.copy(fontFamily = sans, fontFeatureSettings = "tnum"),
        overline = KbType.overline.copy(fontFamily = sans),
    )
