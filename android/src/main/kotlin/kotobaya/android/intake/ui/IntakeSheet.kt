package kotobaya.android.intake.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.intake.IntakeIntents
import kotobaya.android.intake.IntakeOutcome
import kotobaya.android.intake.IntakeUiState
import kotobaya.android.intake.InvalidReason

/** The confirm sheet of a share / process-text / deep link. */
@Composable
fun IntakeSheet(model: IntakeUiState, intents: IntakeIntents, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(T(R.string.intake_title), style = MaterialTheme.typography.titleLarge)
        when (val o = model.outcome) {
            is IntakeOutcome.WordPage -> Text(T(R.string.intake_word_unavailable))
            is IntakeOutcome.Invalid -> Text(
                T(
                    when (o.reason) {
                        InvalidReason.EMPTY -> R.string.intake_err_empty
                        InvalidReason.MISSING_WORD -> R.string.intake_err_missing_word
                        InvalidReason.MALFORMED -> R.string.intake_err_malformed
                        InvalidReason.UNSUPPORTED -> R.string.intake_err_unsupported
                    },
                ),
            )
            IntakeOutcome.Added -> Text(T(R.string.intake_added))
            else -> {
                if (model.trimmed) Text(T(R.string.intake_trimmed), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(model.word, intents::setWord, Modifier.fillMaxWidth(), label = { Text(T(R.string.intake_word)) })
                OutlinedTextField(model.reading, intents::setReading, Modifier.fillMaxWidth(), label = { Text(T(R.string.intake_reading)) })
                OutlinedTextField(model.sentence, intents::setSentence, Modifier.fillMaxWidth(), label = { Text(T(R.string.intake_sentence)) })
                Text(T(R.string.intake_deck), style = MaterialTheme.typography.titleSmall)
                Row { RadioButton(model.deckId == null, { intents.setDeck(null) }); Text(T(R.string.intake_no_deck)) }
                model.decks.forEach { d ->
                    Row { RadioButton(model.deckId == d.id, { intents.setDeck(d.id) }); Text(d.name) }
                }
                when (val e = o) {
                    is IntakeOutcome.Duplicate -> {
                        Text(T(R.string.intake_duplicate))
                        OutlinedButton(onClick = intents::openExisting) { Text(T(R.string.intake_open_existing)) }
                    }
                    is IntakeOutcome.Failed -> Text(T(R.string.intake_err_failed, e.detail), color = MaterialTheme.colorScheme.error)
                    IntakeOutcome.NoWord -> Text(T(R.string.intake_err_no_word), color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                Button(onClick = { intents.submit(false) }, enabled = !model.busy) { Text(T(R.string.intake_add)) }
            }
        }
        OutlinedButton(onClick = onClose) { Text(T(R.string.intake_close)) }
    }
}
