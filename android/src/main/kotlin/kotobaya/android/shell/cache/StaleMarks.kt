package kotobaya.android.shell.cache

/**
 * The K-4 invalidation alone, straight on `response_cache`: what marks entries stale when no [ResponseCache] exists in
 * the process (a background sync with no WebView, LF-A2b). No fetch, no warm pass: the next cycle with a cache warms.
 */
class StaleMarks(private val store: ResponseCacheStore) : CacheHooks {
    override fun onReplicaChanged(tables: Set<String>?) {
        val ops = if (tables == null) {
            ResponseCache.STALE_WHEN.filterValues { it.isNotEmpty() }.keys
        } else {
            ResponseCache.STALE_WHEN.filterValues { t -> t.any { it in tables } }.keys
        }
        store.markStale(ops)
    }

    override fun onAllStale() = store.markAllStale()

    override fun onCycleSucceeded() = Unit
}
