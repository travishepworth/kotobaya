package kotobaya.android.dict

import kotlinx.coroutines.CancellationException

/** The answer of the full lookup (SHELL.md L-7 steps 1-4) for one entry or kanji. */
sealed interface Resolved<out T> {
    /** Steps 1-3 found it. */
    data class Found<T>(val value: T) : Resolved<T>

    /** A known miss: the package is authoritative, or a negative result stands. `404` / `422 not_found`. */
    data object NotFound : Resolved<Nothing>

    /** Not on this device and the server could not be asked: `503 shell_dict_missing`. */
    data object NotOnDevice : Resolved<Nothing>
}

/**
 * THE dictionary lookup of SHELL.md rule L-7 (SHELL-L9): installed package -> sidecar cache -> (only [resolveEntry] /
 * [resolveKanji], for `local_or_proxy` dictionary reads and `createNote`) the server -> miss. [entry] and [kanji] are
 * steps 1-2 only and never touch the network: everything that renders or schedules uses them. [InstalledDictionary]
 * with a cache attached answers the same steps 1-2 for its existing readers.
 *
 * [remote] null = no server configured (the device is treated as offline).
 */
class DictLookup(
    private val source: PackageSource,
    private val cache: DictGapCache,
    private val remote: DictGapRemote?,
    private val nowMs: () -> Long = System::currentTimeMillis,
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

    suspend fun resolveEntry(seq: Long): Resolved<DictEntryDetail> =
        resolve(GapRef.entry(seq), { entry(seq) }, { remote?.entry(seq) }, DictJson::entry)

    suspend fun resolveKanji(literal: String): Resolved<KanjiDetail> =
        resolve(GapRef.kanji(literal), { kanji(literal) }, { remote?.kanji(literal) }, DictJson::kanji)

    private suspend fun <T> resolve(
        ref: GapRef,
        local: () -> T?,
        ask: suspend () -> RemoteAnswer?,
        parse: (String) -> T,
    ): Resolved<T> {
        local()?.let { return Resolved.Found(it) }
        if (isAuthoritative() || standingNegative(ref)) return Resolved.NotFound
        val answer = try {
            ask() ?: return Resolved.NotOnDevice
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Resolved.NotOnDevice
        }
        val release = cache.serverReleaseId() ?: 0L
        return when (answer) {
            is RemoteAnswer.Found -> {
                val body = runCatching { DictJson.stripOverlay(answer.body) }.getOrNull() ?: return Resolved.NotOnDevice
                val value = runCatching { parse(body) }.getOrNull() ?: return Resolved.NotOnDevice
                cache.put(listOf(GapRow(ref, release, body, nowMs())))
                Resolved.Found(value)
            }
            RemoteAnswer.NotFound -> {
                cache.put(listOf(GapRow(ref, release, null, nowMs())))
                Resolved.NotFound
            }
        }
    }

    private fun standingNegative(ref: GapRef): Boolean {
        val row = cache.row(ref) ?: return false
        return row.body == null && row.releaseId == (cache.serverReleaseId() ?: 0L)
    }
}
