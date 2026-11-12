package kotobaya.search.sql

import kotobaya.contracts.search.SearchPattern

/** ASCII-only lowercase (`A`-`Z` to `a`-`z`); every other character is left alone (Search.kt "Text matching"). */
internal fun foldAscii(s: String): String {
    var changed = false
    for (ch in s) {
        if (ch in 'A'..'Z') {
            changed = true
            break
        }
    }
    if (!changed) return s
    val sb = StringBuilder(s.length)
    for (ch in s) sb.append(if (ch in 'A'..'Z') ch + 32 else ch)
    return sb.toString()
}

/** Escapes `\`, `%` and `_` for a `LIKE ... ESCAPE '\'` pattern. */
internal fun escapeLike(s: String): String {
    val sb = StringBuilder(s.length + 4)
    for (ch in s) {
        if (ch == '\\' || ch == '%' || ch == '_') sb.append('\\')
        sb.append(ch)
    }
    return sb.toString()
}

/** The LIKE pattern for [segments] (wildcard between segments), literal text escaped; [contains] adds `%` at both ends. */
internal fun likePattern(segments: List<String>, contains: Boolean, fold: Boolean): String {
    val body = segments.joinToString("%") { escapeLike(if (fold) foldAscii(it) else it) }
    return if (contains) "%$body%" else body
}

/** Whole-string match of [segments] against [text], ASCII-case-insensitive. */
internal fun matchesWhole(segments: List<String>, text: String): Boolean {
    val t = foldAscii(text)
    val segs = segments.map(::foldAscii)
    if (segs.size == 1) return t == segs[0]
    val first = segs.first()
    val last = segs.last()
    if (!t.startsWith(first)) return false
    val end = t.length - last.length
    if (end < first.length || !t.endsWith(last)) return false
    var pos = first.length
    for (i in 1 until segs.size - 1) {
        val idx = t.indexOf(segs[i], pos)
        if (idx < 0 || idx + segs[i].length > end) return false
        pos = idx + segs[i].length
    }
    return true
}

/** Implicit wildcards at both ends: `*s0*s1*...*sn*`. */
internal fun matchesContains(pattern: SearchPattern, text: String): Boolean =
    matchesWhole(listOf("") + pattern.segments + listOf(""), text)

/**
 * Substrings that any text matched by [segments] must contain verbatim, usable as a `LIKE '%x%'`
 * prefilter on the raw JSON text of a column. Chunks with characters the canonical JSON writer
 * escapes (`"`, `\`, control characters) are dropped (the prefilter may over-select, never under-select).
 * With [splitOnSpace], segments are cut at U+0020 so a chunk never straddles the space that joins
 * values in `search_text`.
 */
internal fun safeChunks(segments: List<String>, splitOnSpace: Boolean): List<String> =
    segments.flatMap { if (splitOnSpace) it.split(' ') else listOf(it) }
        .filter { c -> c.isNotEmpty() && c.none { it == '"' || it == '\\' || it < ' ' } }
