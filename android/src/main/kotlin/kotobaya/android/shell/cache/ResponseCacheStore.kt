package kotobaya.android.shell.cache

import kotobaya.db.app.AppDatabase

/** One row of `response_cache` (SHELL.md K-1; contracts/db SCHEMA.md 5.3). */
data class CacheEntry(
    val key: String,
    val operationId: String,
    val path: String,
    /** The canonical query (K-1), `""` for none. */
    val query: String,
    val status: Int,
    val contentType: String,
    val body: String,
    val sizeBytes: Long,
    val etag: String?,
    /** Device clock, ms: fetched or last confirmed unchanged. */
    val fetchedAt: Long,
    /** Device clock, ms: last hit or store (LRU order). */
    val lastUsedAt: Long,
    val stale: Boolean,
)

/** What a warm pass or a stale revalidation fetches: the parts of a key without the stored answer. */
data class CacheTarget(val operationId: String, val path: String, val query: String) {
    val key: String get() = CacheKey.of(operationId, path, query)
}

/** The device table `response_cache`. Device-local, not learning data: no op, no `pending_op` row. */
interface ResponseCacheStore {
    fun get(key: String): CacheEntry?

    /** Inserts or replaces [entry]. */
    fun put(entry: CacheEntry)

    fun touch(key: String, lastUsedAt: Long)

    /** A revalidation found the same bytes (or `304`): new [fetchedAt] and [stale]; the body is untouched. */
    fun confirm(key: String, fetchedAt: Long, stale: Boolean)

    fun delete(key: String)

    fun deleteAll()

    fun markAllStale()

    fun markStale(operationIds: Collection<String>)

    /** Sum of `size_bytes`. */
    fun totalSize(): Long

    /** `(key, size_bytes)` by ascending `last_used_at`, ties by key (K-4 eviction order). */
    fun leastRecentlyUsed(): List<Pair<String, Long>>

    /** Every stale entry, most recently used first (K-4 warm pass, step 2). */
    fun staleTargets(): List<CacheTarget>

    /** Live deck ids of type `media` or `list`, most recently updated first (K-4 warm pass seeds). */
    fun warmDeckIds(): List<String>
}

/** [ResponseCacheStore] over the app database (`ResponseCache.sq`). */
class SqlResponseCacheStore(db: AppDatabase) : ResponseCacheStore {
    private val q = db.responseCacheQueries

    override fun get(key: String): CacheEntry? = q.rcGet(key).executeAsOneOrNull()?.let {
        CacheEntry(
            key = it.key,
            operationId = it.op,
            path = it.path,
            query = it.query,
            status = it.status.toInt(),
            contentType = it.content_type,
            body = it.body,
            sizeBytes = it.size_bytes,
            etag = it.etag,
            fetchedAt = it.fetched_at,
            lastUsedAt = it.last_used_at,
            stale = it.stale != 0L,
        )
    }

    override fun put(entry: CacheEntry) {
        q.rcUpsert(
            entry.key, entry.operationId, entry.path, entry.query, entry.status.toLong(), entry.contentType, entry.body,
            entry.sizeBytes, entry.etag, entry.fetchedAt, entry.lastUsedAt, if (entry.stale) 1L else 0L,
        )
    }

    override fun touch(key: String, lastUsedAt: Long) = q.rcTouch(lastUsedAt, key)

    override fun confirm(key: String, fetchedAt: Long, stale: Boolean) = q.rcConfirm(fetchedAt, if (stale) 1L else 0L, key)

    override fun delete(key: String) = q.rcDelete(key)

    override fun deleteAll() = q.rcDeleteAll()

    override fun markAllStale() = q.rcMarkAllStale()

    override fun markStale(operationIds: Collection<String>) {
        if (operationIds.isNotEmpty()) q.rcMarkOpsStale(operationIds)
    }

    override fun totalSize(): Long = q.rcTotalSize().executeAsOne()

    override fun leastRecentlyUsed(): List<Pair<String, Long>> = q.rcByLeastRecentlyUsed().executeAsList().map { it.key to it.size_bytes }

    override fun staleTargets(): List<CacheTarget> = q.rcStaleByMostRecentlyUsed().executeAsList().map { CacheTarget(it.op, it.path, it.query) }

    override fun warmDeckIds(): List<String> = q.rcWarmDecks().executeAsList()
}
