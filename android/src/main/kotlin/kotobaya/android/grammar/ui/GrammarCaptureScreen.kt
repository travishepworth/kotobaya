package kotobaya.android.grammar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
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
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbRadioRow
import kotobaya.android.ui.components.KbSegmented
import kotobaya.android.ui.components.KbTextField
import kotobaya.android.ui.components.KbTextFieldValue
import kotobaya.android.ui.components.KbTone
import kotobaya.android.ui.components.jaPhrase
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.ja

/**
 * Where a sentence view puts its "Saw something weird" entry: `captureEntry(sentenceId, mediaId, text)`. The route
 * glue supplies one bound to that sentence's capture ViewModel; previews pass [NoCaptureEntry] or a static one.
 */
typealias CaptureEntrySlot = @Composable (sentenceId: String, mediaId: String?, text: String) -> Unit

/** A slot that renders the closed entry with no behaviour (previews). */
val NoCaptureEntry: CaptureEntrySlot = { _, _, text -> GrammarCaptureEntry(text, GrammarCaptureUiState(), object : GrammarCaptureIntents {}) }

/** Collapsed "Saw something weird" entry for a sentence (a quiet button); the panel when open. */
@Composable
fun GrammarCaptureEntry(text: String, state: GrammarCaptureUiState, intents: GrammarCaptureIntents) {
    if (state.open) {
        GrammarCapture(text, state, intents)
    } else {
        KbButton(T(R.string.gc_open), intents::open, variant = KbButtonVariant.Quiet, leading = KbIcons.Flag)
    }
}

/** The capture panel: select the span, new or existing point, a draft rule with its test, Save. */
@Composable
fun GrammarCapture(text: String, state: GrammarCaptureUiState, intents: GrammarCaptureIntents) {
    val c = KbTheme.colors
    // The read-only sentence field keeps its own selection; the ViewModel gets the span.
    var field by remember { mutableStateOf(TextFieldValue(text)) }
    val existing = state.existing
    KbPanel(Modifier.fillMaxWidth().padding(vertical = 8.dp), tone = KbTone.Raised) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(T(R.string.gc_title), style = KbTheme.type.title, color = c.text)
            KbTextFieldValue(
                value = field,
                onValueChange = { v ->
                    // Read-only: keep the text, accept only the selection.
                    field = TextFieldValue(text, TextRange(v.selection.start, v.selection.end))
                    intents.select(v.selection.start, v.selection.end)
                },
                label = T(R.string.gc_select),
                readOnly = true,
                textStyle = KbTheme.type.jpBody.jaPhrase(),
            )
            val sp = state.span
            Text(
                if (sp == null) T(R.string.gc_no_span) else T(R.string.gc_span, text.substring(sp.first, sp.last + 1)),
                style = KbTheme.type.bodySm.ja(),
                color = if (sp == null) c.textMuted else c.text,
            )
            KbSegmented(
                listOf(false to T(R.string.gc_mode_new), true to T(R.string.gc_mode_existing)),
                selected = existing,
                onSelect = intents::setExisting,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!existing) {
                KbTextField(state.title, intents::setTitle, label = T(R.string.gc_field_title), enabled = !state.frozen)
                KbTextField(state.pattern, intents::setPattern, label = T(R.string.gc_field_pattern), enabled = !state.frozen, textStyle = KbTheme.type.body.ja())
            } else {
                KbTextField(state.query, intents::setQuery, label = T(R.string.gc_search))
                if (state.query.isNotBlank() && state.results.isEmpty()) Text(T(R.string.gc_no_results), style = KbTheme.type.bodySm, color = c.textMuted)
                Column(Modifier.selectableGroup()) {
                    state.results.forEach { p ->
                        KbRadioRow(p.title, state.chosen?.id == p.id, { intents.choose(p) }, supporting = p.pattern.ifEmpty { null })
                    }
                }
            }
            KbTextField(state.rule, intents::setRule, label = T(R.string.gc_field_rule), singleLine = false)
            KbButton(
                T(if (state.busy) R.string.gc_testing else R.string.gc_test),
                intents::testRule,
                variant = KbButtonVariant.Outline,
                enabled = !state.busy && state.rule.isNotBlank() && (!existing || state.chosen != null),
            )
            state.test?.let { t ->
                KbPanel(tone = KbTone.Sunken) {
                    Text(T(R.string.gc_test_result, t.matches.size, t.scanned), style = KbTheme.type.label, color = c.text)
                    if (t.truncated) Text(T(R.string.gc_test_truncated), style = KbTheme.type.caption, color = c.textMuted)
                    t.matches.forEach { m -> Text(m.text, style = KbTheme.type.bodySm.ja(), color = c.text, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            state.message?.let { KbBanner(T(it), kind = KbFeedback.Danger) }
            if (state.done) KbBanner(T(R.string.gc_saved), kind = KbFeedback.Success)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KbButton(
                    T(if (state.busy) R.string.gc_saving else R.string.gc_save), intents::save, Modifier.fillMaxWidth(),
                    enabled = !state.busy && !state.done, loading = state.busy,
                )
                KbButton(T(if (state.done) R.string.gc_close else R.string.gc_cancel), intents::close, Modifier.fillMaxWidth(), variant = KbButtonVariant.Quiet)
            }
        }
    }
}
