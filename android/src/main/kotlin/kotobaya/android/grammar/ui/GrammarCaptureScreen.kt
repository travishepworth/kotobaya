package kotobaya.android.grammar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.grammar.GrammarCaptureIntents
import kotobaya.android.grammar.GrammarCaptureUiState
import kotobaya.android.i18n.T

/**
 * Where a sentence view puts its "Saw something weird" entry: `captureEntry(sentenceId, mediaId, text)`. The route
 * glue supplies one bound to that sentence's capture ViewModel; previews pass [NoCaptureEntry] or a static one.
 */
typealias CaptureEntrySlot = @Composable (sentenceId: String, mediaId: String?, text: String) -> Unit

/** A slot that renders the closed entry with no behaviour (previews). */
val NoCaptureEntry: CaptureEntrySlot = { _, _, text -> GrammarCaptureEntry(text, GrammarCaptureUiState(), object : GrammarCaptureIntents {}) }

/** Collapsed "Saw something weird" entry for a sentence; the panel when open. */
@Composable
fun GrammarCaptureEntry(text: String, state: GrammarCaptureUiState, intents: GrammarCaptureIntents) {
    if (state.open) {
        GrammarCapture(text, state, intents)
    } else {
        OutlinedButton(onClick = intents::open) { Text(T(R.string.gc_open)) }
    }
}

@Composable
fun GrammarCapture(text: String, state: GrammarCaptureUiState, intents: GrammarCaptureIntents) {
    // The read-only sentence field keeps its own selection; the ViewModel gets the span.
    var field by remember { mutableStateOf(TextFieldValue(text)) }
    val existing = state.existing

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(T(R.string.gc_title), style = MaterialTheme.typography.titleMedium)
        Text(T(R.string.gc_select))
        OutlinedTextField(
            value = field,
            onValueChange = { v ->
                // Read-only: keep the text, accept only the selection.
                field = TextFieldValue(text, TextRange(v.selection.start, v.selection.end))
                intents.select(v.selection.start, v.selection.end)
            },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val sp = state.span
        Text(if (sp == null) T(R.string.gc_no_span) else T(R.string.gc_span, text.substring(sp.first, sp.last + 1)))

        Row {
            RadioButton(selected = !existing, onClick = { intents.setExisting(false) })
            Text(T(R.string.gc_mode_new), Modifier.padding(top = 12.dp))
            RadioButton(selected = existing, onClick = { intents.setExisting(true) })
            Text(T(R.string.gc_mode_existing), Modifier.padding(top = 12.dp))
        }
        if (!existing) {
            OutlinedTextField(state.title, intents::setTitle, Modifier.fillMaxWidth(), enabled = !state.frozen, label = { Text(T(R.string.gc_field_title)) })
            OutlinedTextField(state.pattern, intents::setPattern, Modifier.fillMaxWidth(), enabled = !state.frozen, label = { Text(T(R.string.gc_field_pattern)) })
        } else {
            OutlinedTextField(state.query, intents::setQuery, Modifier.fillMaxWidth(), label = { Text(T(R.string.gc_search)) })
            if (state.query.isNotBlank() && state.results.isEmpty()) Text(T(R.string.gc_no_results))
            state.results.forEach { p ->
                Row {
                    RadioButton(selected = state.chosen?.id == p.id, onClick = { intents.choose(p) })
                    Text(p.title + if (p.pattern.isNotEmpty()) "  ${p.pattern}" else "", Modifier.padding(top = 12.dp))
                }
            }
        }

        OutlinedTextField(state.rule, intents::setRule, Modifier.fillMaxWidth(), label = { Text(T(R.string.gc_field_rule)) })
        OutlinedButton(
            enabled = !state.busy && state.rule.isNotBlank() && (!existing || state.chosen != null),
            onClick = intents::testRule,
        ) { Text(T(if (state.busy) R.string.gc_testing else R.string.gc_test)) }
        state.test?.let { t ->
            Text(T(R.string.gc_test_result, t.matches.size, t.scanned))
            if (t.truncated) Text(T(R.string.gc_test_truncated))
            t.matches.forEach { m -> Text(m.text, style = MaterialTheme.typography.bodySmall) }
        }

        state.message?.let { Text(T(it), color = MaterialTheme.colorScheme.error) }
        if (state.done) Text(T(R.string.gc_saved))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !state.busy && !state.done, onClick = intents::save) {
                Text(T(if (state.busy) R.string.gc_saving else R.string.gc_save))
            }
            OutlinedButton(onClick = intents::close) { Text(T(if (state.done) R.string.gc_close else R.string.gc_cancel)) }
        }
    }
}
