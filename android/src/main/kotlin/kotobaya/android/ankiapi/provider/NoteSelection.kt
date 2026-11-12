package kotobaya.android.ankiapi.provider

/**
 * Parses the `notes`/`notes_v2` selections AddContentApi sends for duplicate checks:
 * `mid=N` (or `mid:N` in the v1 search syntax), `csum in (a,b)` / `csum=N`, and
 * `sfld=?` / `sfld='x'` / `sfld="x"`, joined by `and` (or whitespace, the v1 search syntax).
 *
 * Returns [Parsed.All] for a blank selection and [Parsed.NotUnderstood] when anything other than
 * recognised clauses, `and`, whitespace and parentheses remains (e.g. `or`, `not`, other columns,
 * or `?` placeholders we don't bind): the provider then answers with no rows (so a client never
 * sees a false duplicate) and logs it.
 */
internal object NoteSelection {
    sealed interface Parsed {
        data object All : Parsed

        data class Filter(val filter: NoteFilter) : Parsed

        data object NotUnderstood : Parsed
    }

    private val mid = Regex("""\bmid\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val csumIn = Regex("""\bcsum\s+in\s*\(\s*(\d+(?:\s*,\s*\d+)*)\s*\)""", RegexOption.IGNORE_CASE)
    private val csumEq = Regex("""\bcsum\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val sfldArg = Regex("""\bsfld\s*=\s*\?""", RegexOption.IGNORE_CASE)
    private val sfldSingle = Regex("""\bsfld\s*=\s*'((?:[^']|'')*)'""", RegexOption.IGNORE_CASE)
    private val sfldDouble = Regex("""\bsfld\s*=\s*"((?:[^"]|"")*)"""", RegexOption.IGNORE_CASE)
    private val connectives = Regex("""(?i)\band\b|[\s()]""")

    fun parse(selection: String?, args: Array<out String>?): Parsed {
        if (selection.isNullOrBlank()) return Parsed.All
        val used = mutableListOf<IntRange>()
        fun Regex.take(): MatchResult? = findAll(selection).singleOrNull()?.also { used += it.range }
        // A clause given twice is ambiguous; findAll(...).singleOrNull() leaves it unrecognised.
        val modelId = mid.take()?.groupValues?.get(1)?.toLongOrNull()
        val checksums = csumIn.take()?.groupValues?.get(1)?.split(',')?.map { it.trim().toLong() }?.toSet()
            ?: csumEq.take()?.groupValues?.get(1)?.toLongOrNull()?.let { setOf(it) }
        val sortField = sfldArg.take()?.let { m ->
            // Bind the placeholder by position: the number of `?` before it in the selection.
            args?.getOrNull(selection.substring(0, m.range.first).count { it == '?' }) ?: return Parsed.NotUnderstood
        } ?: sfldSingle.take()?.groupValues?.get(1)?.replace("''", "'")
            ?: sfldDouble.take()?.groupValues?.get(1)?.replace("\"\"", "\"")
        if (modelId == null && checksums == null && sortField == null) return Parsed.NotUnderstood

        val rest = StringBuilder(selection)
        used.sortedByDescending { it.first }.forEach { rest.replace(it.first, it.last + 1, " ") }
        if (connectives.replace(rest, "").isNotEmpty()) return Parsed.NotUnderstood
        return Parsed.Filter(NoteFilter(modelId, checksums, sortField))
    }
}
