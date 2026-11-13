package kotobaya.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import kotobaya.android.ui.theme.KbCardState
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.ja

/**
 * Japanese text that must not break mid-word (headwords, sentences): Japanese glyphs plus phrase-based line
 * breaking (Android 13+ breaks between bunsetsu; older versions fall back to strict CJK breaking).
 */
fun TextStyle.jaPhrase(): TextStyle =
    ja().copy(lineBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase))

/** The card state of a stored `card.state` value. */
fun cardStateOf(wire: String, suspended: Boolean = false): KbCardState = when {
    suspended -> KbCardState.Suspended
    wire == "learning" -> KbCardState.Learning
    wire == "review" -> KbCardState.Review
    wire == "relearning" -> KbCardState.Relearning
    else -> KbCardState.New
}

/** The chip tone of a word status (`word_status.status`): learning/known read as card states, the rest as muted. */
fun wordStatusTone(wire: String): KbCardState = when (wire) {
    "learning" -> KbCardState.Learning
    "known", "never_forget" -> KbCardState.Review
    "blacklisted", "ignored" -> KbCardState.Suspended
    else -> KbCardState.New
}

/** A list-row title in Japanese (title-sm size, Japanese glyphs, phrase breaking). */
@Composable
fun jaTitle(): TextStyle = KbTheme.type.titleSm.jaPhrase()
