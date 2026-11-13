package kotobaya.android.intake.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.intake.IntakeIntents
import kotobaya.android.intake.IntakeOutcome
import kotobaya.android.intake.IntakeUiState
import kotobaya.android.intake.InvalidReason
import kotobaya.android.nav.Seal
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbEmptyState
import kotobaya.android.ui.components.KbEmptyTone
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbOverline
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbRadioRow
import kotobaya.android.ui.components.KbTextField
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.ja

/**
 * The share-intake sheet (DESIGN §5.13): it rises over the source app (the activity's window is translucent at the
 * bottom), with the word, reading and sentence, the target deck, and Add.
 */
@Composable
fun IntakeSheet(model: IntakeUiState, intents: IntakeIntents, onClose: () -> Unit) {
    val c = KbTheme.colors
    Box(Modifier.fillMaxSize().background(c.scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(KbTheme.shapes.sheet)
                .background(c.surfaceRaised)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(KbTheme.shapes.full).background(c.borderStrong))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Seal()
                Text(T(R.string.intake_title), style = KbTheme.type.title, color = c.text)
            }
            when (val o = model.outcome) {
                is IntakeOutcome.WordPage -> KbBanner(T(R.string.intake_word_unavailable))
                is IntakeOutcome.Invalid -> KbBanner(
                    T(
                        when (o.reason) {
                            InvalidReason.EMPTY -> R.string.intake_err_empty
                            InvalidReason.MISSING_WORD -> R.string.intake_err_missing_word
                            InvalidReason.MALFORMED -> R.string.intake_err_malformed
                            InvalidReason.UNSUPPORTED -> R.string.intake_err_unsupported
                        },
                    ),
                    kind = KbFeedback.Warning,
                )
                IntakeOutcome.Added -> KbEmptyState(T(R.string.intake_added), tone = KbEmptyTone.Clear)
                else -> Form(model, o, intents)
            }
            KbButton(T(R.string.intake_close), onClose, Modifier.fillMaxWidth(), variant = KbButtonVariant.Quiet)
        }
    }
}

@Composable
private fun Form(model: IntakeUiState, o: IntakeOutcome, intents: IntakeIntents) {
    if (model.trimmed) KbBanner(T(R.string.intake_trimmed), kind = KbFeedback.Warning)
    KbTextField(model.word, intents::setWord, label = T(R.string.intake_word), textStyle = KbTheme.type.titleSm.ja())
    KbTextField(model.reading, intents::setReading, label = T(R.string.intake_reading), textStyle = KbTheme.type.body.ja())
    KbTextField(model.sentence, intents::setSentence, label = T(R.string.intake_sentence), singleLine = false, textStyle = KbTheme.type.jpBody.ja())
    KbPanel(padding = PaddingValues(12.dp)) {
        KbOverline(T(R.string.intake_deck), Modifier.padding(start = 4.dp, bottom = 4.dp))
        Column(Modifier.selectableGroup()) {
            KbRadioRow(T(R.string.intake_no_deck), model.deckId == null, { intents.setDeck(null) })
            model.decks.forEach { d -> KbRadioRow(d.name, model.deckId == d.id, { intents.setDeck(d.id) }) }
        }
    }
    when (o) {
        is IntakeOutcome.Duplicate -> KbBanner(
            T(R.string.intake_duplicate),
            action = { KbButton(T(R.string.intake_open_existing), intents::openExisting, variant = KbButtonVariant.Quiet) },
        )
        is IntakeOutcome.Failed -> KbBanner(T(R.string.intake_err_failed, o.detail), kind = KbFeedback.Danger)
        IntakeOutcome.NoWord -> KbBanner(T(R.string.intake_err_no_word), kind = KbFeedback.Danger)
        else -> Unit
    }
    KbButton(T(R.string.intake_add), { intents.submit(false) }, Modifier.fillMaxWidth(), enabled = !model.busy, loading = model.busy, leading = KbIcons.Add)
}
