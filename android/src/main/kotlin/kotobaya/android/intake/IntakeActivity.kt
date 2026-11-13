package kotobaya.android.intake

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.ui.KotobayaTheme

/**
 * Entry for the share sheet (`ACTION_SEND text/plain`), the text-selection menu (`ACTION_PROCESS_TEXT`) and
 * the `kotobaya://add` / `kotobaya://word/<id>` links. Parsing is [IntakeParser]; creation is T305b's
 * `NoteCreator` behind [IntakeServices].
 */
class IntakeActivity : ComponentActivity() {
    internal lateinit var model: IntakeModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = IntakeModel(IntakeServicesProvider.get(), parse(intent))
        setContent { KotobayaTheme { IntakeSheet(model, onClose = { finish() }) } }
    }

    companion object {
        fun parse(intent: Intent?): IntakeTarget = IntakeParser.parse(
            action = intent?.action,
            mimeType = intent?.type,
            extraText = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            processText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
            data = intent?.dataString,
        )
    }
}

@Composable
private fun IntakeSheet(model: IntakeModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val submit = { force: Boolean -> scope.launch { withContext(Dispatchers.IO) { model.submit(force) } }; Unit }
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
                OutlinedTextField(model.word, { model.word = it }, Modifier.fillMaxWidth(), label = { Text(T(R.string.intake_word)) })
                OutlinedTextField(model.reading, { model.reading = it }, Modifier.fillMaxWidth(), label = { Text(T(R.string.intake_reading)) })
                OutlinedTextField(model.sentence, { model.sentence = it }, Modifier.fillMaxWidth(), label = { Text(T(R.string.intake_sentence)) })
                Text(T(R.string.intake_deck), style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.Row { RadioButton(model.deckId == null, { model.deckId = null }); Text(T(R.string.intake_no_deck)) }
                model.decks.forEach { d ->
                    androidx.compose.foundation.layout.Row { RadioButton(model.deckId == d.id, { model.deckId = d.id }); Text(d.name) }
                }
                when (val e = o) {
                    is IntakeOutcome.Duplicate -> {
                        Text(T(R.string.intake_duplicate))
                        OutlinedButton(onClick = { model.openExisting() }) { Text(T(R.string.intake_open_existing)) }
                    }
                    is IntakeOutcome.Failed -> Text(T(R.string.intake_err_failed, e.detail), color = MaterialTheme.colorScheme.error)
                    IntakeOutcome.NoWord -> Text(T(R.string.intake_err_no_word), color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                Button(onClick = { submit(false) }, enabled = !model.busy) { Text(T(R.string.intake_add)) }
            }
        }
        OutlinedButton(onClick = onClose) { Text(T(R.string.intake_close)) }
    }
}
