package kotobaya.android.shell.cache

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotobaya.android.shell.api.ApiOperation
import kotobaya.android.shell.api.ApiOperations
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.Problem
import kotobaya.android.shell.api.ShellHeaders
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.parseQuery
import kotobaya.android.shell.events.ShellEvents
import kotobaya.android.sync.NetworkMonitor
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.store.PendingState

/** Where the replica and the sync engine tell the response cache what happened (SHELL.md K-4); [ShellEvents] calls these. */
interface CacheHooks {
    /** Learning data changed (a local write, a pull page that applied ops). [tables] null = unknown: every replica table. */
    fun onReplicaChanged(tables: Set<String>? = null)

    /** A rebuild or a log reset (SYNC.md 10.3, 6.2): every entry is stale, none is deleted. */
    fun onAllStale()

    /** A sync cycle ended successfully: the warm pass. */
    fun onCycleSucceeded()
}

/** The payload of `cache.updated` (SHELL.md 2.6). */
data class CacheUpdate(val operationId: String, val path: String, val query: String, val status: Int)

/**
 * The `cached` class (SHELL.md 4.4, rules K-1 … K-4; L-9: the request never waits).
 *
 * [answer] reads `response_cache` only: a hit is answered at once (`kotobaya-cached-at`) and may start one background
 * revalidation; a miss is `503 shell_uncached` at once and may start one background fetch. Fetches run on [scope]
 * through [upstream] (the device token, P-1's 12 s); their outcome replaces, confirms or deletes the entry and calls
 * [onUpdated] only when what the next request gets changed. [onCycleSucceeded] runs the warm pass (≤ 4 at a time,
 * ≤ 64 per pass), [onReplicaChanged] marks entries stale by the K-4 table, [clear] empties the cache (pairing).
 */
