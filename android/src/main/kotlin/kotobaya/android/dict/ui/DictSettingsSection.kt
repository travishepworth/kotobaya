package kotobaya.android.dict.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import kotobaya.android.settings.ui.SettingsBody
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbFactRow
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbProgressBar
import kotobaya.android.ui.components.KbSegmented
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbTheme

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

/** Settings › Offline dictionary: installed release, size, scope and "Update now" with its progress. */
@Composable
fun DictSettingsSection(state: DictSettingsUiState, intents: DictSettingsIntents) {
    val c = KbTheme.colors
    val running = state.install as? InstallState.Running
    SettingsBody {
        KbPanel(padding = PaddingValues(vertical = 4.dp)) {
            val i = state.info
            KbFactRow(
                T(R.string.dict_installed_label),
                if (i == null) T(R.string.dict_not_installed) else T(R.string.dict_installed, i.version, i.releaseId, i.scope),
            )
            KbDivider(inset = 16.dp)
            KbFactRow(T(R.string.dict_size_label), T(R.string.storage_mb, mb(state.bytes)))
        }
        KbPanel {
            Text(T(R.string.dict_scope_label), style = KbTheme.type.titleSm, color = c.text)
            KbSegmented(
                listOf("top60k" to T(R.string.dict_scope_top60k), "all" to T(R.string.dict_scope_all)),
                selected = state.scope,
                onSelect = intents::chooseScope,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
            )
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KbButton(T(R.string.dict_update_now), intents::updateNow, variant = KbButtonVariant.Tonal, enabled = running == null, loading = running != null)
                if (running != null) {
                    if (running.stage == InstallState.Stage.DOWNLOADING && running.totalBytes > 0) {
                        KbProgressBar(running.doneBytes.toFloat() / running.totalBytes)
                    }
                    Text(statusText(running), style = KbTheme.type.bodySm, color = c.textMuted)
                }
            }
        }
        (state.install as? InstallState.Finished)?.let { f ->
            val kind = when (f.result) {
                is InstallResult.Installed, is InstallResult.UpToDate, is InstallResult.DowngradeIgnored -> KbFeedback.Success
                is InstallResult.NoRelease -> KbFeedback.Info
                else -> KbFeedback.Danger
            }
            KbBanner(statusText(f), kind = kind)
        }
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
