package kotobaya.search

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.search.CompletionKind
import kotobaya.contracts.search.IsValue
import kotobaya.contracts.search.SearchCompletion
import kotobaya.contracts.search.SearchPattern
import kotobaya.contracts.search.SearchVocabulary

private val KEY_COMPLETIONS: List<String> = listOf(
    "deck:", "tag:", "is:", "kind:", "type:", "notetype:", "preset:", "kanji:", "entry:", "status:",
    "flag:", "rated:", "added:", "nid:", "cid:", "field:",
    "lapses>", "reps>", "interval>", "due>", "stability>", "difficulty>",
)

private val BUILTIN_KINDS: List<String> = listOf(
    "meaning", "reading", "listening", "production", "pitch", "writing", "components", "keyword",
    "comprehension", "cloze", "recognition", "spot", "basic",
)

private val RATED_VALUES: List<String> = listOf("1", "7", "30", "365")

private class Cand(val label: String, val insert: String, val kind: CompletionKind)

private fun asciiLower(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
    return sb.toString()
}

/** Prefix matches first, then substring matches; alphabetical within each group; duplicates removed. */
private fun rank(cands: List<Cand>, prefix: String): List<Cand> {
    val p = asciiLower(prefix)
    val order = compareBy<Cand>({ asciiLower(it.label) }, { it.label }, { it.insert })
    val all = cands.filter { it.label.isNotEmpty() }
    val pre = all.filter { asciiLower(it.label).startsWith(p) }.sortedWith(order)
    val sub = all.filter { val l = asciiLower(it.label); !l.startsWith(p) && l.contains(p) }.sortedWith(order)
    return (pre + sub).distinctBy { it.kind.name + "\u0000" + it.insert }
}

/** Unescapes the typed part of a value: drops the opening quote and backslashes. */
private fun typedValue(s: String): String {
    val sb = StringBuilder()
    var i = if (s.startsWith("\"")) 1 else 0
    while (i < s.length) {
        if (s[i] == '\\' && i + 1 < s.length) {
            sb.append(s[i + 1])
            i += 2
        } else {
            sb.append(s[i])
            i++
        }
    }
    return sb.toString()
}

internal fun completeSearch(query: String, cursor: Int, vocabulary: SearchVocabulary, limit: Int): List<SearchCompletion> {
    if (limit <= 0) return emptyList()
    val cur = cursor.coerceIn(0, query.length)
    val toks = lex(query)
    var idx = -1
    for ((i, t) in toks.withIndex()) {
        if ((t.kind == TokKind.TERM || t.kind == TokKind.OR || t.kind == TokKind.AND) && t.start <= cur && cur <= t.end) {
            idx = i
            break
        }
    }
    val out: List<SearchCompletion>
    if (idx < 0) {
        val prev = toks.lastOrNull { it.end <= cur }
        val operand = prev != null && (prev.kind == TokKind.TERM || prev.kind == TokKind.RPAREN)
        out = keyCompletions("", cur, cur, operand, cur, cur)
    } else {
        val t = toks[idx]
        val raw = query.substring(t.start, t.end)
        if (raw.startsWith("\"")) return emptyList()
        var colon = -1
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == ':' || c == '<' || c == '>' || c == '=') {
                if (c == ':') colon = i
                break
            }
            i++
        }
        val valueMode = colon >= 0 && cur > t.start + colon
        if (valueMode) {
            out = valueCompletions(query, t.start, t.end, colon, cur, vocabulary)
        } else {
            val pre = query.substring(t.start, cur)
            if (pre.any { it == '<' || it == '>' || it == '=' || it == '"' || it == '\\' || it == ':' }) return emptyList()
            val prevTok = if (idx > 0) toks[idx - 1] else null
            val operand = prevTok != null && (prevTok.kind == TokKind.TERM || prevTok.kind == TokKind.RPAREN)
            val end = if (colon >= 0) t.start + colon + 1 else t.end
            out = keyCompletions(pre, t.start, end, operand, t.start, t.end)
        }
    }
    return out.take(limit)
}

private fun keyCompletions(prefix: String, start: Int, end: Int, operandPrecedes: Boolean, opStart: Int, opEnd: Int): List<SearchCompletion> {
    val keys = rank(KEY_COMPLETIONS.map { Cand(it, it, CompletionKind.KEY) }, prefix)
        .map { SearchCompletion(it.insert, it.label, it.kind, start, end) }
    val ops = if (operandPrecedes) {
        rank(listOf("or", "and").map { Cand(it, it, CompletionKind.OPERATOR) }, prefix)
            .map { SearchCompletion(it.insert, it.label, it.kind, opStart, opEnd) }
    } else {
        emptyList()
    }
    return keys + ops
}

private fun valueCompletions(
    query: String,
    tokStart: Int,
    tokEnd: Int,
    colon: Int,
    cur: Int,
    v: SearchVocabulary,
): List<SearchCompletion> {
    val key = query.substring(tokStart, tokStart + colon)
    val valueStart = tokStart + colon + 1
    var replaceStart = valueStart
    var replaceEnd = tokEnd
    val typed = query.substring(valueStart, cur)
    if (key == "field") {
        val second = query.indexOf(':', valueStart)
        if (second in valueStart until tokEnd) {
            if (cur > second) return emptyList()
            replaceEnd = second
        }
        val withColon = replaceEnd == tokEnd
        val cands = v.fieldKeys.filter { KEY_RE.matches(it) }
            .map { Cand(it, if (withColon) "$it:" else it, CompletionKind.VALUE) }
        return rank(cands, typed).map { SearchCompletion(it.insert, it.label, it.kind, replaceStart, replaceEnd) }
    }
    val cands: List<Cand> = when (key) {
        "is" -> IsValue.entries.map { plain(it.wire) }
        "type" -> NoteBaseType.entries.map { plain(it.wire) }
        "status" -> WordStatus.entries.map { plain(it.wire) }
        "flag" -> (0..7).map { plain(it.toString()) }
        "rated" -> {
            if (typed.contains(':')) {
                (1..4).map { plain(it.toString()) }.also { replaceStart = valueStart + typed.indexOf(':') + 1 }
            } else {
                RATED_VALUES.map { plain(it) }
            }
        }
        "kind" -> (v.cardKinds + BUILTIN_KINDS).filter { KEY_RE.matches(it) }.map { plain(it) }
        "deck" -> v.deckNames.map { quoted(it) }
        "tag" -> v.tags.map { quoted(it) }
        "notetype" -> v.noteTypeNames.map { quoted(it) }
        "preset" -> v.presetNames.map { quoted(it) }
        else -> emptyList()
    }
    val prefix = if (key == "rated" && typed.contains(':')) typed.substringAfter(':') else typedValue(typed)
    return rank(cands, prefix).map { SearchCompletion(it.insert, it.label, it.kind, replaceStart, replaceEnd) }
}

private fun plain(s: String) = Cand(s, s, CompletionKind.VALUE)

private fun quoted(name: String): Cand {
    if (name.isEmpty()) return Cand(name, "", CompletionKind.VALUE)
    return Cand(name, fmtPattern(SearchPattern(listOf(name)), false), CompletionKind.VALUE)
}
