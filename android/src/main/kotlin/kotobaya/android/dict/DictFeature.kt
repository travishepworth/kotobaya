package kotobaya.android.dict

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import kotobaya.android.db.DbThreadGuard
import kotobaya.android.dict.ui.DictSettingsSection
import org.koin.compose.getKoin
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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
import java.util.concurrent.TimeUnit
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import kotobaya.db.app.AppDatabase
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

    /** Daily check. Safe to call on every app start. */
    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<DictUpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
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

private fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private val dictModule = module {
    single { DictStore(File(get<Context>().filesDir, "dict")) }
    // BUG-8 review: main-thread reads of the dictionary file are reported like the app database's
    single { InstalledDictionary(get(), get<DbThreadGuard>()) }
    single<DictRemote> {
        val ctx = get<Context>()
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        HttpDictRemote(
            ApiClient({ settings.serverBaseUrl }, { tokens.token }, { isOnline(ctx) }),
            { settings.serverBaseUrl }, { tokens.token },
        )
    }
    single {
        val settings = get<SettingsStore>()
        val db = get<AppDatabase>()
        DictInstaller(get(), get(), { settings.dictionaryScope }, db)
    }
}

@Composable
private fun DictSettingsRoute() {
    val koin = getKoin()
    val context = LocalContext.current.applicationContext
    val vm: DictSettingsViewModel = viewModel {
        DictSettingsViewModel(
            AppDictSettingsPort(koin.get(), koin.get(), koin.get(), koin.get(), ensurePeriodic = { DictWork.ensurePeriodic(context) }),
        )
    }
    val state by vm.state.collectAsState()
    DictSettingsSection(state, vm)
}

val dictFeature = FeatureEntry(
    id = "dict",
    module = dictModule,
    settingsSections = listOf(SettingsSection("dict", R.string.dict_section_title) { DictSettingsRoute() }),
)
