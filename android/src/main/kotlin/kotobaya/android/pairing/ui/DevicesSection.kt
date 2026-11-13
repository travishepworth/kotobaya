package kotobaya.android.pairing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.pairing.DevicesIntents
import kotobaya.android.pairing.DevicesUiState

/** Settings > Devices: this device only. */
@Composable
fun DevicesSection(state: DevicesUiState, intents: DevicesIntents) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(T(R.string.devices_title))
        Text(T(R.string.devices_this_device))
        state.deviceId?.let { Text(T(R.string.devices_id, it)) }
        if (state.paired) {
            Text(T(R.string.devices_paired, state.serverUrl.orEmpty()))
            Text(T(R.string.devices_unpair_note))
            OutlinedButton(onClick = intents::unpair) {
                Text(T(R.string.devices_unpair))
            }
        } else {
            Text(T(R.string.devices_not_paired))
            Button(onClick = {
                // the system QR scanner is a platform UI; its result goes to the ViewModel
                val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, opts).startScan()
                    .addOnSuccessListener { b -> intents.scanned(b.rawValue) }
            }) { Text(T(R.string.pair_scan_qr)) }
            OutlinedTextField(state.url, intents::setUrl, label = { Text(T(R.string.pair_server_url)) }, singleLine = true)
            OutlinedTextField(state.code, intents::setCode, label = { Text(T(R.string.pair_code)) }, singleLine = true)
            Button(enabled = !state.busy, onClick = intents::submit) { Text(T(R.string.pair_submit)) }
        }
        state.message?.let { Text(T(it)) }
        state.error?.let { Text(T(it)) }
    }
}
