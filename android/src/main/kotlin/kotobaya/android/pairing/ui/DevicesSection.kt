package kotobaya.android.pairing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.pairing.DevicesIntents
import kotobaya.android.pairing.DevicesUiState
import kotobaya.android.settings.ui.SettingsBody
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbConfirmDialog
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbFactRow
import kotobaya.android.ui.components.KbIconTile
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbOverline
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbTextField
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbTheme

/** Settings › Devices: this device (pair by QR or server address + code; unpair behind a confirm). */
@Composable
fun DevicesSection(state: DevicesUiState, intents: DevicesIntents) {
    val context = LocalContext.current
    val c = KbTheme.colors
    var confirm by remember { mutableStateOf(false) }
    SettingsBody {
        KbPanel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                KbIconTile(
                    KbIcons.Device,
                    container = if (state.paired) c.successContainer else c.warningContainer,
                    tint = if (state.paired) c.onSuccessContainer else c.onWarningContainer,
                )
                Column(Modifier.weight(1f)) {
                    Text(T(R.string.devices_this_device), style = KbTheme.type.titleSm, color = c.text)
                    Text(
                        if (state.paired) T(R.string.devices_paired, state.serverUrl.orEmpty()) else T(R.string.devices_not_paired),
                        style = KbTheme.type.bodySm,
                        color = c.textMuted,
                    )
                }
            }
        }
        if (state.paired) {
            KbPanel(padding = PaddingValues(vertical = 4.dp)) {
                KbFactRow(T(R.string.devices_server_label), state.serverUrl.orEmpty())
                state.deviceId?.let {
                    KbDivider(inset = 16.dp)
                    KbFactRow(T(R.string.devices_id_label), it, valueStyle = KbTheme.type.bodySm)
                }
            }
            KbPanel {
                Text(T(R.string.devices_unpair_note), style = KbTheme.type.bodySm, color = c.textMuted)
                KbButton(T(R.string.devices_unpair), { confirm = true }, Modifier.padding(top = 12.dp), variant = KbButtonVariant.Outline)
            }
        } else {
            KbPanel {
                Text(T(R.string.devices_pair_how), style = KbTheme.type.body, color = c.text)
                KbButton(
                    T(R.string.pair_scan_qr),
                    {
                        // the system QR scanner is a platform UI; its result goes to the ViewModel
                        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                        GmsBarcodeScanning.getClient(context, opts).startScan().addOnSuccessListener { b -> intents.scanned(b.rawValue) }
                    },
                    Modifier.padding(top = 14.dp).fillMaxWidth(),
                    leading = KbIcons.Device,
                )
            }
            KbPanel {
                KbOverline(T(R.string.devices_manual))
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    KbTextField(state.url, intents::setUrl, label = T(R.string.pair_server_url), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    KbTextField(state.code, intents::setCode, label = T(R.string.pair_code))
                    KbButton(
                        T(R.string.pair_submit), intents::submit, Modifier.align(Alignment.End),
                        variant = KbButtonVariant.Tonal, enabled = !state.busy, loading = state.busy,
                    )
                }
            }
        }
        state.message?.let { KbBanner(T(it), kind = KbFeedback.Success) }
        state.error?.let { KbBanner(T(it), kind = KbFeedback.Danger) }
    }
    if (confirm) {
        KbConfirmDialog(
            title = T(R.string.devices_unpair_confirm_title),
            body = T(R.string.devices_unpair_note),
            confirm = T(R.string.devices_unpair),
            dismiss = T(R.string.devices_cancel),
            onConfirm = { confirm = false; intents.unpair() },
            onDismiss = { confirm = false },
            destructive = true,
        )
    }
}
