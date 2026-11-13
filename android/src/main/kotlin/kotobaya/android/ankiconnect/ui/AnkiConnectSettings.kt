package kotobaya.android.ankiconnect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.ankiconnect.AnkiConnectSettingsIntents
import kotobaya.android.ankiconnect.AnkiConnectSettingsUiState
import kotobaya.android.ankiconnect.AnkiConnectStatus
import kotobaya.android.i18n.T

/** Settings > AnkiConnect. */
@Composable
fun AnkiConnectSettings(state: AnkiConnectSettingsUiState, intents: AnkiConnectSettingsIntents) {
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.enabled, onCheckedChange = intents::setEnabled)
            Text(T(R.string.ankiconnect_enable), Modifier.padding(start = 8.dp))
        }
        OutlinedTextField(
            state.port,
            intents::setPort,
            Modifier.fillMaxWidth(),
            label = { Text(T(R.string.ankiconnect_port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        OutlinedTextField(
            state.apiKey, intents::setApiKey, Modifier.fillMaxWidth(),
            label = { Text(T(R.string.ankiconnect_api_key)) }, singleLine = true,
        )
        OutlinedTextField(
            state.origins, intents::setOrigins, Modifier.fillMaxWidth(),
            label = { Text(T(R.string.ankiconnect_origins)) },
        )
        Text(
            when (val s = state.status) {
                AnkiConnectStatus.Stopped -> T(R.string.ankiconnect_status_stopped)
                is AnkiConnectStatus.Running -> T(R.string.ankiconnect_status_running, s.port)
                is AnkiConnectStatus.Failed -> T(R.string.ankiconnect_status_failed, s.message)
            },
        )
        Text(T(R.string.ankiconnect_hint, state.savedPort))
    }
}
