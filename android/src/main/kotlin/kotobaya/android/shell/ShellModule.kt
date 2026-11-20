package kotobaya.android.shell

import android.util.Log
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotobaya.android.dict.DictStore
import kotobaya.android.ankiconnect.AnkiConnectController
import kotobaya.android.ankiconnect.AnkiConnectPrefs
import kotobaya.android.media.BlobStore
import kotobaya.android.shell.api.device.AnkiConnectOps
import kotobaya.android.shell.api.device.CoreAnkiConnectOps
import kotobaya.android.shell.api.device.CoreDeviceOps
import kotobaya.android.shell.api.device.CoreDictOps
import kotobaya.android.shell.api.device.CoreNotifyOps
import kotobaya.android.shell.api.device.CorePushOps
import kotobaya.android.shell.api.device.CoreStorageOps
import kotobaya.android.shell.api.device.DeviceOps
import kotobaya.android.shell.api.device.DictEvents
import kotobaya.android.shell.api.device.DictOps
import kotobaya.android.shell.api.device.NotifyOps
import kotobaya.android.shell.api.device.PushOps
import kotobaya.android.shell.api.device.StorageOps
import kotobaya.android.shell.api.device.toJson
import kotobaya.android.shell.events.ShellEvents
import kotobaya.android.shell.web.BridgeMessage
import kotobaya.android.shell.web.BridgeTransport
import kotobaya.android.sync.SyncOutcome
import kotobaya.android.sync.SyncStatusRepository
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
import org.koin.core.qualifier.named
import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.shell.api.actions.CardQuery
import kotobaya.android.search.ReplicaCardQuery
import kotobaya.db.app.AppDatabase

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

/**
 * Where native producers (sync outcome, local write commit, connectivity, notes added by other apps) reach the SPA:
 * [transport] is the current page's bridge (set by `ShellWiring.bridge`); [events] and [dict] exist once the
 * shell's graph built them. Nothing here creates them, so a background sync with no page costs nothing.
 */
object ShellEventHub {
    @Volatile
    var transport: BridgeTransport? = null

    @Volatile
    internal var events: ShellEvents? = null

    @Volatile
    internal var dict: DictEvents? = null

    /** `BridgeTransport.send`; a dropped event (no page) is fine. */
    fun send(event: BridgeMessage.Event) {
        transport?.send(event)
    }

    /** The `SyncCoordinator` outcome hook. With no [events] (no page yet) the K-4 stale marks still reach the DB (LF-A2b). */
    fun onSyncOutcome(outcome: SyncOutcome) {
        events?.onSyncOutcome(outcome) ?: ShellEvents.applyCacheMarks(outcome, ShellEvents.dbStaleMarks())
        dict?.poke()
    }

    /** The `LocalWrites` after-commit hook; the same DB fallback as [onSyncOutcome]. */
    fun onLocalWrite() {
        events?.onLocalWrite() ?: runCatching { ShellEvents.dbStaleMarks()?.onReplicaChanged() }
    }
}

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
        // SHELL-A7: the device group's seams (singletons: the dictionary install must not start twice)
        single<DeviceOps> { CoreDeviceOps.from(KoinShellServices(getKoin())) }
        single<DictOps> { CoreDictOps.from(KoinShellServices(getKoin()), get<CoroutineScope>(ShellWiring.SCOPE)) }
        single<StorageOps> { CoreStorageOps.from(KoinShellServices(getKoin())) }
        single<NotifyOps> { CoreNotifyOps.from(KoinShellServices(getKoin())) }
        single<PushOps> { CorePushOps.from(KoinShellServices(getKoin())) }
        single<AnkiConnectOps> { CoreAnkiConnectOps.from(KoinShellServices(getKoin())) }
        // SHELL-A6/A7: native -> web events
        single<ShellEvents> {
            val device = get<DeviceOps>()
            ShellEvents(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                send = ShellEventHub::send,
                syncChanged = { device.syncStatus().let { it.pullSeq to it.view.status.pendingCount } },
                syncStatus = { device.syncStatus().toJson() },
            ).also { ev ->
                ev.watch(get<SyncStatusRepository>().status)
                get<NetworkMonitor>().onConnectivity = ev::onConnectivity
                ShellEventHub.events = ev
            }
        }
        single<DictEvents> {
            DictEvents(CoroutineScope(SupervisorJob() + Dispatchers.IO), get<DictOps>(), ShellEventHub::send)
                .also { it.start(); ShellEventHub.dict = it }
        }
        // SHELL-L4: the `bulkCards` `query` selector over the replica (the app driver, as the legacy card search)
        single<CardQuery> { ReplicaCardQuery(get<SqlDriver>(named("syncAppDriver")), get<AppDatabase>(), get<InstalledDictionary>()) }
        single<LocalWrites<SqlLearningTables>> {
            val koin = getKoin()
            LocalWrites(get<SqlDelightDeviceStore>(), get<SyncClient>().minter) {
                koin.get<SyncTriggers>().localChange()
                ShellEventHub.onLocalWrite()
            }
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
