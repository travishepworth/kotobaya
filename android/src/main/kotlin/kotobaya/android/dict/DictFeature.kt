package kotobaya.android.dict

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotobaya.android.db.DbThreadGuard
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotobaya.android.media.BlobStore
import kotobaya.android.shell.ShellEventHub
import kotobaya.sync.client.store.bookkeeping
import java.util.concurrent.TimeUnit
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.db.app.AppDatabase
import app.cash.sqldelight.db.SqlDriver
import org.koin.core.qualifier.named
import org.koin.core.context.GlobalContext
import org.koin.dsl.module

/** WorkManager scheduling of the dictionary update (unique work, exponential backoff, network required). */
object DictWork {
    private const val ONE_SHOT = "dict-update"
    private const val PERIODIC = "dict-update-periodic"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Queues one update now (keeps a queued or running one). */
    fun enqueueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<DictUpdateWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.KEEP, req)
    }

    private const val AUTO = "dict-auto-install"

    /**
     * L-7 "Auto-install": re-evaluated hourly, and only while the network is unmetered (WorkManager holds the job back
     * otherwise). Safe to call on every app start.
     */
    fun ensureAutoInstall(context: Context) {
        val req = PeriodicWorkRequestBuilder<DictAutoInstallWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(AUTO, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Daily check. Safe to call on every app start. */
    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<DictUpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        ensureAutoInstall(context) // the same app-start hook (AppDictSettingsPort.startup) also arms the auto-install
    }
}

/** Runs [DictInstaller] from WorkManager; a failed run is retried with backoff. */
class DictUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = GlobalContext.get()
        val installer = koin.get<DictInstaller>()
        installer.startup()
        return when (installer.run()) {
            is InstallResult.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}

/** Evaluates the auto-install policy (SHELL.md L-7); the policy itself decides whether to do anything. */
class DictAutoInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = GlobalContext.get()
        runCatching { koin.get<DictInstaller>().startup() }
        return when (val o = koin.get<DictAutoInstall>().evaluate()) {
            is AutoInstallOutcome.Ran -> if (o.result is InstallResult.Failed) Result.retry() else Result.success()
            is AutoInstallOutcome.Skipped -> Result.success()
        }
    }
}

/** Device state of the dictionary choice the auto-install needs. */
class DictChoice(private val prefs: android.content.SharedPreferences) {
    /** `startDictInstall` with scope `top60k` is remembered as the user's choice; any other scope clears it. */
    var userChoseTop60k: Boolean
        get() = prefs.getBoolean(K_TOP60K, false)
        set(v) = prefs.edit().putBoolean(K_TOP60K, v).apply()

    var autoInstallFailedAtMs: Long?
        get() = if (prefs.contains(K_FAILED)) prefs.getLong(K_FAILED, 0L) else null
        set(v) = prefs.edit().apply { if (v == null) remove(K_FAILED) else putLong(K_FAILED, v) }.apply()

    private companion object {
        const val K_TOP60K = "chose_top60k"
        const val K_FAILED = "auto_failed_ms"
    }
}

private fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private const val DICT_API = "dictApi"
const val GAP_FILL_TAG: String = "KotobayaDictGap"

private val dictModule = module {
    single { DictStore(File(get<Context>().filesDir, "dict")) }
    // SHELL-L9: the sidecar cache of L-7 lives in the learning database (the sync store's driver)
    single { DictGapCache(get<SqlDelightDeviceStore>(), get<SqlDriver>(named("syncAppDriver"))) }
    // BUG-8 review: main-thread reads of the dictionary file are reported like the app database's.
    // With the cache attached, entry()/kanji() are L-7 steps 1-2 for every reader (review rendering included).
    single { InstalledDictionary(get(), get<DbThreadGuard>(), get<DictGapCache>()) }
    single(named(DICT_API)) {
        val ctx = get<Context>()
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        ApiClient({ settings.serverBaseUrl }, { tokens.token }, { isOnline(ctx) })
    }
    single<DictRemote> {
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        HttpDictRemote(get(named(DICT_API)), { settings.serverBaseUrl }, { tokens.token })
    }
    single<DictGapRemote> { HttpDictGapRemote(get(named(DICT_API))) }
    single<GapFillQueue> {
        BackgroundGapFillQueue(
            CoroutineScope(SupervisorJob() + Dispatchers.IO), get<InstalledDictionary>(), get(), get<DictGapRemote>(),
            onLanded = { ShellEventHub.dict?.poke() }, log = { android.util.Log.i(GAP_FILL_TAG, it) },
        )
    }
    single { DictLookup(get<InstalledDictionary>(), get(), get<DictGapRemote>(), queue = get<GapFillQueue>()) }
    single { DictChoice(get<Context>().getSharedPreferences("kotobaya-dict", Context.MODE_PRIVATE)) }
    single {
        val ctx = get<Context>()
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        val choice = get<DictChoice>()
        val installer = get<DictInstaller>()
        val dictStore = get<DictStore>()
        val koin = getKoin()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val env = object : AutoInstallEnv {
            override fun paired() = tokens.token != null

            override fun firstSyncDone() = koin.get<SqlDelightDeviceStore>().read { it.bookkeeping.lastSyncAt } != null

            override fun unmetered() =
                cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

            override fun installedScope() = runCatching { dictStore.info()?.scope }.getOrNull()

            override fun userChoseTop60k() = choice.userChoseTop60k

            override fun budgetBytes() = settings.storageBudgetMb.toLong() * 1_048_576L

            override fun usedBytes() = koin.get<BlobStore>().breakdown(dictStore.installedBytes()).totalBytes

            override fun installedDictionaryBytes() = dictStore.installedBytes()
        }
        DictAutoInstall(
            env, get<DictRemote>(),
            installRunning = { installer.state.value is InstallState.Running }, runInstall = { installer.run() },
            setScope = { settings.dictionaryScope = it },
            failedAtMs = { choice.autoInstallFailedAtMs }, recordFailure = { choice.autoInstallFailedAtMs = it },
            log = { android.util.Log.i(GAP_FILL_TAG, it) },
        )
    }
    single {
        DictGapFill(get<InstalledDictionary>(), get(), get(), log = { android.util.Log.i(GAP_FILL_TAG, it) })
    }
    single {
        val settings = get<SettingsStore>()
        val db = get<AppDatabase>()
        val cache = get<DictGapCache>()
        DictInstaller(get(), get(), { settings.dictionaryScope }, db, onServerRelease = cache::recordServerRelease)
    }
}

val dictFeature = FeatureEntry(
    id = "dict",
    module = dictModule,
)
