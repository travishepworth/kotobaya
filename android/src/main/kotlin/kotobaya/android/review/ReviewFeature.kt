package kotobaya.android.review

import android.content.Context
import androidx.compose.runtime.Composable
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.media.BlobStore
import kotobaya.android.media.isBlobHash
import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.Routes
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.sync.SyncBootstrap
import kotobaya.android.sync.SyncClient
import kotobaya.android.sync.SyncTriggers
import org.koin.compose.getKoin
import org.koin.dsl.module
import java.io.File

/** Adapts T304a's loader to the session's queue seam. */
fun LocalQueueLoader.asQueueSource(): QueueSource = object : QueueSource {
    override fun load(newShownThisSession: Int) = load(newShownThisSession, null).queue

    override fun forDeck(deckId: String): QueueSource = QueueSource { newShown -> load(newShown, deckId).queue }
}

/** [BlobResolver] over T310's [BlobStore]: a published blob file, and `touch` on play. */
class BlobStoreResolver(private val store: BlobStore, private val clock: () -> Long = System::currentTimeMillis) : BlobResolver {
    override fun file(sha256: String): File? = if (isBlobHash(sha256)) store.file(sha256).takeIf { it.isFile } else null

    override fun played(sha256: String) {
        if (isBlobHash(sha256)) runCatching { store.touch(sha256, clock()) }
    }
}

/**
 * Replaces the shell's Review placeholder. [ReviewBackend] is wired over T303c's store, minter, startup gate
 * and sync triggers.
 */
val reviewFeature = FeatureEntry(
    id = "review",
    module = module {
        single<ReviewBackend<*>> {
            val client = get<SyncClient>()
            val triggers = get<SyncTriggers>()
            val ctx = get<Context>()
            val store = ReviewPrefsStore(ctx.getSharedPreferences("kotobaya-review", Context.MODE_PRIVATE))
            val koin = getKoin()
            val db = get<kotobaya.db.app.AppDatabase>()
            ReviewBackend(
                store = client.store,
                minter = client.minter,
                queue = get<LocalQueueLoader>().asQueueSource(),
                prefs = store::read,
                blobs = BlobStoreResolver(get()),
                awaitReady = { koin.get<SyncBootstrap>().ready.await() },
                syncNow = { triggers.background(kotobaya.android.sync.SyncTrigger.MANUAL) },
                nextStudyDayStart = { at ->
                    val raw = db.localQueueQueries.schedulerSettings().executeAsList().associate { it.key to it.value_ }
                    val st = kotobaya.android.queue.QueueSettings.from(raw)
                    kotobaya.android.queue.studyDayOf(at, st.timezone, st.dayRolloverHour).day.endMs
                },
            )
        }
    },
    routes = listOf(
        NavRoute(Routes.REVIEW, R.string.nav_review, topLevel = true) { ReviewRoute() },
    ),
)

@Composable
private fun ReviewRoute() {
    val koin = getKoin()
    val backend = koin.getOrNull<ReviewBackend<*>>()
    ReviewScreen(backend, onSessionEnd = { koin.getOrNull<SyncTriggers>()?.reviewSessionEnded() })
}
