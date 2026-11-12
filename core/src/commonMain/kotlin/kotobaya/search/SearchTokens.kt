package kotobaya.search

import kotobaya.contracts.search.SearchError

/** A highlighting token: [kind] is one of `key`, `value`, `text`, `operator`, `negation`, `paren`, `error`. */
internal class SearchTokenInfo(val kind: String, val start: Int, val end: Int)

internal fun searchTokens(query: String, errors: List<SearchError>): List<SearchTokenInfo> {
    val out = ArrayList<SearchTokenInfo>()
    for (t in lex(query)) {
        val flagged = errors.any { it.span.start == t.start && it.span.end == t.end }
        when (t.kind) {
            TokKind.LPAREN, TokKind.RPAREN -> out += SearchTokenInfo(if (flagged) "error" else "paren", t.start, t.end)
            TokKind.MINUS -> out += SearchTokenInfo(if (flagged) "error" else "negation", t.start, t.end)
            TokKind.OR, TokKind.AND -> out += SearchTokenInfo(if (flagged) "error" else "operator", t.start, t.end)
            TokKind.TERM -> when {
                t.errors.isNotEmpty() -> out += SearchTokenInfo("error", t.start, t.end)
                t.keyEnd > t.start -> {
                    out += SearchTokenInfo("key", t.start, t.keyEnd)
                    if (t.end > t.keyEnd) out += SearchTokenInfo("value", t.keyEnd, t.end)
                }
                else -> out += SearchTokenInfo("text", t.start, t.end)
            }
        }
    }
    return out
}
