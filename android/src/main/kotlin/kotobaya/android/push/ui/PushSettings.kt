package kotobaya.android.push.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.push.PushSettingsIntents
import kotobaya.android.push.PushSettingsUiState
import kotobaya.android.push.PushUi
import kotobaya.android.settings.ui.SettingsBody
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbTheme

/** Settings › Server notifications (UnifiedPush). */
@Composable
fun PushSettings(state: PushSettingsUiState, intents: PushSettingsIntents) {
    SettingsBody {
        KbPanel {
            Text(T(R.string.push_explainer), style = KbTheme.type.body, color = KbTheme.colors.text)
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.hasEndpoint) {
                    KbButton(T(R.string.push_enable), intents::enable)
                } else {
                    if (state.ui == PushUi.WAITING) KbButton(T(R.string.push_retry), intents::retry, variant = KbButtonVariant.Tonal)
                    KbButton(T(R.string.push_disable), intents::disable, variant = KbButtonVariant.Outline)
                }
            }
        }
        KbBanner(
            T(
                when (state.ui) {
                    PushUi.OFF -> R.string.push_state_off
                    PushUi.NO_DISTRIBUTOR -> R.string.push_state_no_distributor
                    PushUi.WAITING -> R.string.push_state_waiting
                    PushUi.ON -> R.string.push_state_on
                },
            ),
            kind = when (state.ui) {
                PushUi.ON -> KbFeedback.Success
                PushUi.NO_DISTRIBUTOR -> KbFeedback.Warning
                else -> KbFeedback.Info
            },
        )
    }
}
