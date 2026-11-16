package kotobaya.android.dict

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Why a referenced entry or kanji is in neither the installed package nor the sidecar cache after a run. */
enum class MissingReason {
    /** The server answered `404` under its current release (a negative result, L-7). */
    SERVER_NOT_FOUND,

    /** Not asked this run: over the per-run budget. The next run continues. */
    BUDGET,

    /** Not asked or not answered: offline, unreachable, rate-limited, a server error, or no release on the server. */
    FETCH_FAILED,
}

/**
 * The outcome of one gap-fill run. Every reference in [referenced] is in exactly one of [installed], [cached] or
 * [missing] (nothing is dropped silently).
 */
data class GapFillReport(
    val serverReleaseId: Long?,
    val referenced: List<GapRef>,
    val installed: Set<GapRef>,
    val cached: Set<GapRef>,
    val missing: Map<GapRef, MissingReason>,
    val fetched: Int,
    val pruned: Int,
) {
    val counts: GapFillCounts get() = GapFillCounts(cached.size, missing.size)

    /** Something is left that a later run may still get (budget or a failed fetch). */
    val retryable: Boolean get() = missing.values.any { it != MissingReason.SERVER_NOT_FOUND }
}

/**
 * Gap fill (SHELL-L9, SHELL.md L-7 and 10.7): after a sync, every dictionary entry and kanji that local notes and deck
 * items reference (cards hang off notes) and that the installed package lacks is fetched from the server one by one
 * (`getDictEntry` / `getKanji`) and kept in the [DictGapCache], so review rendering finds it offline.
 *
 * Per run: the server's release is asked first (and recorded: L-7 "last known server release"); cache rows the
 * installed package now holds are dropped; missing references are fetched in priority order (notes, then deck items
 * by deck priority and position, then kanji), at most [budget] requests, stored [batchSize] at a time with [pauseMs]
 * between batches; rows fetched under an older server release are refreshed with what budget is left (they stay
 * readable until then). Any failure other than `404` ends the fetching for this run; the rest waits for the next.
 * Runs are serialised.
 */
