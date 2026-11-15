package kotobaya.android.review

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotobaya.android.review.ui.ReviewScreen
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
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File

/** Adapts T304a's loader to the session's queue seam. */
fun LocalQueueLoader.asQueueSource(deckId: String? = null): QueueSource = object : QueueSource {
    override fun load(newShownThisSession: Int) = load(newShownThisSession, deckId).queue

    override fun loadSession(newShownThisSession: Int, keepGoing: Boolean): SessionQueue {
        val loaded = load(newShownThisSession, deckId, keepGoing)
        return SessionQueue(
            loaded.queue,
            loaded.input.limits.burySiblings,
            moreAvailable = loaded.queue.moreAvailable,
            dayStartMs = loaded.input.day.startMs,
            dayEndMs = loaded.input.day.endMs,
        )
    }

    override fun forDeck(deckId: String): QueueSource = asQueueSource(deckId)
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
        single { ReviewDueSignal() } bind kotobaya.android.notify.DueListener::class
        single<ReviewBackend<*>> {
            val client = get<SyncClient>()
            val triggers = get<SyncTriggers>()
            val ctx = get<Context>()
            val store = ReviewPrefsStore { ctx.getSharedPreferences("kotobaya-review", Context.MODE_PRIVATE) }
            val koin = getKoin()
            val db = get<kotobaya.db.app.AppDatabase>()
            val renderData = InstalledRenderData(get<kotobaya.android.dict.InstalledDictionary>())
            val syncStore = client.store
            ReviewBackend(
                reads = object : ReviewReads<kotobaya.android.sync.SqlLearningTables> {
                    override fun <R> read(block: (kotobaya.sync.client.store.StoreTransaction<kotobaya.android.sync.SqlLearningTables>) -> R): R =
                        syncStore.read(block)
                },
                dictionary = renderData,
                kanji = renderData,
                kanjiWords = InstalledKanjiWords(get<kotobaya.android.dict.InstalledDictionary>()),
                extrasIndex = SqlBackExtrasIndex(get<app.cash.sqldelight.db.SqlDriver>(org.koin.core.qualifier.named("syncAppDriver"))),
                store = client.store,
                minter = client.minter,
                queue = get<LocalQueueLoader>().asQueueSource(),
                prefs = store::read,
                blobs = BlobStoreResolver(get()),
                dueChanged = get<ReviewDueSignal>().flow,
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

/** Relays the shared due-trigger (sync pulled ops, app open, alarms) to open review sessions. */
class ReviewDueSignal : kotobaya.android.notify.DueListener {
    private val shared = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val flow: kotlinx.coroutines.flow.Flow<Unit> get() = shared

    override fun onDueChanged() {
        shared.tryEmit(Unit)
    }
}

/**
 * The review screen's glue: a [ReviewViewModel] per [key] (the Review tab uses none; a deck's study page passes one
 * per visit), the session started while composed and ended when it leaves, like the old composition-scoped one.
 * [onSessionEnd] null tells the sync triggers a session ended (the Review tab).
 */
@Composable
fun ReviewRoute(deckId: String? = null, key: String? = null, onSessionEnd: (() -> Unit)? = null, keepGoing: Boolean = false) {
    val koin = getKoin()
    val vm: ReviewViewModel = viewModel(key = key) {
        ReviewViewModel(
            koin.getOrNull<ReviewBackend<*>>(),
            deckId,
            keepGoing,
            onSessionEnd ?: { koin.getOrNull<SyncTriggers>()?.reviewSessionEnded(); Unit },
        )
    }
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    DisposableEffect(vm) {
        vm.enter()
        // AND-KEEPGOING-FIX: a configuration change (rotation, dark mode, ...) is not leaving the screen; the session
        // (and its keep-going mode) carries on in the retained view model.
        onDispose { vm.leave(changingConfigurations = activity?.isChangingConfigurations == true) }
    }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(vm, owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refreshIfEmpty()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val state by vm.state.collectAsState()
    ReviewScreen(state, vm)
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
