package kotobaya.android.report

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotobaya.android.di.FeatureEntry
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.PairingRepository
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import org.koin.dsl.module

/** OBS-1c-and: client error reporting. The queue and crash handler are installed in `KotobayaApp`; this is the sender. */
val reportFeature = FeatureEntry(
    id = "report",
    module = module {
        single {
            val settings = get<SettingsStore>()
            val tokens = get<TokenStore>()
            val pairing = get<PairingRepository>()
            ClientErrorFlusher(
                client = { ApiClient({ settings.serverBaseUrl }, { tokens.token }) },
                isPaired = { pairing.isPaired },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        }
    },
)
