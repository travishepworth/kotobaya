package kotobaya.android.pairing

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.launch
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.i18n.T
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import kotobaya.android.net.MetaLimitsStore
import kotobaya.android.net.ServerReachability
import kotobaya.android.settings.SettingsStore
import org.koin.compose.koinInject
import org.koin.dsl.module

/** User-facing message for any [ApiError]. */
@StringRes
fun errorMessageRes(e: ApiError): Int = when (e) {
    is ApiError.Offline -> R.string.err_offline
    is ApiError.Unreachable -> R.string.err_unreachable
    is ApiError.Unauthorized -> R.string.err_unauthorized
    is ApiError.Forbidden -> R.string.err_forbidden
    is ApiError.InvalidPairingCode -> R.string.pair_err_code_expired
    is ApiError.Validation -> if (e.isKindConflict) R.string.pair_err_kind_conflict else R.string.pair_err_validation
    is ApiError.RateLimited -> R.string.pair_err_rate_limited
    is ApiError.Server -> R.string.err_server
    is ApiError.Other -> R.string.err_other
    is ApiError.BadBody -> R.string.err_bad_body
}

private fun errorArg(e: ApiError): Any? = when (e) {
    is ApiError.Server -> e.status
    is ApiError.Other -> e.status
    is ApiError.Validation -> e.code ?: e.pointer ?: ""
    else -> null
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private val pairingModule = module {
    single { TokenStore(get<Context>()) }
    single { MetaLimitsStore(get()) }
    single {
        val ctx = get<Context>()
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        ApiClient({ settings.serverBaseUrl }, { tokens.token }, { isOnline(ctx) })
    }
    single { ServerReachability(get(), get()) }
    single {
        val ctx = get<Context>()
        val tokens = get<TokenStore>()
        PairingRepository(
            db = get(),
            settings = get(),
            tokens = tokens,
            deviceName = Build.MODEL.ifBlank { "Android" },
            clientFor = { url -> ApiClient({ url }, { null }, { isOnline(ctx) }) },
        )
    }
}

/** Settings > Devices: this device only. */
@Composable
private fun DevicesSection() {
    val repo = koinInject<PairingRepository>()
    var paired by remember { mutableStateOf(repo.isPaired) }
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Pair<Int, Any?>?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun submit(target: PairingTarget) {
        busy = true
        error = null
        scope.launch {
            when (val r = repo.pair(target)) {
                is PairResult.Paired -> { paired = true; message = R.string.pair_success }
                is PairResult.Failed -> error = errorMessageRes(r.error) to errorArg(r.error)
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(T(R.string.devices_title))
        Text(T(R.string.devices_this_device))
        val deviceId by produceState<String?>(null, paired) { value = withContext(Dispatchers.IO) { repo.deviceId } }
        deviceId?.let { Text(T(R.string.devices_id, it)) }
        if (paired) {
            Text(T(R.string.devices_paired, repo.serverBaseUrl.orEmpty()))
            Text(T(R.string.devices_unpair_note))
            OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { repo.unpair() }; paired = false; message = null }) {
                Text(T(R.string.devices_unpair))
            }
        } else {
            Text(T(R.string.devices_not_paired))
            Button(onClick = {
                val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, opts).startScan()
                    .addOnSuccessListener { b ->
                        val target = b.rawValue?.let(PairingUri::parse)
                        if (target == null) error = R.string.pair_err_invalid_uri to null else submit(target)
                    }
            }) { Text(T(R.string.pair_scan_qr)) }
            OutlinedTextField(url, { url = it }, label = { Text(T(R.string.pair_server_url)) }, singleLine = true)
            OutlinedTextField(code, { code = it }, label = { Text(T(R.string.pair_code)) }, singleLine = true)
            Button(enabled = !busy, onClick = {
                val server = PairingUri.normalizeServerUrl(url)
                val c = PairingUri.normalizeCode(code)
                if (server == null || c.isEmpty()) error = R.string.pair_err_bad_url to null
                else submit(PairingTarget(server, c))
            }) { Text(T(R.string.pair_submit)) }
        }
        message?.let { Text(T(it)) }
        error?.let { (res, arg) -> Text(if (arg == null) T(res) else T(res, arg)) }
    }
}

/** THE registration for featureEntries. */
val pairingFeature = FeatureEntry(
    id = "pairing",
    module = pairingModule,
    settingsSections = listOf(SettingsSection("devices", R.string.devices_title) { DevicesSection() }),
)
