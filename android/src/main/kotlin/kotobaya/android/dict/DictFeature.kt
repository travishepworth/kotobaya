package kotobaya.android.dict

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.i18n.T
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import kotobaya.db.app.AppDatabase
import org.koin.compose.koinInject
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
    single { InstalledDictionary(get()) }
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

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

/** Settings > Offline storage: scope, installed release, sizes, "update now". */
@Composable
private fun DictSettings() {
    val ctx = LocalContext.current
    val store = koinInject<DictStore>()
    val installer = koinInject<DictInstaller>()
    val settings = koinInject<SettingsStore>()
    val dictionary = koinInject<InstalledDictionary>()
    val scope = rememberCoroutineScope()
    val state by installer.state.collectAsState()
    val generation by store.generation.collectAsState()
    var chosen by remember { mutableStateOf(settings.dictionaryScope) }
    var info by remember { mutableStateOf<PackageInfo?>(null) }
    var bytes by remember { mutableStateOf(0L) }
    LaunchedEffect(generation) {
        installer.startup()
        DictWork.ensurePeriodic(ctx)
        withContext(Dispatchers.IO) { store.info() to store.installedBytes() }.let { (i, b) -> info = i; bytes = b }
    }
    Column(Modifier.padding(8.dp)) {
        val i = info
        Text(
            if (i == null) T(R.string.dict_not_installed)
            else T(R.string.dict_installed, i.version, i.releaseId, i.scope),
        )
        Text(T(R.string.dict_size, mb(bytes)))
        Text(T(R.string.dict_scope_label))
        Row {
            for ((value, label) in listOf("top60k" to R.string.dict_scope_top60k, "all" to R.string.dict_scope_all)) {
                if (chosen == value) {
                    Button(onClick = {}) { Text(T(label)) }
                } else {
                    OutlinedButton(onClick = { chosen = value; settings.dictionaryScope = value }) { Text(T(label)) }
                }
            }
        }
        Button(
            onClick = { scope.launch { installer.run(); info = withContext(Dispatchers.IO) { store.info() } } },
            enabled = state !is InstallState.Running,
        ) { Text(T(R.string.dict_update_now)) }
        Text(statusText(state))
    }
    LaunchedEffect(state) {
        if (state is InstallState.Finished) info = withContext(Dispatchers.IO) { store.info().also { dictionary.info() } }
    }
}

@Composable
private fun statusText(state: InstallState): String = when (state) {
    is InstallState.Idle -> ""
    is InstallState.Running -> when (state.stage) {
        InstallState.Stage.CHECKING -> T(R.string.dict_state_checking)
        InstallState.Stage.DOWNLOADING -> T(R.string.dict_state_downloading, mb(state.doneBytes), mb(state.totalBytes))
        InstallState.Stage.VERIFYING -> T(R.string.dict_state_verifying)
        InstallState.Stage.APPLYING -> T(R.string.dict_state_applying)
    }
    is InstallState.Finished -> when (val r = state.result) {
        is InstallResult.NoRelease -> T(R.string.dict_result_no_release)
        is InstallResult.UpToDate, is InstallResult.DowngradeIgnored -> T(R.string.dict_result_up_to_date)
        is InstallResult.Installed -> T(R.string.dict_result_installed, r.releaseId)
        is InstallResult.NeedsNewerApp -> T(R.string.dict_result_needs_newer_app)
        is InstallResult.InsufficientSpace -> T(R.string.dict_result_no_space, mb(r.requiredBytes), mb(r.availableBytes))
        is InstallResult.Failed -> T(R.string.dict_result_failed, r.reason)
    }
}

val dictFeature = FeatureEntry(
    id = "dict",
    module = dictModule,
    settingsSections = listOf(SettingsSection("dict", R.string.dict_section_title) { DictSettings() }),
)
