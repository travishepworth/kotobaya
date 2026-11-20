package kotobaya.android.dict

/** The answer of the full lookup (SHELL.md L-7 steps 1-4) for one entry or kanji. */
sealed interface Resolved<out T> {
    /** Steps 1-3 found it. */
    data class Found<T>(val value: T) : Resolved<T>

    /** A known miss: the package is authoritative, or a negative result stands. `404` / `422 not_found`. */
    data object NotFound : Resolved<Nothing>

    /** Not on this device and not a known miss (enqueued for gap fill): `503 shell_dict_missing`. */
    data object NotOnDevice : Resolved<Nothing>
}

/**
 * THE dictionary lookup of SHELL.md rule L-7 (SHELL-L9, C-31): installed package -> sidecar cache -> miss. [entry] and
 * [kanji] are steps 1-2 only: everything that renders or schedules uses them. [resolveEntry] / [resolveKanji] (dictionary
 * reads, `createNote`) add the known-miss rules and enqueue a real miss on [queue]. No method touches the network.
 * [InstalledDictionary] with a cache attached answers the same steps 1-2 for its existing readers.
 *
 * [remote] is unused since C-31 (the queue fetches); the parameter stays so that existing constructors compile.
 */
class DictLookup(
    private val source: PackageSource,
    private val cache: DictGapCache,
    @Suppress("unused") private val remote: DictGapRemote?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val queue: GapFillQueue = GapFillQueue.None,
) {
    fun entry(seq: Long): DictEntryDetail? = source.packageEntry(seq) ?: cache.entry(seq)

    fun kanji(literal: String): KanjiDetail? = source.packageKanji(literal) ?: cache.kanji(literal)

    /**
     * L-7 "Authoritative": scope `all`, the last known server release is known, and the installed release is not
     * older than it. Only then does a miss mean "no such entry".
     */
    fun isAuthoritative(): Boolean {
        val installed = runCatching { source.info() }.getOrNull() ?: return false
        val server = cache.serverReleaseId() ?: return false
        return installed.scope == "all" && installed.releaseId >= server
    }

    /**
     * `resolveEntry` / `resolveKanji` (C-31): steps 1-2, then the known-miss rules; any other miss is enqueued for gap fill
     * (`urgent`: the miss came from a request) and answered at once as [Resolved.NotOnDevice]. Never touches the network.
     */
    fun resolveEntry(seq: Long): Resolved<DictEntryDetail> = resolve(GapRef.entry(seq)) { entry(seq) }

    fun resolveKanji(literal: String): Resolved<KanjiDetail> = resolve(GapRef.kanji(literal)) { kanji(literal) }

    private fun <T> resolve(ref: GapRef, local: () -> T?): Resolved<T> {
        local()?.let { return Resolved.Found(it) }
        if (isAuthoritative() || standingNegative(ref)) return Resolved.NotFound
        queue.request(ref.kind, ref.key, urgent = true)
        return Resolved.NotOnDevice
    }

    /** Enqueues [ref] for gap fill (the same reference twice is one fetch). */
    fun enqueue(ref: GapRef, urgent: Boolean) = queue.request(ref.kind, ref.key, urgent)

    /** True when [ref] is a known miss (authoritative package or a standing negative result): nothing to fetch. */
    fun isKnownMiss(ref: GapRef): Boolean = isAuthoritative() || standingNegative(ref)

    private fun standingNegative(ref: GapRef): Boolean {
        val row = cache.row(ref) ?: return false
        return row.body == null && row.releaseId == (cache.serverReleaseId() ?: 0L)
    }
}
