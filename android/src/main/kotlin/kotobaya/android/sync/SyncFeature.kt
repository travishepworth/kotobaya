package kotobaya.android.sync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.work.WorkManager
import app.cash.sqldelight.db.SqlDriver
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotobaya.android.di.FeatureEntry
import kotobaya.android.media.AudioPrefetchWork
import kotobaya.android.media.MediaPrefs
import kotobaya.android.net.MetaLimitsStore
import kotobaya.android.net.ServerReachability
import kotobaya.android.notify.DueTrigger
import kotobaya.android.pairing.PairingRepository
import kotobaya.android.pairing.TokenStore
import kotobaya.android.push.PushSyncTrigger
import kotobaya.android.settings.SettingsStore
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.DeviceDiagnostics
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.StartupReplay
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.SyncEngine
import kotobaya.sync.client.protocol.SyncTransport
import kotobaya.sync.client.rebuild.DeviceSyncCycle
import kotobaya.sync.client.rebuild.Rebuild
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.UuidV7Source
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The T303a/T303b sync client wired over one store, as the rest of the app uses it: [minter] for
 * every learning-data change (T304b and others), [cycle] for the coordinator. One [DeviceCardCache]
 * (and so one [DeviceDiagnostics]) is shared by the applier, the startup replay and the cycle, as
 * the T303b review asked.
 */
class SyncClient(
    val store: SqlDelightDeviceStore,
    transport: SyncTransport,
    clock: Clock = Clock { System.currentTimeMillis() },
    diagnosticsSink: (DeviceDiagnostics.Refusal) -> Unit = {},
) {
    val diagnostics: DeviceDiagnostics = DeviceDiagnostics(diagnosticsSink)
    val cache: DeviceCardCache = DeviceCardCache(diagnostics = diagnostics)
    val startup: StartupReplay = StartupReplay(cache)
    val applier: DeviceOpApplier<SqlLearningTables> = DeviceOpApplier(cache, startup)
    val engine: SyncEngine<SqlLearningTables> = SyncEngine(store, transport, applier, clock)
    val rebuild: Rebuild<SqlLearningTables> = Rebuild(store, engine, applier)
    val cycle: DeviceSyncCycle<SqlLearningTables> = DeviceSyncCycle(store, engine, rebuild, startup)
    val minter: OpMinter<SqlLearningTables> = OpMinter(store, applier, clock, UuidV7Source(SecureRandom().asKotlinRandom()))
}

/** The backoff count in `sync_state` (device-local key, never synced), so a new process continues it. */
class StoreFailureCounter(private val store: SqlDelightDeviceStore) : FailureCounter {
    override var failures: Int
        get() = store.read { it.syncState.get(KEY) }?.toIntOrNull() ?: 0
        set(v) = store.transaction { if (v == 0) it.syncState.remove(KEY) else it.syncState.put(KEY, v.toString()) }

    companion object {
        /** Device-local `sync_state` key (the `client.*` namespace of T303a's SyncStateKeys). */
        const val KEY: String = "client.sync_failures"
    }
}

/**
 * How other features ask for a sync (SYNC.md 6.1: after a review session, after local changes, on
 * demand). Never blocks the caller.
 */
class SyncTriggers(
    private val coordinator: SyncCoordinator,
    private val scope: CoroutineScope,
    private val workManager: () -> WorkManager?,
) {
    /** After a review session (T304b): sync now in-process. */
    fun reviewSessionEnded() = background(SyncTrigger.REVIEW_SESSION)

    /** After any local change: an expedited one-shot, so it is pushed even if the app goes away. */
    fun localChange() {
        runCatching { workManager()?.let(SyncWork::requestNow) }
    }

    /** The user's 'sync now' (T303e); returns the outcome. */
    suspend fun syncNow(): SyncOutcome = coordinator.sync(SyncTrigger.MANUAL)

    /** Fire-and-forget sync for [trigger]. */
    fun background(trigger: SyncTrigger) {
        scope.launch { coordinator.sync(trigger) }
    }
}

/**
 * Runs at process start (Koin `createdAtStart`): the startup replay of SYNC.md 10.5 first (the
 * coordinator waits for [ready] before any cycle), then the first status, the periodic work, the
 * connectivity trigger and the app-open trigger (first activity started).
 */
