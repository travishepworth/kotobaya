package kotobaya.android.notify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.notify.NotifySettingsIntents
import kotobaya.android.notify.NotifySettingsUiState
import kotobaya.android.notify.EVENING_DEFAULT_HOUR

/** Android 13+ asks for POST_NOTIFICATIONS; older versions need nothing. */
@Composable
fun NotificationRationaleScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Column(Modifier.padding(16.dp)) {
        Text(T(R.string.notify_permission_title))
        Text(T(R.string.notify_permission_rationale))
        if (granted) {
            Text(T(R.string.notify_permission_granted))
        } else {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text(T(R.string.notify_permission_allow)) }
            Text(T(R.string.notify_permission_denied_note))
        }
    }
}

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Settings > Notifications. */
@Composable
fun NotifySettings(state: NotifySettingsUiState, intents: NotifySettingsIntents) {
    Column {
        NotificationRationaleScreen()
        Row(Modifier.padding(horizontal = 16.dp)) {
            Text(T(R.string.notify_nudges_enable))
            Switch(checked = state.nudges, onCheckedChange = intents::setNudges)
        }
        Row(Modifier.padding(horizontal = 16.dp)) {
            Text(T(R.string.notify_evening_enable, EVENING_DEFAULT_HOUR))
            Switch(checked = state.evening, onCheckedChange = intents::setEvening)
        }
    }
}
