package kotobaya.android.search

import java.io.File
import kotlinx.coroutines.CancellationException
import kotobaya.android.net.ApiClient
import org.json.JSONArray

/** Fetches labels online for entries the device has no label for (GET /dict/headwords). Omitted seqs are absent. */
fun interface HeadwordFetcher {
    suspend fun fetch(seqs: List<Long>): Map<Long, EntryLabel>
}

/** [HeadwordFetcher] over `listDictHeadwords`; the seqs must number at most 500. */
fun ApiClient.asHeadwordFetcher(): HeadwordFetcher = HeadwordFetcher { seqs ->
    val items = get("/dict/headwords?seq=" + seqs.joinToString(",")).body.optJSONArray("items") ?: JSONArray()
    val out = HashMap<Long, EntryLabel>()
    for (i in 0 until items.length()) {
        val o = items.optJSONObject(i) ?: continue
        val gloss = o.optJSONArray("glosses")?.optString(0).orEmpty()
        out[o.getLong("seq")] = EntryLabel(o.optString("headword"), o.optString("headword_reading"), gloss)
    }
    out
}

/**
 * App-local (not synced, not the dictionary package) cache of labels fetched online (AND-DECK-LABELS), persisted as
 * tab-separated lines in [file]. Lookups are in-memory and batched; [file] may be null (memory only).
 */
class EntryLabelCache(private val file: File?, private val fetcher: HeadwordFetcher?) : EntryLabeler {
    private val map = HashMap<Long, EntryLabel>()
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            val seq = p.getOrNull(0)?.toLongOrNull()
            if (p.size == 4 && seq != null) map[seq] = EntryLabel(p[1], p[2], p[3])
        }
    }

    @Synchronized
    override fun labels(seqs: Collection<Long>): Map<Long, EntryLabel> {
        ensureLoaded()
        return seqs.toSet().mapNotNull { s -> map[s]?.let { s to it } }.toMap()
    }

    /**
     * Fetches the [seqs] not cached yet, in batches of at most [BATCH], and persists them. True when the cache
     * gained entries. A failed batch is skipped (retried on the next call); seqs the server omits stay uncached.
     */
    suspend fun fetchMissing(seqs: Collection<Long>): Boolean {
        val f = fetcher ?: return false
        val missing = missingOf(seqs)
        var added = false
        for (batch in missing.chunked(BATCH)) {
            val got = try {
                f.fetch(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            val fresh = got.filterKeys { it in batch }
            if (fresh.isEmpty()) continue
            store(fresh)
            added = true
        }
        return added
    }

    @Synchronized
    private fun missingOf(seqs: Collection<Long>): List<Long> {
        ensureLoaded()
        return seqs.toSet().filter { it !in map }
    }

    @Synchronized
    private fun store(fresh: Map<Long, EntryLabel>) {
        map.putAll(fresh)
        val f = file ?: return
        fun clean(t: String) = t.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        val text = map.entries.joinToString("\n") { (k, v) -> "$k\t${clean(v.headword)}\t${clean(v.reading)}\t${clean(v.gloss)}" }
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) f.writeText(text)
        }
    }

    companion object {
        const val BATCH = 500
    }
}

/** This labeler, then [second] for the seqs this one lacks. */
fun EntryLabeler.then(second: EntryLabeler): EntryLabeler = EntryLabeler { seqs ->
    val a = labels(seqs)
    val rest = seqs.filter { it !in a }
    if (rest.isEmpty()) a else a + second.labels(rest)
}
