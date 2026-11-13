package kotobaya.android.ankiconnect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.ankiconnect.AnkiConnectSettingsIntents
import kotobaya.android.ankiconnect.AnkiConnectSettingsUiState
import kotobaya.android.ankiconnect.AnkiConnectStatus
import kotobaya.android.i18n.T
import kotobaya.android.settings.ui.SettingsBody
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbSwitchRow
import kotobaya.android.ui.components.KbTextField
import kotobaya.android.ui.theme.KbFeedback

/** Settings › AnkiConnect: the switch, its status, and the port, key and origins. */
@Composable
fun AnkiConnectSettings(state: AnkiConnectSettingsUiState, intents: AnkiConnectSettingsIntents) {
    SettingsBody {
        KbPanel(padding = PaddingValues(vertical = 4.dp)) {
            KbSwitchRow(T(R.string.ankiconnect_enable), state.enabled, intents::setEnabled, supporting = T(R.string.ankiconnect_hint, state.savedPort))
        }
        when (val s = state.status) {
            AnkiConnectStatus.Stopped -> KbBanner(T(R.string.ankiconnect_status_stopped))
            is AnkiConnectStatus.Running -> KbBanner(T(R.string.ankiconnect_status_running, s.port), kind = KbFeedback.Success)
            is AnkiConnectStatus.Failed -> KbBanner(T(R.string.ankiconnect_status_failed, s.message), kind = KbFeedback.Danger)
        }
        KbPanel {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                KbTextField(state.port, intents::setPort, label = T(R.string.ankiconnect_port), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                KbTextField(state.apiKey, intents::setApiKey, label = T(R.string.ankiconnect_api_key))
                KbTextField(state.origins, intents::setOrigins, label = T(R.string.ankiconnect_origins), singleLine = false)
            }
        }
    }
}
