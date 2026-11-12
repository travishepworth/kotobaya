package kotobaya.search

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.search.CompareOp
import kotobaya.contracts.search.IsValue
import kotobaya.contracts.search.SearchError
import kotobaya.contracts.search.SearchErrorCode
import kotobaya.contracts.search.SearchPattern
import kotobaya.contracts.search.SearchProperty
import kotobaya.contracts.search.SearchTerm
import kotobaya.contracts.search.SourceSpan

internal enum class TokKind { LPAREN, RPAREN, MINUS, OR, AND, TERM }

/** One lexical token. [term] is null for non-terms and for terms that have [errors]. */
internal class Tok(
    val kind: TokKind,
    val start: Int,
    val end: Int,
    val spaceBefore: Boolean,
    val term: SearchTerm? = null,
    val errors: List<SearchError> = emptyList(),
    /** For keyed terms: end of the key part (including `:` or the operator); else [start]. */
    val keyEnd: Int = start,
)

internal val KEY_RE = Regex("[a-z][a-z0-9_]*")
internal val UUID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val NUMBER_RE = Regex("-?[0-9]+(\\.[0-9]+)?")
private val DAYS_RE = Regex("[1-9][0-9]{0,4}")
private val ENTRY_RE = Regex("[1-9][0-9]*")
private val COMPARE_RE = Regex("(lapses|reps|interval|due|stability|difficulty)(<=|>=|!=|<|>|=)(.*)")

internal const val MAX_DAYS: Int = 36500

internal val PATTERN_KEYS: Set<String> = setOf("deck", "tag", "notetype", "preset")
internal val KNOWN_KEYS: Set<String> = PATTERN_KEYS + setOf(
    "is", "kind", "type", "kanji", "entry", "status", "flag", "rated", "added", "nid", "cid", "field",
)

internal fun isWs(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '　'

internal fun messageKey(code: SearchErrorCode): String = "search.error." + code.name.lowercase()

/** Code point at [i] and its UTF-16 length. */
internal fun codePointAt(s: String, i: Int): Pair<Int, Int> {
    val c = s[i]
    if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
        return Pair(0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00), 2)
    }
    return Pair(c.code, 1)
}

internal fun isKanjiCodePoint(cp: Int): Boolean =
    cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x20000..0x2FA1F

internal fun isSingleKanji(s: String): Boolean {
    if (s.isEmpty()) return false
    val (cp, len) = codePointAt(s, 0)
    return len == s.length && isKanjiCodePoint(cp)
}

internal fun lex(q: String): List<Tok> {
    val out = ArrayList<Tok>()
    val n = q.length
    var i = 0
    var space = true
    while (i < n) {
        val c = q[i]
        if (isWs(c)) {
            space = true
            i++
            continue
        }
        when (c) {
            '(' -> out += Tok(TokKind.LPAREN, i, i + 1, space)
            ')' -> out += Tok(TokKind.RPAREN, i, i + 1, space)
            '-' -> out += Tok(TokKind.MINUS, i, i + 1, space)
            else -> {
                var j = i
                var inQuote = false
                while (j < n) {
                    val ch = q[j]
                    if (ch == '\\') {
                        j = minOf(j + 2, n)
                        continue
                    }
                    if (inQuote) {
                        if (ch == '"') inQuote = false
                    } else if (ch == '"') {
                        inQuote = true
                    } else if (isWs(ch) || ch == '(' || ch == ')') {
                        break
                    }
                    j++
                }
                out += chunk(q, i, j, inQuote, space)
                i = j
                space = false
                continue
            }
        }
        space = false
        i++
    }
    return out
}

private class Fail(val code: SearchErrorCode)

private fun chunk(q: String, start: Int, end: Int, unterminated: Boolean, space: Boolean): Tok {
    val raw = q.substring(start, end)
    when (raw) {
        "or", "OR" -> return Tok(TokKind.OR, start, end, space)
        "and", "AND" -> return Tok(TokKind.AND, start, end, space)
    }
    val errs = ArrayList<SearchError>()
    var keyEnd = start
    var term: SearchTerm? = null
    val fail: Fail? = if (unterminated) {
        Fail(SearchErrorCode.BAD_QUOTE)
    } else {
        val r = parseChunk(raw)
        term = r.term
        keyEnd = start + r.keyLen
        r.fail
    }
    if (fail != null) {
        errs += SearchError(fail.code, SourceSpan(start, end), messageKey(fail.code))
        term = null
    }
    return Tok(TokKind.TERM, start, end, space, term, errs, keyEnd)
}

private class ChunkResult(val term: SearchTerm?, val keyLen: Int, val fail: Fail?)

private fun bad(code: SearchErrorCode) = ChunkResult(null, 0, Fail(code))

private fun patternTerm(p: PatternResult): ChunkResult =
    if (p.pattern != null) ChunkResult(SearchTerm.Text(p.pattern), 0, null) else bad(p.code ?: SearchErrorCode.BAD_VALUE)