class SyncBootstrap(
    app: Application,
    private val client: SyncClient,
    private val coordinator: SyncCoordinator,
    private val status: SyncStatusRepository,
    private val network: NetworkMonitor,
    private val triggers: SyncTriggers,
    scope: CoroutineScope,
    workManager: () -> WorkManager?,
) : Application.ActivityLifecycleCallbacks {
    val ready: CompletableDeferred<Unit> = CompletableDeferred()
    private var started = 0

    init {
        scope.launch {
            runCatching { client.startup.run(client.store) }
                .onFailure { Log.e(TAG, "startup replay failed; the applier retries it before the next apply", it) }
            ready.complete(Unit)
            status.refresh()
            runCatching { workManager()?.let(SyncWork::ensurePeriodic) }
        }
        scope.launch { coordinator.running.collect { status.onRunning(it) } }
        client.store.onCommit { scope.launch { status.refresh() } } // off the committing thread
        network.register { triggers.background(SyncTrigger.CONNECTIVITY) }
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) triggers.background(SyncTrigger.APP_OPEN)
    }

    override fun onActivityStopped(activity: Activity) {
        started = maxOf(0, started - 1)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val TAG = "KotobayaSync"
    }
}

private val SYNC_DRIVER = named("syncAppDriver")
private val SYNC_SCOPE = named("syncScope")

val syncModule = module {
    single<CoroutineScope>(SYNC_SCOPE) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    single<SqlDriver>(SYNC_DRIVER) { DurableDriver.open(androidContext()) }
    // Replaces appModule's AppDatabase so that every feature and the sync store share ONE driver,
    // opened with WAL + synchronous=FULL (T303c; see HANDOFF).
    single<AppDatabase> { AppDatabase(get<SqlDriver>(SYNC_DRIVER)) }
    single { SqlDelightDeviceStore(get(SYNC_DRIVER), get()) }
    single {
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        HttpSyncTransport({ settings.serverBaseUrl }, { tokens.token }, HttpSyncTransport.storedLimits(get<MetaLimitsStore>()))
    }
    single {
        SyncClient(get(), get<HttpSyncTransport>(), diagnosticsSink = { r ->
            Log.e("KotobayaSync", "replay refused for card ${r.cardId}: ${r.error}")
        })
    }
    single { get<SyncClient>().minter }
    single { NetworkMonitor(androidContext()) }
    single { StoreFailureCounter(get()) }
    single {
        val pairing = get<PairingRepository>()
        val counter = get<StoreFailureCounter>()
        SyncStatusRepository(get(), { System.currentTimeMillis() }, { pairing.isPaired }, { counter.failures })
    }
    single {
        val ctx = androidContext()
        val client = get<SyncClient>()
        val pairing = get<PairingRepository>()
        val network = get<NetworkMonitor>()
        val reach = get<ServerReachability>()
        val status = get<SyncStatusRepository>()
        val koin = getKoin()
        SyncCoordinator(
            scope = get(SYNC_SCOPE),
            runCycle = { client.cycle.run() },
            isPaired = { pairing.isPaired },
            isOnline = { network.isOnline() },
            probe = { reach.probe() },
            counter = get<StoreFailureCounter>(),
            ready = { koin.get<SyncBootstrap>().ready.await() },
            onOutcome = { _, outcome, retryDelayMs ->
                status.onOutcome(outcome.asCycleResult(), unreachable = outcome is SyncOutcome.Unreachable)
                runCatching { SyncWork.applyOutcome(WorkManager.getInstance(ctx), outcome, retryDelayMs) }
                val r = (outcome as? SyncOutcome.Ran)?.result
                if (r is CycleResult.Completed && r.pulledOps > 0) koin.getOrNull<DueTrigger>()?.fire()
                prefetchAfterSync(outcome, { koin.get<MediaPrefs>().allowMetered }) { metered ->
                    runCatching { AudioPrefetchWork.enqueue(ctx, metered) }
                }
            },
        )
    }
    single { RejectedOpsStore(get()) }
    single {
        val ctx = androidContext()
        SyncTriggers(get(), get(SYNC_SCOPE)) { runCatching { WorkManager.getInstance(ctx) }.getOrNull() }
    }
    // Replaces push's no-op default: a `sync.changed` push runs a sync.
    single<PushSyncTrigger> {
        val triggers = get<SyncTriggers>()
        PushSyncTrigger { triggers.background(SyncTrigger.PUSH) }
    }
    single(createdAtStart = true) {
        val ctx = androidContext()
        SyncBootstrap(
            app = ctx as Application,
            client = get(),
            coordinator = get(),
            status = get(),
            network = get(),
            triggers = get(),
            scope = get(SYNC_SCOPE),
            workManager = { runCatching { WorkManager.getInstance(ctx) }.getOrNull() },
        )
    }
}

/** After a successful sync cycle (pulled card changes may name new audio) queue the audio prefetch (T310). */
internal fun prefetchAfterSync(outcome: SyncOutcome, allowMetered: () -> Boolean, enqueue: (allowMetered: Boolean) -> Unit) {
    if ((outcome as? SyncOutcome.Ran)?.result is CycleResult.Completed) enqueue(allowMetered())
}

/** THE registration for featureEntries. */
val syncFeature = FeatureEntry(id = "sync", module = syncModule)
