package kotobaya.android.review

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
    val clock: () -> Long = System::currentTimeMillis,
) {
    private val ids = UuidV7Source(Random.Default)

    fun newSession(): ReviewSession<L> = ReviewSession(
        store = store,
        minter = minter,
        queue = queue,
        clock = clock,
        utcOffsetMin = { at -> TimeZone.getDefault().getOffset(at) / 60_000 },
        newReviewId = ids::newOpId,
        prefs = prefs,
        dictionary = dictionary,
    )
}
