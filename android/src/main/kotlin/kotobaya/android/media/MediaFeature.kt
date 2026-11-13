package kotobaya.android.media

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.di.FeatureEntry
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** WorkManager scheduling: unmetered network by default, any network when the setting allows metered. */
object AudioPrefetchWork {
    private const val NAME = "audio-prefetch"

    /** Call after a sync completes. Keeps a queued run. */
    fun enqueue(context: Context, allowMetered: Boolean) {
        val net = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
        val req = OneTimeWorkRequestBuilder<AudioPrefetchWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
    }
}

class AudioPrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = GlobalContext.get().get<AudioPrefetcher>().run()
        if (s.failed > 0) Result.retry() else Result.success()
    }
}

private val mediaModule = module {
    single { BlobStore(get<SqlDriver>(named("syncAppDriver")), File(get<Context>().filesDir, "blobs")) }
    single { MediaPrefs(SharedPrefsKeyValue(get<Context>().getSharedPreferences("kotobaya-media", Context.MODE_PRIVATE))) }
    // Card audio hashes are not derivable from the contract schema yet; until T304b supplies the source, nothing is wanted.
    single<AudioNeedsSource> { AudioNeedsSource { _, _ -> emptyList() } }
    single {
        val ctx = get<Context>()
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        AudioPrefetcher(
            get(), get(),
            BlobDownloader(get(), { settings.serverBaseUrl }, { tokens.token }, ApiClient.defaultHttp()),
            get(), { cm.isActiveNetworkMetered }, System::currentTimeMillis,
        )
    }
}

val mediaFeature = FeatureEntry(id = "media", module = mediaModule)
