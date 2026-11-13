package kotobaya.android.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.net.ApiClient
import kotobaya.android.net.MetaLimitsStore
import kotobaya.android.net.ServerReachability
import kotobaya.android.pairing.ui.DevicesSection
import kotobaya.android.settings.SettingsStore
import org.koin.compose.getKoin
import org.koin.dsl.module

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
    factory<DevicesPort> { AppDevicesPort(get()) }
}

@Composable
private fun DevicesRoute() {
    val koin = getKoin()
    val vm: DevicesViewModel = viewModel { DevicesViewModel(koin.get<DevicesPort>()) }
    val state by vm.state.collectAsState()
    DevicesSection(state, vm)
}

/** THE registration for featureEntries. */
val pairingFeature = FeatureEntry(
    id = "pairing",
    module = pairingModule,
    settingsSections = listOf(SettingsSection("devices", R.string.devices_title) { DevicesRoute() }),
)
