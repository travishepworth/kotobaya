package kotobaya.android.push.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.push.PushSettingsIntents
import kotobaya.android.push.PushSettingsUiState
import kotobaya.android.push.PushUi

/** Settings > Push. */
@Composable
fun PushSettings(state: PushSettingsUiState, intents: PushSettingsIntents) {
    Column(Modifier.padding(16.dp)) {
        Text(T(R.string.push_explainer))
        Text(
            T(
                when (state.ui) {
                    PushUi.OFF -> R.string.push_state_off
                    PushUi.NO_DISTRIBUTOR -> R.string.push_state_no_distributor
                    PushUi.WAITING -> R.string.push_state_waiting
                    PushUi.ON -> R.string.push_state_on
                },
            ),
        )
        if (!state.hasEndpoint) {
            Button(onClick = intents::enable) { Text(T(R.string.push_enable)) }
        } else {
            if (state.ui == PushUi.WAITING) Button(onClick = intents::retry) { Text(T(R.string.push_retry)) }
            Button(onClick = intents::disable) { Text(T(R.string.push_disable)) }
        }
    }
}
