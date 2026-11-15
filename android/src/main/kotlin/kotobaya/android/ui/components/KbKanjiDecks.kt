package kotobaya.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.KbTone

/** Chips shown before "+N more" (same as a vocab card's deck chips). */
private const val SHOWN = 3

/**
 * "Appears in decks" for a kanji: one chip per deck (name · number of words with the kanji), [decks] already in priority
 * order; the first three, then "+N more" which expands the rest on tap. Draws nothing for an empty list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KbKanjiDecks(decks: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    if (decks.isEmpty()) return
    var expanded by remember(decks) { mutableStateOf(false) }
    val c = KbTheme.colors
    val tone = KbTone(c.text, c.surfaceSunken)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(T(R.string.kanji_decks_title), style = KbTheme.type.caption, color = c.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (if (expanded) decks else decks.take(SHOWN)).forEach { (name, n) -> KbChip(T(R.string.kanji_deck_chip, name, n), tone) }
            if (!expanded && decks.size > SHOWN) {
                KbChip(T(R.string.review_decks_more, decks.size - SHOWN), tone, Modifier.clickable { expanded = true })
            }
        }
    }
}