class DictGapFill(
    private val source: PackageSource,
    private val cache: DictGapCache,
    private val remote: DictGapRemote,
    private val budget: Int = DEFAULT_BUDGET,
    private val batchSize: Int = 50,
    private val pauseMs: Long = 200,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()

    /** True when a run would do anything: references or releases changed, work was left over, or a recheck is due. */
    fun isDue(): Boolean {
        if (cache.state(DictGapCache.RETRY_KEY) == "1") return true
        if (cache.state(DictGapCache.STAMP_KEY) != stamp()) return true
        val checked = cache.state(DictGapCache.CHECKED_KEY)?.toLongOrNull() ?: return true
        return nowMs() - checked >= RECHECK_MS
    }

    /** One run. Never throws except for cancellation. */
    suspend fun run(): GapFillReport = lock.withLock {
        cache.install()
        val st = Run()
        val server: Long? = try {
            val id = remote.serverRelease()
            if (id == null) st.failure = "the server has no dictionary release" else cache.recordServerRelease(id)
            id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            st.failure = "release check failed: $e"
            null
        }
        val release = server ?: cache.serverReleaseId() ?: 0L

        val entryRefs = referencedEntries().map { GapRef.entry(it) }
        phase(GapKind.ENTRY, entryRefs, release, server != null, st)
        val kanjiRefs = (referencedKanjiLiterals() + kanjiOfCachedEntries(st.cached)).distinct().map { GapRef.kanji(it) }
        phase(GapKind.KANJI, kanjiRefs, release, server != null, st)

        val report = GapFillReport(
            server, entryRefs.distinct() + kanjiRefs, st.installed, st.cached, st.missing, st.fetched, st.pruned,
        )
        cache.putState(
            mapOf(
                DictGapCache.CACHED_KEY to report.cached.size.toString(),
                DictGapCache.MISSING_KEY to report.missing.size.toString(),
                DictGapCache.RETRY_KEY to if (report.retryable) "1" else "0",
                DictGapCache.STAMP_KEY to stamp(),
                DictGapCache.CHECKED_KEY to nowMs().toString(),
            ),
        )
        log(
            "dict gap fill: ${report.referenced.size} referenced, ${report.installed.size} installed, ${report.cached.size} " +
                "cached, ${report.missing.size} missing, ${report.fetched} fetched, ${report.pruned} pruned" +
                (st.failure?.let { "; stopped: $it" } ?: ""),
        )
        report
    }

    private class Run {
        val installed = HashSet<GapRef>()
        val cached = LinkedHashSet<GapRef>()
        val missing = LinkedHashMap<GapRef, MissingReason>()
        var fetched = 0
        var pruned = 0
        var failure: String? = null
        var requests = 0
    }

    private suspend fun phase(kind: GapKind, refsInOrder: List<GapRef>, release: Long, releaseKnown: Boolean, run: Run) {
        val refs = refsInOrder.distinct()
        val rows = cache.rows(kind).associateBy { it.ref }
        val present = present(kind, (refs.map { it.key } + rows.keys.map { it.key }).distinct())

        // Rows the installed package now holds are dropped (a newer package gained them).
        val prune = rows.keys.filter { it.key in present }
        cache.delete(prune)
        run.pruned += prune.size

        val absent = ArrayList<GapRef>()
        val stale = ArrayList<GapRef>()
        for (ref in refs) {
            if (ref.key in present) {
                run.installed += ref
                continue
            }
            val row = rows[ref]
            when {
                row == null -> absent += ref
                row.body != null -> {
                    run.cached += ref
                    if (releaseKnown && row.releaseId != release) stale += ref
                }
                row.releaseId == release -> run.missing[ref] = MissingReason.SERVER_NOT_FOUND // standing negative result
                else -> absent += ref // a negative result of another release: ask again
            }
        }

        val buffer = ArrayList<GapRow>()
        suspend fun flush() {
            if (buffer.isEmpty()) return
            cache.put(buffer.toList())
            buffer.clear()
            if (pauseMs > 0) delay(pauseMs)
        }
        for (ref in absent + stale) {
            val refresh = ref in run.cached
            if (run.failure != null || run.requests >= budget) {
                if (!refresh) run.missing[ref] = if (run.failure != null) MissingReason.FETCH_FAILED else MissingReason.BUDGET
                continue
            }
            run.requests++
            val answer = try {
                when (kind) {
                    GapKind.ENTRY -> remote.entry(ref.key.toLong())
                    GapKind.KANJI -> remote.kanji(ref.key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                run.failure = "${kind.tag} ${ref.key}: $e"
                if (!refresh) run.missing[ref] = MissingReason.FETCH_FAILED
                continue
            }
            when (answer) {
                is RemoteAnswer.Found -> {
                    val body = runCatching { DictJson.stripOverlay(answer.body) }.getOrNull()
                    if (body == null) {
                        run.failure = "${kind.tag} ${ref.key}: unreadable body"
                        if (!refresh) run.missing[ref] = MissingReason.FETCH_FAILED
                        continue
                    }
                    buffer += GapRow(ref, release, body, nowMs())
                    run.fetched++
                    run.cached += ref
                }
                RemoteAnswer.NotFound -> {
                    buffer += GapRow(ref, release, null, nowMs())
                    run.cached -= ref
                    run.missing[ref] = MissingReason.SERVER_NOT_FOUND
                }
            }
            if (buffer.size >= batchSize) flush()
        }
        if (buffer.isNotEmpty()) cache.put(buffer.toList()).also { buffer.clear() }
    }

    private fun present(kind: GapKind, keys: List<String>): Set<String> = when (kind) {
        GapKind.ENTRY -> source.presentEntries(keys.mapNotNull { it.toLongOrNull() }).mapTo(HashSet()) { it.toString() }
        GapKind.KANJI -> source.presentKanji(keys)
    }

    /** Entry seqs of live notes (oldest first), then of live deck items in live decks (deck priority, position). */
    private fun referencedEntries(): List<Long> =
        cache.column("SELECT entry_seq FROM note WHERE entry_seq IS NOT NULL AND deleted_at IS NULL ORDER BY created_at, id") {
            it.getLong(0) ?: 0L
        } + cache.column(
            "SELECT i.entry_seq FROM deck_item i JOIN deck d ON d.id = i.deck_id WHERE i.entry_seq IS NOT NULL " +
                "AND i.deleted_at IS NULL AND d.deleted_at IS NULL ORDER BY d.priority, d.id, i.position, i.item_key",
        ) { it.getLong(0) ?: 0L }

    private fun referencedKanjiLiterals(): List<String> =
        cache.column("SELECT kanji_literal FROM note WHERE kanji_literal IS NOT NULL AND deleted_at IS NULL ORDER BY created_at, id") {
            it.getString(0).orEmpty()
        }.filter { it.isNotEmpty() }

    /** Kanji of the referenced entries held in the cache (the package has the kanji of its own entries). */
    private fun kanjiOfCachedEntries(cached: Set<GapRef>): List<String> = cache.rows(GapKind.ENTRY)
        .filter { it.ref in cached && it.body != null }
        .flatMap { row ->
            runCatching {
                val a = JSONObject(row.body.orEmpty()).optJSONArray("kanji")
                if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            }.getOrDefault(emptyList())
        }

    /** Changes when the references, the installed package or the last known server release change. */
    private fun stamp(): String = listOf(
        (runCatching { source.info()?.releaseId }.getOrNull() ?: 0L).toString(),
        (cache.serverReleaseId() ?: 0L).toString(),
        cache.column(
            "SELECT count(entry_seq) || ':' || total(entry_seq) || ':' || count(kanji_literal) FROM note WHERE deleted_at IS NULL",
        ) { it.getString(0).orEmpty() }.firstOrNull().orEmpty(),
        cache.column("SELECT count(entry_seq) || ':' || total(entry_seq) FROM deck_item WHERE deleted_at IS NULL") {
            it.getString(0).orEmpty()
        }.firstOrNull().orEmpty(),
    ).joinToString("|")

    companion object {
        /** Requests per run ("a few hundred"); the rest waits for the next sync. */
        const val DEFAULT_BUDGET: Int = 300

        /** Ask the server for its release at least this often even when nothing else changed. */
        const val RECHECK_MS: Long = 6 * 60 * 60 * 1000L
    }
}
