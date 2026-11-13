package kotobaya.android.ankiapi.takoboto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.ankiapi.takoboto.TakobotoDebugIntents
import kotobaya.android.ankiapi.takoboto.TakobotoDebugUiState
import kotobaya.android.i18n.T
import kotobaya.android.settings.ui.SettingsBody
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbTone
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbTheme

private const val DEBUG_LINES = 30

/** Settings › Takoboto (the AnkiDroid stand-in): counts, note-type readiness and the recent provider calls. */
@Composable
fun TakobotoDebug(state: TakobotoDebugUiState, intents: TakobotoDebugIntents) {
    SettingsBody {
        KbPanel {
            Text(T(R.string.ankiapi_counts, state.added, state.duplicates, state.refused), style = KbTheme.type.body, color = KbTheme.colors.text)
        }
        KbBanner(
            T(if (state.noteTypeReady) R.string.ankiapi_note_type_ready else R.string.ankiapi_note_type_missing),
            kind = if (state.noteTypeReady) KbFeedback.Success else KbFeedback.Warning,
        )
        Log(T(R.string.ankiapi_unknown_title, state.unknown.size), state.unknown)
        Log(T(R.string.ankiapi_recent_title), state.calls)
        KbButton(T(R.string.ankiapi_refresh), intents::refresh, variant = KbButtonVariant.Outline)
    }
}

@Composable
private fun Log(title: String, lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = KbTheme.type.titleSm, color = KbTheme.colors.text)
        KbPanel(tone = KbTone.Sunken) {
            if (lines.isEmpty()) {
                Text(T(R.string.ankiapi_none), style = KbTheme.type.bodySm, color = KbTheme.colors.textMuted)
            }
            lines.takeLast(DEBUG_LINES).forEach {
                Text(it, style = KbTheme.type.caption.copy(fontFamily = FontFamily.Monospace), color = KbTheme.colors.text, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}
