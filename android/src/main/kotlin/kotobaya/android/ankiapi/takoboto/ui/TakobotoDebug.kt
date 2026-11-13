package kotobaya.android.ankiapi.takoboto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.ankiapi.takoboto.TakobotoDebugIntents
import kotobaya.android.ankiapi.takoboto.TakobotoDebugUiState
import kotobaya.android.i18n.T

private const val DEBUG_LINES = 30

/** Settings > Takoboto (debug). */
@Composable
fun TakobotoDebug(state: TakobotoDebugUiState, intents: TakobotoDebugIntents) {
    val snapshot = state.calls
    val unknown = state.unknown
    Column(Modifier.padding(16.dp)) {
        Text(T(R.string.ankiapi_counts, state.added, state.duplicates, state.refused))
        Text(T(if (state.noteTypeReady) R.string.ankiapi_note_type_ready else R.string.ankiapi_note_type_missing))
        Text(T(R.string.ankiapi_unknown_title, unknown.size), style = MaterialTheme.typography.titleSmall)
        if (unknown.isEmpty()) Text(T(R.string.ankiapi_none))
        unknown.takeLast(DEBUG_LINES).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(T(R.string.ankiapi_recent_title), style = MaterialTheme.typography.titleSmall)
        if (snapshot.isEmpty()) Text(T(R.string.ankiapi_none))
        snapshot.takeLast(DEBUG_LINES).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = intents::refresh) { Text(T(R.string.ankiapi_refresh)) }
    }
}
