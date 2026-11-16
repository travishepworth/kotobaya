package kotobaya.android.push

import android.content.Context
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
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

val pushFeature = FeatureEntry(
    id = "push",
    module = pushModule,
)
