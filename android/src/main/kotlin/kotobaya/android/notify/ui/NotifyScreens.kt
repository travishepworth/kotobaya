package kotobaya.android.notify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.notify.EVENING_DEFAULT_HOUR
import kotobaya.android.notify.NotifySettingsIntents
import kotobaya.android.notify.NotifySettingsUiState
import kotobaya.android.settings.ui.SettingsBody
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbIconTile
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbSwitchRow
import kotobaya.android.ui.theme.KbTheme

/** Android 13+ asks for POST_NOTIFICATIONS; older versions need nothing. A panel: why, then Allow. */
@Composable
fun NotificationRationaleScreen() {
    val context = LocalContext.current
    val c = KbTheme.colors
    var granted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    KbPanel {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KbIconTile(
                KbIcons.Bell,
                container = if (granted) c.successContainer else c.primaryContainer,
                tint = if (granted) c.onSuccessContainer else c.onPrimaryContainer,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(T(R.string.notify_permission_title), style = KbTheme.type.titleSm, color = c.text)
                Text(T(R.string.notify_permission_rationale), style = KbTheme.type.bodySm, color = c.textMuted)
                if (granted) {
                    Text(T(R.string.notify_permission_granted), style = KbTheme.type.label, color = c.onSuccessContainer)
                } else {
                    KbButton(
                        T(R.string.notify_permission_allow),
                        { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        Modifier.padding(top = 6.dp),
                    )
                    Text(T(R.string.notify_permission_denied_note), style = KbTheme.type.caption, color = c.textMuted)
                }
            }
        }
    }
}

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Settings › Notifications. */
@Composable
fun NotifySettings(state: NotifySettingsUiState, intents: NotifySettingsIntents) {
    SettingsBody {
        NotificationRationaleScreen()
        KbPanel(padding = PaddingValues(vertical = 4.dp)) {
            KbSwitchRow(T(R.string.notify_nudges_enable), state.nudges, intents::setNudges, supporting = T(R.string.notify_nudges_hint))
            KbDivider(inset = 16.dp)
            KbSwitchRow(T(R.string.notify_evening_enable, EVENING_DEFAULT_HOUR), state.evening, intents::setEvening)
        }
    }
}