class ResponseCache(
    private val store: ResponseCacheStore,
    private val upstream: Upstream,
    private val scope: CoroutineScope,
    private val isOnline: () -> Boolean,
    /** `pending_op` holds a `pending` row (K-2: an entry fetched then stays stale). */
    private val hasPendingOps: () -> Boolean,
    private val onUpdated: (CacheUpdate) -> Unit,
    private val newRequestId: () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val logError: (String, Throwable) -> Unit = { _, _ -> },
) : CacheHooks {
    private val lock = Any()
    private val inFlight = HashSet<String>()
    private val lastFinished = HashMap<String, Long>()
    private var warming = false

    /** Bumped by [clear] under [lock]: a fetch that started before belongs to the old pairing and stores nothing. */
    private var generation = 0L

    /** Stale marks so far, per operation and for all (under [lock]): a mark after a fetch started keeps its `stale` (K-4). */
    private val marksByOp = HashMap<String, Long>()
    private var marksAll = 0L

    private fun marksOf(operationId: String): Long = marksAll + (marksByOp[operationId] ?: 0L)

    /** K-2 / K-3 for one request of a `cached` operation. Never waits for the network. */
    fun answer(request: ShellRequest, operation: ApiOperation, requestId: String): ShellResponse {
        val query = CacheKey.canonicalQuery(request.query)
        val target = CacheTarget(operation.operationId, request.path, query)
        val key = target.key
        val now = nowMs()
        val entry = store.get(key)
        if (entry == null) {
            if (isOnline()) startFetch(target)
            return uncached().toResponse(request.path, requestId)
        }
        store.touch(key, now)
        if ((entry.stale || isOld(now - entry.fetchedAt)) && isOnline() && !recentlyFetched(key, now)) startFetch(target)
        return ShellResponse(
            entry.status,
            mapOf(ShellHeaders.CONTENT_TYPE to entry.contentType, CACHED_AT to formatAt(entry.fetchedAt)),
            entry.body,
        )
    }

    override fun onReplicaChanged(tables: Set<String>?) {
        val ops = if (tables == null) REPLICA_DEPENDENT else STALE_WHEN.filterValues { t -> t.any { it in tables } }.keys
        markStale(ops)
    }

    override fun onAllStale() = synchronized(lock) {
        marksAll++
        guarded("mark all stale") { store.markAllStale() }
    }

    /** K-4: a successful `putParseOverride` / `deleteParseOverride` through the proxy. */
    fun onParseOverrideWritten() = markStale(PARSE_OVERRIDE_DEPENDENT)

    /** Counted and written under [lock], so that a [settle] either sees the mark or is followed by it. */
    private fun markStale(ops: Collection<String>) = synchronized(lock) {
        ops.forEach { marksByOp[it] = (marksByOp[it] ?: 0L) + 1 }
        guarded("mark stale") { store.markStale(ops) }
    }

    /** `unpairShell`, `pairShell`: the entries belong to the old pairing. Under [lock]: no fetch of it stores after this. */
    fun clear() = synchronized(lock) {
        generation++
        guarded("clear") { store.deleteAll() }
    }

    override fun onCycleSucceeded() {
        if (!isOnline()) return
        synchronized(lock) {
            if (warming) return
            warming = true
        }
        scope.launch {
            try {
                warm()
            } finally {
                synchronized(lock) { warming = false }
            }
        }
    }

    /** The K-4 warm pass, awaited (tests call it directly). */
    suspend fun warm() {
        val now = nowMs()
        val targets = LinkedHashMap<String, CacheTarget>()
        val seeds = SEED_PATHS.map { (op, path) -> CacheTarget(op, path, "") } +
            runCatchingLogged("warm decks") { store.warmDeckIds() }.orEmpty().map { CacheTarget(DECK_COVERAGE, "/api/v1/decks/$it/coverage", "") }
        for (t in seeds) {
            val e = runCatchingLogged("warm read") { store.get(t.key) }
            if (e == null || e.stale || isOld(now - e.fetchedAt)) targets[t.key] = t
        }
        runCatchingLogged("warm stale") { store.staleTargets() }.orEmpty().forEach { targets.putIfAbsent(it.key, it) }
        val permits = Semaphore(WARM_PARALLEL)
        coroutineScope {
            targets.values.take(WARM_MAX).forEach { t ->
                launch { permits.withPermit { if (isOnline() && claim(t.key)) fetchClaimed(t) } }
            }
        }
    }

    /** Starts one background fetch of [target] unless one is in flight (claimed before it is launched). */
    private fun startFetch(target: CacheTarget) {
        if (claim(target.key)) scope.launch { fetchClaimed(target) }
    }

    private fun claim(key: String): Boolean = synchronized(lock) { inFlight.add(key) }

    private fun recentlyFetched(key: String, now: Long): Boolean = synchronized(lock) {
        key in inFlight || lastFinished[key]?.let { now - it in 0 until REFETCH_GAP_MS } == true
    }

    /** One fetch of [target], already [claim]ed; outcome by the tables of K-2 (entry) and K-3 (no entry). Releases the claim. */
    private suspend fun fetchClaimed(target: CacheTarget) {
        val key = target.key
        try {
            val (gen, marksAtStart) = synchronized(lock) { generation to marksOf(target.operationId) }
            val operation = ApiOperations.byId[target.operationId] ?: return
            val pendingAtStart = runCatchingLogged("pending check") { hasPendingOps() } ?: true
            val before = store.get(key)
            val headers = LinkedHashMap<String, String>()
            headers["accept"] = ShellHeaders.JSON
            before?.etag?.let { headers[IF_NONE_MATCH] = it }
            val request = ShellRequest("GET", target.path, parseQuery(target.query), headers)
            val response = try {
                upstream.forward(request, operation, newRequestId())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("cache fetch ${target.operationId} failed", e)
                return
            }
            settle(target, response, gen, marksAtStart, pendingAtStart)?.let(onUpdated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("cache fetch ${target.operationId} failed", e)
        } finally {
            synchronized(lock) {
                inFlight.remove(key)
                lastFinished[key] = nowMs()
            }
        }
    }

    /**
     * The outcome of one fetch, under [lock] (the generation check and the write are one step against [clear] and the
     * stale marks). `stale` stays 1 when `pending_op` held a row at the start or the entry was marked since. Returns
     * the `cache.updated` to send (outside the lock), or null.
     */
    private fun settle(target: CacheTarget, response: ShellResponse, gen: Long, marksAtStart: Long, pendingAtStart: Boolean): CacheUpdate? =
        synchronized(lock) {
            if (gen != generation) return null
            settleLocked(target, response, pendingAtStart || marksOf(target.operationId) != marksAtStart)
        }

    private fun settleLocked(target: CacheTarget, response: ShellResponse, stale: Boolean): CacheUpdate? {
        val key = target.key
        val now = nowMs()
        val existing = store.get(key)
        val status = response.status
        return when {
            status in 200..299 -> {
                val body = response.body ?: return null
                val type = response.headers[ShellHeaders.CONTENT_TYPE] ?: return null
                if (!isJson(type)) return null
                val size = LocalApi.utf8Length(body)
                if (size > MAX_ENTRY_BYTES) return null
                if (existing != null && existing.body == body) {
                    store.confirm(key, now, stale)
                    return null
                }
                store.put(
                    CacheEntry(
                        key, target.operationId, target.path, target.query, status, type, body, size,
                        response.headers[ETAG], now, now, stale,
                    ),
                )
                evict(key)
                CacheUpdate(target.operationId, target.path, target.query, status)
            }
            status == 304 -> {
                if (existing != null) store.confirm(key, now, stale)
                null
            }
            status == 404 || status == 410 -> {
                existing?.let { store.delete(key) }
                CacheUpdate(target.operationId, target.path, target.query, status)
            }
            existing == null && status in FINAL_REFUSALS -> CacheUpdate(target.operationId, target.path, target.query, status)
            else -> null // 401 (9.2), 5xx, 429, 503 shell_offline: the stored answer keeps being served
        }
    }

    /** K-4 size: least recently used first, ties by key; [justWritten] last. */
    private fun evict(justWritten: String) {
        var total = store.totalSize()
        if (total <= MAX_TOTAL_BYTES) return
        for ((key, size) in store.leastRecentlyUsed()) {
            if (total <= MAX_TOTAL_BYTES) return
            if (key == justWritten) continue
            store.delete(key)
            total -= size
        }
        if (total > MAX_TOTAL_BYTES) store.delete(justWritten)
    }

    private fun guarded(what: String, block: () -> Unit) {
        runCatchingLogged(what, block)
    }

    private inline fun <T> runCatchingLogged(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logError("response cache: $what failed", e)
        null
    }

    companion object {
        const val MAX_AGE_MS: Long = 60_000
        const val REFETCH_GAP_MS: Long = 5_000
        const val MAX_ENTRY_BYTES: Long = 4L * 1024 * 1024
        const val MAX_TOTAL_BYTES: Long = 16L * 1024 * 1024
        const val WARM_PARALLEL: Int = 4
        const val WARM_MAX: Int = 64
        const val CACHED_AT: String = "kotobaya-cached-at"
        const val IF_NONE_MATCH: String = "if-none-match"
        const val ETAG: String = "etag"
        private const val DECK_COVERAGE = "getDeckCoverage"

        private val FINAL_REFUSALS = setOf(400, 403, 422)

        /** K-4 warm pass step 1 (besides `getDeckCoverage` per deck), each with the empty query. */
        val SEED_PATHS: List<Pair<String, String>> = listOf(
            "listDeckLists" to "/api/v1/deck-lists",
            "listParseOverrides" to "/api/v1/parse-overrides",
            "getStatsForecast" to "/api/v1/stats/forecast",
            "getStatsKnownWords" to "/api/v1/stats/known-words",
            "getStatsCalibration" to "/api/v1/stats/calibration",
        )

        private val SENTENCE_TABLES = setOf("media", "note", "word_status", "sentence_grammar_span", "grammar_point")

        /** K-4 invalidation: the replica tables each `cached` operation depends on. `listParseOverrides`: none. */
        val STALE_WHEN: Map<String, Set<String>> = mapOf(
            "listDeckLists" to setOf("deck"),
            "getDeckCoverage" to setOf("deck", "deck_item", "note", "card", "review_log", "word_status", "media", "settings"),
            "getStatsForecast" to setOf("card", "review_log", "fsrs_params", "settings"),
            "getStatsKnownWords" to setOf("word_status", "note", "card", "review_log", "settings"),
            "getStatsCalibration" to setOf("review_log", "card", "fsrs_params"),
            "listSentences" to SENTENCE_TABLES,
            "listMediaSentences" to SENTENCE_TABLES,
            "getSentence" to SENTENCE_TABLES,
            "listGrammarExamples" to SENTENCE_TABLES,
            "getMediaCoverage" to setOf("media", "note", "card", "review_log", "word_status", "settings"),
            "listParseOverrides" to emptySet(),
        )

        private val REPLICA_DEPENDENT: Set<String> = STALE_WHEN.filterValues { it.isNotEmpty() }.keys

        /** K-4: what a parse-override write through the proxy makes stale. */
        val PARSE_OVERRIDE_DEPENDENT: Set<String> = setOf("listParseOverrides", "listSentences", "listMediaSentences", "getSentence")

        /** The proxied writes that trigger [onParseOverrideWritten]. */
        val PARSE_OVERRIDE_WRITES: Set<String> = setOf("putParseOverride", "deleteParseOverride")

        private val AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** RFC 3339 UTC with milliseconds (SHELL.md 3.3 `kotobaya-cached-at`). */
        fun formatAt(ms: Long): String = AT.format(Instant.ofEpochMilli(ms))

        /** SHELL.md section 5 `shell_uncached`: no `retry-after`. */
        fun uncached(): Problem = Problem(503, "shell_uncached", "Not downloaded yet", "This has not been downloaded to the device yet.")

        private fun isJson(contentType: String): Boolean =
            contentType.substringBefore(';').trim().equals(ShellHeaders.JSON, ignoreCase = true)

        /**
         * The production cache over the app's services (Koin, as the route handlers get theirs): `response_cache` in
         * [AppDatabase], `NetworkMonitor`, pending ops from the [SqlDelightDeviceStore], `cache.updated` through
         * [ShellEvents] (which also gets the hooks). Null when [services] lacks any of them (kernel tests).
         */
        fun fromServices(
            services: ShellServices,
            upstream: Upstream,
            newRequestId: () -> String,
            logError: (String, Throwable) -> Unit,
        ): ResponseCache? {
            val parts = try {
                Parts(
                    services.get(AppDatabase::class),
                    services.get(SqlDelightDeviceStore::class),
                    services.get(NetworkMonitor::class),
                    services.get(ShellEvents::class),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (services !== ShellServices.None) logError("response cache unavailable", e)
                return null
            }
            val cache = ResponseCache(
                store = SqlResponseCacheStore(parts.db),
                upstream = upstream,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                isOnline = { parts.network.isOnline() },
                hasPendingOps = { parts.device.read { it.outbox.inState(PendingState.PENDING, 1).isNotEmpty() } },
                onUpdated = parts.events::onCacheUpdated,
                newRequestId = newRequestId,
                logError = logError,
            )
            parts.events.cacheHooks = cache
            return cache
        }

        private class Parts(val db: AppDatabase, val device: SqlDelightDeviceStore, val network: NetworkMonitor, val events: ShellEvents)

        /** K-2: old enough to revalidate; a negative age is a clock set back (10.17). */
        private fun isOld(age: Long): Boolean = age >= MAX_AGE_MS || age < 0
    }
}
