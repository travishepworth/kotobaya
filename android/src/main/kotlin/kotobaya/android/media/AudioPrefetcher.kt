package kotobaya.android.media

import android.content.SharedPreferences

/** Minimal key/value seam so settings are testable without Android. */
interface KeyValue {
    fun int(key: String, default: Int): Int
    fun bool(key: String, default: Boolean): Boolean
    fun put(key: String, value: Int)
    fun put(key: String, value: Boolean)
}

class SharedPrefsKeyValue(private val p: SharedPreferences) : KeyValue {
    override fun int(key: String, default: Int) = p.getInt(key, default)
    override fun bool(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun put(key: String, value: Int) = p.edit().putInt(key, value).apply()
    override fun put(key: String, value: Boolean) = p.edit().putBoolean(key, value).apply()
}

/** Prefetch settings (device-local). Budget default 500 MB, window default 2 days, unmetered only. */
class MediaPrefs(private val kv: KeyValue) {
    var prefetchDays: Int
        get() = kv.int("prefetch_days", 2)
        set(v) = kv.put("prefetch_days", v)

    var budgetMb: Int
        get() = kv.int("audio_budget_mb", 500)
        set(v) = kv.put("audio_budget_mb", v)

    var allowMetered: Boolean
        get() = kv.bool("allow_metered", false)
        set(v) = kv.put("allow_metered", v)
}

/** Where the card audio hashes come from (due and soon-due cards; T304a's due window). */
fun interface AudioNeedsSource {
    fun needs(nowMs: Long, windowDays: Int): List<AudioNeed>
}

data class PrefetchSummary(
    val skippedMetered: Boolean = false,
    val downloaded: Int = 0,
    val missing: Int = 0,
    val failed: Int = 0,
    val evicted: Int = 0,
    val skipped: List<Skipped> = emptyList(),
)

/** One prefetch run: plan, evict, download, record. */
class AudioPrefetcher(
    private val store: BlobStore,
    private val source: AudioNeedsSource,
    private val downloader: BlobDownloader,
    private val prefs: MediaPrefs,
    private val isMetered: () -> Boolean,
    private val nowMs: () -> Long,
) {
    fun run(): PrefetchSummary {
        if (isMetered() && !prefs.allowMetered) return PrefetchSummary(skippedMetered = true)
        val now = nowMs()
        val budget = prefs.budgetMb.toLong() * 1_048_576L
        val plan = PrefetchPlanner.plan(source.needs(now, prefs.prefetchDays), store.entries(), now, prefs.prefetchDays, budget)
        plan.evict.forEach { store.evict(it) }
        plan.want.forEach { store.want(it, BlobReason.CARD, now) }

        var downloaded = 0
        var missing = 0
        var failed = 0
        for (hash in plan.want) {
            val used = store.entries().filter { it.state == BlobState.CACHED }.sumOf { it.sizeBytes ?: 0L }
            when (val r = downloader.download(hash, (budget - used).coerceAtLeast(0))) {
                is DownloadResult.Stored -> { store.markCached(hash, r.size, r.contentType, now); downloaded++ }
                DownloadResult.NotFound, DownloadResult.HashMismatch -> { store.markMissing(hash); missing++ }
                DownloadResult.OverBudget, is DownloadResult.Failed -> failed++
            }
        }
        return PrefetchSummary(false, downloaded, missing, failed, plan.evict.size, plan.skipped)
    }
}
