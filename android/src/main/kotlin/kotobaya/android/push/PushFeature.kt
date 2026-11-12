package kotobaya.android.push

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.i18n.T
import kotobaya.android.notify.SystemNotifier
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import org.koin.android.ext.koin.androidContext
import org.koin.compose.koinInject
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

private enum class PushUi { OFF, NO_DISTRIBUTOR, WAITING, ON }

@Composable
private fun PushSettings() {
    val prefs: PushPrefs = koinInject()
    val registrar: PushRegistrar = koinInject()
    var ui by remember {
        mutableStateOf(
            when {
                prefs.endpoint == null -> PushUi.OFF
                prefs.registeredWithServer -> PushUi.ON
                else -> PushUi.WAITING
            },
        )
    }
    Column(Modifier.padding(16.dp)) {
        Text(T(R.string.push_explainer))
        Text(
            T(
                when (ui) {
                    PushUi.OFF -> R.string.push_state_off
                    PushUi.NO_DISTRIBUTOR -> R.string.push_state_no_distributor
                    PushUi.WAITING -> R.string.push_state_waiting
                    PushUi.ON -> R.string.push_state_on
                },
            ),
        )
        if (prefs.endpoint == null) {
            Button(onClick = {
                ui = when (registrar.enable()) {
                    EnableResult.NoDistributor -> PushUi.NO_DISTRIBUTOR
                    EnableResult.Requested -> PushUi.WAITING
                }
            }) { Text(T(R.string.push_enable)) }
        } else {
            if (ui == PushUi.WAITING) Button(onClick = { registrar.retryServerRegistration() }) { Text(T(R.string.push_retry)) }
            Button(onClick = { registrar.disable(); ui = PushUi.OFF }) { Text(T(R.string.push_disable)) }
        }
    }
}

val pushFeature = FeatureEntry(
    id = "push",
    module = pushModule,
    settingsSections = listOf(SettingsSection("push", R.string.push_section_title) { PushSettings() }),
)