private fun parseChunk(raw: String): ChunkResult {
    if (raw.startsWith("\"")) return patternTerm(parseValuePattern(raw))
    COMPARE_RE.matchEntire(raw)?.let { m ->
        val prop = SearchProperty.entries.first { it.wire == m.groupValues[1] }
        val op = CompareOp.entries.first { it.wire == m.groupValues[2] }
        val num = m.groupValues[3]
        val keyLen = m.groupValues[1].length + m.groupValues[2].length
        if (num.isEmpty()) return ChunkResult(null, keyLen, Fail(SearchErrorCode.MISSING_VALUE))
        if (!NUMBER_RE.matches(num)) return ChunkResult(null, keyLen, Fail(SearchErrorCode.BAD_VALUE))
        val v = num.toDouble()
        if (v.isNaN() || v.isInfinite()) return ChunkResult(null, keyLen, Fail(SearchErrorCode.BAD_VALUE))
        return ChunkResult(SearchTerm.Compare(prop, op, v), keyLen, null)
    }
    // First unescaped ':' '<' '>' '='.
    var i = 0
    var special = -1
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '\\') {
            i += 2
            continue
        }
        if (ch == ':' || ch == '<' || ch == '>' || ch == '=') {
            special = i
            break
        }
        i++
    }
    if (special < 0) return patternTerm(parseValuePattern(raw))
    if (raw[special] != ':') return bad(SearchErrorCode.UNKNOWN_KEY)
    val key = raw.substring(0, special)
    if (key !in KNOWN_KEYS) return bad(SearchErrorCode.UNKNOWN_KEY)
    val r = parseKeyed(key, raw.substring(special + 1))
    return ChunkResult(r.term, special + 1, r.fail)
}

private fun parseKeyed(key: String, value: String): ChunkResult {
    if (key in PATTERN_KEYS || key == "field") {
        var pk = value
        var fieldKey = ""
        if (key == "field") {
            val c = value.indexOf(':')
            if (c < 0) return bad(SearchErrorCode.MISSING_VALUE)
            fieldKey = value.substring(0, c)
            if (!KEY_RE.matches(fieldKey)) return bad(SearchErrorCode.BAD_VALUE)
            pk = value.substring(c + 1)
        }
        val p = parseValuePattern(pk, requireValue = true)
        val pat = p.pattern ?: return bad(p.code ?: SearchErrorCode.BAD_VALUE)
        val t: SearchTerm = when (key) {
            "deck" -> SearchTerm.Deck(pat)
            "tag" -> SearchTerm.Tag(pat)
            "notetype" -> SearchTerm.NoteType(pat)
            "preset" -> SearchTerm.Preset(pat)
            else -> SearchTerm.Field(fieldKey, pat)
        }
        return ChunkResult(t, 0, null)
    }
    if (value.isEmpty()) return bad(SearchErrorCode.MISSING_VALUE)
    val t: SearchTerm = when (key) {
        "is" -> IsValue.entries.firstOrNull { it.wire == value }?.let { SearchTerm.Is(it) }
        "kind" -> if (KEY_RE.matches(value)) SearchTerm.Kind(value) else null
        "type" -> NoteBaseType.entries.firstOrNull { it.wire == value }?.let { SearchTerm.Type(it) }
        "status" -> WordStatus.entries.firstOrNull { it.wire == value }?.let { SearchTerm.Status(it) }
        "kanji" -> if (isSingleKanji(value)) SearchTerm.Kanji(value) else null
        "entry" -> if (ENTRY_RE.matches(value)) value.toLongOrNull()?.let { SearchTerm.Entry(it) } else null
        "flag" -> if (value.length == 1 && value[0] in '0'..'7') SearchTerm.Flag(value[0] - '0') else null
        "added" -> days(value)?.let { SearchTerm.Added(it) }
        "rated" -> {
            val c = value.indexOf(':')
            if (c < 0) {
                days(value)?.let { SearchTerm.Rated(it, null) }
            } else {
                val d = days(value.substring(0, c))
                val r = value.substring(c + 1)
                if (d != null && r.length == 1 && r[0] in '1'..'4') SearchTerm.Rated(d, r[0] - '0') else null
            }
        }
        "nid" -> if (UUID_RE.matches(value)) SearchTerm.NoteId(value) else null
        "cid" -> if (UUID_RE.matches(value)) SearchTerm.CardId(value) else null
        else -> null
    } ?: return bad(SearchErrorCode.BAD_VALUE)
    return ChunkResult(t, 0, null)
}

private fun days(s: String): Int? {
    if (!DAYS_RE.matches(s)) return null
    val d = s.toInt()
    return if (d <= MAX_DAYS) d else null
}

internal class PatternResult(val pattern: SearchPattern?, val code: SearchErrorCode?)

/** Parses a pattern value: `"quoted"` or bare (a bare free-text word or the value after `key:`). */
internal fun parseValuePattern(raw: String, requireValue: Boolean = false): PatternResult {
    if (raw.isEmpty()) {
        return PatternResult(null, if (requireValue) SearchErrorCode.MISSING_VALUE else SearchErrorCode.BAD_VALUE)
    }
    val quoted = raw[0] == '"'
    val body: String
    if (quoted) {
        var i = 1
        var close = -1
        while (i < raw.length) {
            if (raw[i] == '\\') {
                i += 2
                continue
            }
            if (raw[i] == '"') {
                close = i
                break
            }
            i++
        }
        if (close < 0 || close != raw.length - 1) return PatternResult(null, SearchErrorCode.BAD_QUOTE)
        body = raw.substring(1, close)
        if (body.isEmpty()) return PatternResult(null, SearchErrorCode.BAD_QUOTE)
    } else {
        body = raw
    }
    val segs = ArrayList<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < body.length) {
        val ch = body[i]
        when (ch) {
            '\\' -> {
                if (i + 1 >= body.length) return PatternResult(null, SearchErrorCode.BAD_VALUE)
                val nx = body[i + 1]
                val ok = nx == '\\' || nx == '"' || nx == '*' || (!quoted && (nx == '(' || nx == ')'))
                if (!ok) return PatternResult(null, SearchErrorCode.BAD_VALUE)
                cur.append(nx)
                i += 2
                continue
            }
            '"' -> return PatternResult(null, SearchErrorCode.BAD_QUOTE)
            '*' -> {
                segs += cur.toString()
                cur.clear()
            }
            else -> cur.append(ch)
        }
        i++
    }
    segs += cur.toString()
    return PatternResult(SearchPattern(segs), null)
}
