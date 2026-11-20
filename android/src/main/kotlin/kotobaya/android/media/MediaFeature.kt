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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.queue.EntryMaterializer
import kotobaya.android.queue.KanjiSource
import kotobaya.android.queue.LoadGate
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.sync.NetworkMonitor
import kotobaya.android.sync.SqlLearningTables
import kotobaya.db.app.AppDatabase
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** WorkManager scheduling: unmetered network by default, any network when the setting allows metered. */
object AudioPrefetchWork {
    private const val NAME = "audio-prefetch"

    private const val LATE_NAME = "audio-prefetch-late"

    /** Call after a sync completes and on app open. Keeps a queued run. */
    fun enqueue(context: Context, allowMetered: Boolean) {
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request(allowMetered, 0))
    }

    /** ADR-047 / SCHEMA.md 6.6 "Device" rule 1: a pass 30 s after a local `getReviewQueue` that may have materialized cards. */
    fun enqueueLate(context: Context, allowMetered: Boolean) {
        WorkManager.getInstance(context).enqueueUniqueWork(LATE_NAME, ExistingWorkPolicy.KEEP, request(allowMetered, LATE_DELAY_S))
    }

    private fun request(allowMetered: Boolean, delaySeconds: Long): androidx.work.OneTimeWorkRequest {
        val net = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
        return OneTimeWorkRequestBuilder<AudioPrefetchWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
    }

    const val LATE_DELAY_S = 30L
}

/** The local `getReviewQueue` asks for a prefetch pass (a pass already queued is kept). */
fun interface AudioPrefetchTrigger {
    fun afterQueueLoad()
}

class AudioPrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val koin = GlobalContext.get()
        // resolve first (pairs and note blobs become wanted), then download; a failed resolve is repeated by the next trigger
        runCatching { koin.get<WordAudioPrefetch<SqlLearningTables>>().run() }
        val s = koin.get<AudioPrefetcher>().run()
        if (s.failed > 0) Result.retry() else Result.success()
    }
}

private val mediaModule = module {
    single { BlobStore(get<SqlDriver>(named("syncAppDriver")), File(get<Context>().filesDir, "blobs")) }
    single { MediaPrefs(SharedPrefsKeyValue(get<Context>().getSharedPreferences("kotobaya-media", Context.MODE_PRIVATE))) }
    // Card audio hashes come from the rendered cards of the card cache (due and soon-due), via the sync store.
    single { TtsAudioTable(get<SqlDriver>(named("syncAppDriver"))) }
    single<WordAudioLookup> {
        val koin = getKoin()
        DeviceWordAudioLookup(get(), get(), { koin.get<NetworkMonitor>().isOnline() })
    }
    single<AudioPrefetchTrigger> {
        val ctx = get<Context>()
        val prefs = get<MediaPrefs>()
        AudioPrefetchTrigger { runCatching { AudioPrefetchWork.enqueueLate(ctx, prefs.allowMetered) } }
    }
    // ADR-047: P(now) over the replica; part (b) is the queue build at now with materializing switched off
    single {
        val koin = getKoin()
        val dict = get<InstalledDictionary>()
        WordAudioSet(
            get<kotobaya.android.sync.SyncClient>().store,
            entry = { seq -> dict.entry(seq) },
            newWork = { now ->
                val notes = koin.getOrNull<EntryMaterializer>()
                val loader = LocalQueueLoader(
                    get<AppDatabase>(), { now }, headwordOf = { seq -> runCatching { dict.entry(seq)?.headword }.getOrNull() },
                    gate = LoadGate(), materializer = notes?.let(::ReadOnlyMaterializer),
                    kanji = runCatching { koin.get<KanjiSource>() }.getOrDefault(KanjiSource.NONE),
                )
                val q = loader.load(keepGoing = false).queue
                NewWork(q.items.filter { it.isNew }.map { it.cardId }, q.toMaterialize.map { it.entrySeq })
            },
        )
    }
    single {
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        WordAudioPrefetch(
            get<WordAudioSet<SqlLearningTables>>(), get(), get(),
            HttpSpeechResolver({ settings.serverBaseUrl }, { tokens.token }, ApiClient.defaultHttp()),
            System::currentTimeMillis,
        )
    }
    // C-31: the native speech resolver behind the local `resolveSpeech`
    single<SpeechResolveQueue> {
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        val http = HttpSpeechResolver({ settings.serverBaseUrl }, { tokens.token }, ApiClient.defaultHttp())
        BackgroundSpeechResolveQueue(
            CoroutineScope(SupervisorJob() + Dispatchers.IO), get(), get(), UrgentSpeechResolver { pairs, urgent -> http.resolve(pairs, urgent) },
        )
    }
    single<AudioNeedsSource> {
        val cards = CardAudioNeeds(get<kotobaya.android.sync.SyncClient>().store)
        val tts = TtsAudioNeeds(get<WordAudioSet<SqlLearningTables>>(), get())
        AudioNeedsSource { now, days -> cards.needs(now, days) + tts.needs(now, days) }
    }
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

val mediaFeature = FeatureEntry(
    id = "media",
    module = mediaModule,
)

