package kotobaya.android.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.demo.DemoSettingsIntents
import kotobaya.android.demo.DemoSettingsUiState
import kotobaya.android.i18n.T

/** Settings > Demo data (debug builds): the switch and the scenario. */
@Composable
fun DemoSection(state: DemoSettingsUiState, intents: DemoSettingsIntents) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.available) {
            Text(T(R.string.demo_unavailable))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(T(R.string.demo_enable), Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = intents::setEnabled)
        }
        Text(T(R.string.demo_scenario))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in listOf("default" to R.string.demo_scenario_default, "due900" to R.string.demo_scenario_due900, "empty" to R.string.demo_scenario_empty)) {
                if (state.scenario == value) {
                    Button(onClick = {}) { Text(T(label)) }
                } else {
                    OutlinedButton(onClick = { intents.setScenario(value) }) { Text(T(label)) }
                }
            }
        }
        Text(T(R.string.demo_note))
    }
}
