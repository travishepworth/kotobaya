package kotobaya.android.push

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotobaya.android.push.ui.PushSettings
import org.koin.compose.getKoin
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.notify.SystemNotifier
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val pushModule = module {
    single { PushPrefs(androidContext()) }
    // T303c replaces this binding with the sync engine's trigger.
    single<PushSyncTrigger> { PushSyncTrigger { } }
    single { PushHandler(androidContext(), get(), SystemNotifier(androidContext())) }
    single {
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        DevicePushApi({ settings.serverBaseUrl }, { tokens.token })
    }
    single { PushRegistrar(get<Context>(), get(), get()) }
}

@Composable
private fun PushSettingsRoute() {
    val koin = getKoin()
    val vm: PushSettingsViewModel = viewModel { PushSettingsViewModel(koin.get(), koin.get()) }
    val state by vm.state.collectAsState()
    PushSettings(state, vm)
}

val pushFeature = FeatureEntry(
    id = "push",
    module = pushModule,
    settingsSections = listOf(SettingsSection("push", R.string.push_section_title) { PushSettingsRoute() }),
)
