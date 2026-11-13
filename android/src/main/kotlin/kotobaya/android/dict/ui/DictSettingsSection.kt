package kotobaya.android.dict.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.dict.DictSettingsIntents
import kotobaya.android.dict.DictSettingsUiState
import kotobaya.android.dict.InstallResult
import kotobaya.android.dict.InstallState
import kotobaya.android.i18n.T

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

/** Settings > Offline storage: scope, installed release, sizes, "update now". */
@Composable
fun DictSettingsSection(state: DictSettingsUiState, intents: DictSettingsIntents) {
    Column(Modifier.padding(8.dp)) {
        val i = state.info
        Text(
            if (i == null) T(R.string.dict_not_installed)
            else T(R.string.dict_installed, i.version, i.releaseId, i.scope),
        )
        Text(T(R.string.dict_size, mb(state.bytes)))
        Text(T(R.string.dict_scope_label))
        Row {
            for ((value, label) in listOf("top60k" to R.string.dict_scope_top60k, "all" to R.string.dict_scope_all)) {
                if (state.scope == value) {
                    Button(onClick = {}) { Text(T(label)) }
                } else {
                    OutlinedButton(onClick = { intents.chooseScope(value) }) { Text(T(label)) }
                }
            }
        }
        Button(onClick = intents::updateNow, enabled = state.install !is InstallState.Running) { Text(T(R.string.dict_update_now)) }
        Text(statusText(state.install))
    }
}

@Composable
private fun statusText(state: InstallState): String = when (state) {
    is InstallState.Idle -> ""
    is InstallState.Running -> when (state.stage) {
        InstallState.Stage.CHECKING -> T(R.string.dict_state_checking)
        InstallState.Stage.DOWNLOADING -> T(R.string.dict_state_downloading, mb(state.doneBytes), mb(state.totalBytes))
        InstallState.Stage.VERIFYING -> T(R.string.dict_state_verifying)
        InstallState.Stage.APPLYING -> T(R.string.dict_state_applying)
    }
    is InstallState.Finished -> when (val r = state.result) {
        is InstallResult.NoRelease -> T(R.string.dict_result_no_release)
        is InstallResult.UpToDate, is InstallResult.DowngradeIgnored -> T(R.string.dict_result_up_to_date)
        is InstallResult.Installed -> T(R.string.dict_result_installed, r.releaseId)
        is InstallResult.NeedsNewerApp -> T(R.string.dict_result_needs_newer_app)
        is InstallResult.InsufficientSpace -> T(R.string.dict_result_no_space, mb(r.requiredBytes), mb(r.availableBytes))
        is InstallResult.Failed -> T(R.string.dict_result_failed, r.reason)
    }
}
