package kotobaya.android.review

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.UuidV7Source
import java.io.File
import java.util.TimeZone
import kotlin.random.Random

/** Where cached blobs live: the file of a `blob_cache` row in state `cached`, else null (T310 supplies the real one). */
interface BlobResolver {
    fun file(sha256: String): File?

    /** Called when the blob is played (LRU bookkeeping). */
    fun played(sha256: String) {}
}

/** A resolver from a lambda (tests). */
fun BlobResolver(f: (String) -> File?): BlobResolver = object : BlobResolver {
    override fun file(sha256: String): File? = f(sha256)
}

/**
 * Everything the review screen needs from the sync layer. T303c's Koin module registers one
 * (`single<ReviewBackend<*>> { ... }`) once its SQLDelight store exists; until then the review screen
 * shows a "not available" state.
 */
class ReviewBackend<L : LearningTables>(
    val store: DeviceStore<L>,
    val minter: OpMinter<L>,
    val queue: QueueSource,
    val prefs: () -> ReviewPrefs,
    val blobs: BlobResolver = BlobResolver { null },
    val dictionary: DictionaryLookup = DictionaryLookup { null },
    /** Suspends until the startup replay of SYNC.md 10.5 has committed (the readiness gate); returns at once if none. */
    val awaitReady: suspend () -> Unit = {},
    /** Starts a sync cycle (the 'Sync' prompt); null hides the button. */
    val syncNow: (() -> Unit)? = null,
    /** Emits when the due state may have changed (a sync pulled ops, the app opened, the day rolled): an Empty session reloads. */
    val dueChanged: Flow<Unit> = emptyFlow(),
    val clock: () -> Long = System::currentTimeMillis,
    /** First instant of the study day after the one containing the argument (bury horizon, T304c). Default: next local midnight. */
    val nextStudyDayStart: (Long) -> Long = { at ->
        val tz = TimeZone.getDefault()
        java.time.LocalDate.ofEpochDay(Math.floorDiv(at + tz.getOffset(at), 86_400_000L)).plusDays(1)
            .atStartOfDay(tz.toZoneId()).toInstant().toEpochMilli()
    },
) {
    private val ids = UuidV7Source(Random.Default)

    fun newSession(deckId: String? = null): ReviewSession<L> = ReviewSession(
        store = store,
        minter = minter,
        queue = if (deckId == null) queue else queue.forDeck(deckId),
        clock = clock,
        utcOffsetMin = { at -> TimeZone.getDefault().getOffset(at) / 60_000 },
        newReviewId = ids::newOpId,
        prefs = prefs,
        dictionary = dictionary,
    )
}
