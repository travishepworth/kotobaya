package kotobaya.android.review.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.review.BackExtras
import kotobaya.android.review.ExampleWord
import kotobaya.android.review.ReviewIntents
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbChip
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.theme.JapaneseLocale
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.KbTone
import kotobaya.contracts.dict.KanjiReadingType

/**
 * AND-REVIEW-INFO: below the revealed card, inside its surface: a kanji card's example words (reading type, gloss), a
 * vocab card's deck chips (top 3 by priority, "+N more"), and the first-class "Open in dictionary" button. Everything
 * comes precomputed in [BackExtras]; nothing is read here.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BackPanel(extras: BackExtras, intents: ReviewIntents, modifier: Modifier = Modifier) {
    val canOpen = extras.entrySeq != null || extras.kanjiLiteral != null
    if (extras.examples.isEmpty() && extras.decks.isEmpty() && !canOpen) return
    val c = KbTheme.colors
    Column(modifier.fillMaxWidth()) {
        KbDivider()
        Column(
            Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (extras.examples.isNotEmpty()) {
                Text(T(R.string.review_examples_title), style = KbTheme.type.caption, color = c.textMuted)
                extras.examples.forEach { ExampleRow(it) }
            }
            if (extras.decks.isNotEmpty()) {
                Text(T(R.string.review_decks_title), style = KbTheme.type.caption, color = c.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tone = KbTone(c.text, c.surfaceSunken)
                    extras.decks.forEach { KbChip(it, tone) }
                    if (extras.moreDecks > 0) KbChip(T(R.string.review_decks_more, extras.moreDecks), tone)
                }
            }
            if (canOpen) {
                KbButton(
                    T(R.string.review_open_dictionary),
                    { intents.openInDictionary(extras) },
                    Modifier.fillMaxWidth(),
                    variant = KbButtonVariant.Tonal,
                    leading = KbIcons.Book,
                )
            }
        }
    }
}

@Composable
private fun ExampleRow(w: ExampleWord) {
    val c = KbTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "${w.headword}  ${w.reading}",
                style = KbTheme.type.jpBody.copy(localeList = JapaneseLocale),
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (w.gloss.isNotEmpty()) Text(w.gloss, style = KbTheme.type.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (w.type) {
            KanjiReadingType.ON -> KbChip(T(R.string.review_example_on), KbTone(c.text, c.surfaceSunken))
            KanjiReadingType.KUN -> KbChip(T(R.string.review_example_kun), KbTone(c.text, c.surfaceSunken))
            KanjiReadingType.OTHER -> Unit
        }
    }
}
