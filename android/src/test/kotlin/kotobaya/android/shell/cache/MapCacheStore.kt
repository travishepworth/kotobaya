package kotobaya.android.shell.cache

/** A thread-safe in-memory [ResponseCacheStore]; [onGet] runs before every [get] (tests interleave there). */
class MapCacheStore : ResponseCacheStore {
    private val rows = LinkedHashMap<String, CacheEntry>()

    @Volatile
    var onGet: (String) -> Unit = {}

    override fun get(key: String): CacheEntry? {
        onGet(key)
        return synchronized(rows) { rows[key] }
    }

    override fun put(entry: CacheEntry) {
        synchronized(rows) { rows[entry.key] = entry }
    }

    override fun touch(key: String, lastUsedAt: Long) = update(key) { it.copy(lastUsedAt = lastUsedAt) }

    override fun confirm(key: String, fetchedAt: Long, stale: Boolean) = update(key) { it.copy(fetchedAt = fetchedAt, stale = stale) }

    override fun delete(key: String) {
        synchronized(rows) { rows.remove(key) }
    }

    override fun deleteAll() = synchronized(rows) { rows.clear() }

    override fun markAllStale() = synchronized(rows) { rows.replaceAll { _, e -> e.copy(stale = true) } }

    override fun markStale(operationIds: Collection<String>) =
        synchronized(rows) { rows.replaceAll { _, e -> if (e.operationId in operationIds) e.copy(stale = true) else e } }

    override fun totalSize(): Long = synchronized(rows) { rows.values.sumOf { it.sizeBytes } }

    override fun leastRecentlyUsed(): List<Pair<String, Long>> =
        synchronized(rows) { rows.values.sortedWith(compareBy({ it.lastUsedAt }, { it.key })).map { it.key to it.sizeBytes } }

    override fun staleTargets(): List<CacheTarget> = synchronized(rows) {
        rows.values.filter { it.stale }.sortedByDescending { it.lastUsedAt }.map { CacheTarget(it.operationId, it.path, it.query) }
    }

    override fun warmDeckIds(): List<String> = emptyList()

    fun peek(key: String): CacheEntry? = synchronized(rows) { rows[key] }

    private fun update(key: String, f: (CacheEntry) -> CacheEntry) {
        synchronized(rows) { rows[key]?.let { rows[key] = f(it) } }
    }
}
