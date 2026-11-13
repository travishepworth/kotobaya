package kotobaya.android.media

/** Why a blob is held (blob_cache.reason). */
enum class BlobReason(val db: String) {
    CARD("card"), MEDIA("media"), COVER("cover");

    companion object {
        fun of(db: String): BlobReason = entries.firstOrNull { it.db == db } ?: CARD
    }
}

enum class BlobState(val db: String) {
    WANTED("wanted"), CACHED("cached"), MISSING("missing");

    companion object {
        fun of(db: String): BlobState = entries.firstOrNull { it.db == db } ?: WANTED
    }
}

/** One blob_cache row as the planner sees it. */
data class CacheEntry(
    val hash: String,
    val sizeBytes: Long?,
    val state: BlobState,
    val reason: BlobReason,
    val requestedAt: Long,
    val lastUsedAt: Long?,
)

/** An audio blob a card needs, and when that card is due (epoch ms). */
data class AudioNeed(val hash: String, val dueAtMs: Long)

enum class SkipReason { TOO_LARGE_FOR_BUDGET, BUDGET_EXHAUSTED }

data class Skipped(val hash: String, val reason: SkipReason)

/** What to do: [want] to download (in priority order), [evict] to delete, [skipped] with why. */
data class PrefetchPlan(val want: List<String>, val evict: List<String>, val skipped: List<Skipped>)

/**
 * Pure prefetch planning. Blobs needed by cards due within the window are protected from eviction and are wanted in
 * due order. Cached card audio is evicted least-recently-used first (never used = oldest) only when needed to make
 * room, or when the budget was shrunk below what is held. Other reasons (media, cover) count toward the budget but are
 * never evicted here. A blob whose known size exceeds the whole budget is skipped. Unknown sizes plan as 0; the
 * downloader enforces the remaining budget from Content-Length.
 */
object PrefetchPlanner {
    private const val DAY_MS = 86_400_000L

    fun plan(
        needs: List<AudioNeed>,
        cache: List<CacheEntry>,
        nowMs: Long,
        windowDays: Int,
        budgetBytes: Long,
    ): PrefetchPlan {
        val horizon = nowMs + windowDays.toLong() * DAY_MS
        val inWindow = needs.filter { it.dueAtMs <= horizon }.sortedBy { it.dueAtMs }
        val protectedHashes = inWindow.map { it.hash }.toSet()
        val byHash = cache.associateBy { it.hash }

        var used = cache.filter { it.state == BlobState.CACHED }.sumOf { it.sizeBytes ?: 0L }
        val evictable = cache
            .filter { it.state == BlobState.CACHED && it.reason == BlobReason.CARD && it.hash !in protectedHashes }
            .sortedWith(compareBy<CacheEntry>({ it.lastUsedAt ?: Long.MIN_VALUE }, { it.requestedAt }, { it.hash }))
            .toMutableList()
        val evict = mutableListOf<String>()
        fun evictOne(): Boolean {
            val e = evictable.removeFirstOrNull() ?: return false
            used -= e.sizeBytes ?: 0L
            evict += e.hash
            return true
        }

        // Budget shrunk: shed LRU unprotected audio until under it.
        while (used > budgetBytes && evictOne()) { /* keep shedding */ }

        val want = mutableListOf<String>()
        val skipped = mutableListOf<Skipped>()
        for (hash in inWindow.map { it.hash }.distinct()) {
            val e = byHash[hash]
            if (e?.state == BlobState.CACHED || e?.state == BlobState.MISSING) continue
            val size = e?.sizeBytes ?: 0L
            if (size > budgetBytes) {
                skipped += Skipped(hash, SkipReason.TOO_LARGE_FOR_BUDGET)
                continue
            }
            while (used + size > budgetBytes && evictOne()) { /* make room */ }
            if (used + size > budgetBytes) {
                skipped += Skipped(hash, SkipReason.BUDGET_EXHAUSTED)
                continue
            }
            used += size
            want += hash
        }
        return PrefetchPlan(want, evict, skipped)
    }
}
