package kotobaya.android.shell

import android.util.Log
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotobaya.android.dict.DictStore
import kotobaya.android.shell.web.ShellWiring
import kotobaya.android.di.FeatureEntry
import kotobaya.android.net.MetaLimitsStore
import kotobaya.android.pairing.TokenStore
import kotobaya.android.report.ClientErrorFlusher
import kotobaya.android.report.ClientErrors
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ShellGate
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.identity.AppShellIdentity
import kotobaya.android.shell.api.identity.ClientErrorIntake
import kotobaya.android.shell.api.identity.ClientErrorSink
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.settings.SettingsStore
import kotobaya.android.shell.proxy.CoordinatorProxySync
import kotobaya.android.shell.proxy.ServerUpstream
import kotobaya.android.sync.NetworkMonitor
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SyncCoordinator
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.SyncBootstrap
import kotobaya.android.sync.SyncClient
import kotobaya.android.sync.SyncTriggers
import kotobaya.sync.client.store.UuidV7Source
import kotobaya.sync.client.store.bookkeeping
import org.koin.core.Koin
import org.koin.dsl.module

/** The Android side of the [ShellGate]: pairing from `TokenStore`, L-8 from `sync_state`, readiness from `SyncBootstrap`. */
private class AppShellGate(private val koin: Koin) : ShellGate {
    override fun isPaired(): Boolean = koin.get<TokenStore>().token != null

    override fun isRebuildPending(): Boolean = koin.get<SqlDelightDeviceStore>().read { it.bookkeeping.rebuildPending }

    override suspend fun awaitReady() {
        koin.get<SyncBootstrap>().ready.await()
    }
}

private class KoinShellServices(private val koin: Koin) : ShellServices {
    override fun <T : Any> get(type: KClass<T>): T = koin.get(type)
}

private const val TAG = "KotobayaShell"

/** SHELL-A2 (ADR-036): the local API kernel. [ServerUpstream] is the [Upstream] (SHELL-A5); A3 calls [LocalApi.handle]. */
val shellFeature = FeatureEntry(
    id = "shell",
    module = module {
        // SHELL-A4: one process-wide scope for shell requests (a late `res` is dropped by page life, not by cancelling)
        single<CoroutineScope>(ShellWiring.SCOPE) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
        // SHELL-A5: proxied operations go to the server with the device token (rules P-1 … P-3)
        single<Upstream> {
            val koin = getKoin()
            val settings = get<SettingsStore>()
            val tokens = get<TokenStore>()
            ServerUpstream(
                baseUrl = { settings.serverBaseUrl },
                token = { tokens.token },
                isOnline = { koin.get<NetworkMonitor>().isOnline() },
                sync = CoordinatorProxySync({ koin.get<SyncCoordinator>() }, { koin.get<SqlDelightDeviceStore>() }),
            )
        }
        // SHELL-L1: the identity group's services
        single<ShellIdentity> { AppShellIdentity(get<SqlDelightDeviceStore>(), get<MetaLimitsStore>(), get<DictStore>()) }
        single {
            val koin = getKoin()
            ClientErrorIntake(
                sink = ClientErrorSink { items ->
                    ClientErrors.queueOrNull()?.let { q -> items.forEach { q.add(it) } }
                    koin.get<ClientErrorFlusher>().flushAsync()
                },
                nowMs = System::currentTimeMillis,
            )
        }
        single<LocalWrites<SqlLearningTables>> {
            val koin = getKoin()
            LocalWrites(get<SqlDelightDeviceStore>(), get<SyncClient>().minter) { koin.get<SyncTriggers>().localChange() }
        }
        single {
            val koin = getKoin()
            val ids = UuidV7Source(SecureRandom().asKotlinRandom())
            LocalApi(
                routes = LocalRoutes.all,
                gate = AppShellGate(koin),
                upstream = get(),
                services = KoinShellServices(koin),
                newRequestId = { ids.newOpId(System.currentTimeMillis()) },
                logError = { msg, e -> Log.e(TAG, msg, e) },
            )
        }
    },
)
