package kotobaya.android.demo.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.demo.DemoSettingsIntents
import kotobaya.android.demo.DemoSettingsUiState
import kotobaya.android.i18n.T
import kotobaya.android.settings.ui.SettingsBody
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbSegmented
import kotobaya.android.ui.components.KbSwitchRow
import kotobaya.android.ui.theme.KbTheme

/** Settings › Demo data (debug builds): the switch and the scenario. */
@Composable
fun DemoSection(state: DemoSettingsUiState, intents: DemoSettingsIntents) {
    SettingsBody {
        if (!state.available) {
            KbBanner(T(R.string.demo_unavailable))
            return@SettingsBody
        }
        KbPanel(padding = PaddingValues(vertical = 4.dp)) {
            KbSwitchRow(T(R.string.demo_enable), state.enabled, intents::setEnabled)
        }
        KbPanel {
            Text(T(R.string.demo_scenario), style = KbTheme.type.titleSm, color = KbTheme.colors.text)
            KbSegmented(
                listOf(
                    "default" to T(R.string.demo_scenario_default),
                    "due900" to T(R.string.demo_scenario_due900),
                    "empty" to T(R.string.demo_scenario_empty),
                ),
                selected = state.scenario,
                onSelect = intents::setScenario,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
            )
        }
        KbBanner(T(R.string.demo_note))
    }
}
