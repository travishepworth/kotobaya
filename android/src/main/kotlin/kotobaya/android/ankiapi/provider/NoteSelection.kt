package kotobaya.android.ankiapi.provider

/**
 * Parses the `notes`/`notes_v2` selections AddContentApi sends for duplicate checks:
 * `mid=N` (or `mid:N` in the v1 search syntax), `csum in (a,b)` / `csum=N`, and
 * `sfld=?` / `sfld='x'` / `sfld="x"`, joined by `and`, in any combination.
 *
 * Returns [Parsed.All] for a blank selection and [Parsed.NotUnderstood] when nothing matched: the
 * provider then answers with no rows (so a client never sees a false duplicate) and logs it.
 */
internal object NoteSelection {
    sealed interface Parsed {
        data object All : Parsed

        data class Filter(val filter: NoteFilter) : Parsed

        data object NotUnderstood : Parsed
    }

    private val mid = Regex("""\bmid\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val csumIn = Regex("""\bcsum\s+in\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
    private val csumEq = Regex("""\bcsum\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val sfldArg = Regex("""\bsfld\s*=\s*\?""", RegexOption.IGNORE_CASE)
    private val sfldSingle = Regex("""\bsfld\s*=\s*'((?:[^']|'')*)'""", RegexOption.IGNORE_CASE)
    private val sfldDouble = Regex("""\bsfld\s*=\s*"((?:[^"]|"")*)"""", RegexOption.IGNORE_CASE)

    fun parse(selection: String?, args: Array<out String>?): Parsed {
        if (selection.isNullOrBlank()) return Parsed.All
        val modelId = mid.find(selection)?.groupValues?.get(1)?.toLongOrNull()
        val checksums = csumIn.find(selection)?.groupValues?.get(1)
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()
            ?: csumEq.find(selection)?.groupValues?.get(1)?.toLongOrNull()?.let { setOf(it) }
        val sortField = when {
            sfldArg.containsMatchIn(selection) -> args?.firstOrNull()
            else -> sfldSingle.find(selection)?.groupValues?.get(1)?.replace("''", "'")
                ?: sfldDouble.find(selection)?.groupValues?.get(1)?.replace("\"\"", "\"")
        }
        if (modelId == null && checksums == null && sortField == null) return Parsed.NotUnderstood
        return Parsed.Filter(NoteFilter(modelId, checksums, sortField))
    }
}
