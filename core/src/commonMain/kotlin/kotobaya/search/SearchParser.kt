package kotobaya.search

import kotobaya.contracts.search.SearchCompletion
import kotobaya.contracts.search.SearchError
import kotobaya.contracts.search.SearchErrorCode
import kotobaya.contracts.search.SearchNode
import kotobaya.contracts.search.SearchParseResult
import kotobaya.contracts.search.SearchQueryParser
import kotobaya.contracts.search.SearchVocabulary
import kotobaya.contracts.search.SourceSpan

/** The search language parser, printer and autocompletion. Stateless. */
public object DefaultSearchQueryParser : SearchQueryParser {
    override fun parse(query: String): SearchParseResult = parseTokens(query, lex(query))

    override fun format(node: SearchNode): String = formatSearch(node)

    override fun complete(query: String, cursor: Int, vocabulary: SearchVocabulary, limit: Int): List<SearchCompletion> =
        completeSearch(query, cursor, vocabulary, limit)
}

internal fun parseTokens(query: String, toks: List<Tok>): SearchParseResult {
    val errors = ArrayList<SearchError>()
    for (t in toks) errors += t.errors
    val node = TreeParser(toks, query.length, errors).parseAll()
    val sorted = errors.sortedBy { it.span.start }
    return if (sorted.isEmpty() && node != null) SearchParseResult(node, emptyList()) else SearchParseResult(null, sorted)
}

private class TreeParser(val toks: List<Tok>, val n: Int, val errors: MutableList<SearchError>) {
    var pos = 0

    fun err(code: SearchErrorCode, start: Int, end: Int) {
        errors += SearchError(code, SourceSpan(start, end), messageKey(code))
    }

    fun peek(): Tok? = if (pos < toks.size) toks[pos] else null

    fun operandStart(t: Tok) = t.kind == TokKind.MINUS || t.kind == TokKind.LPAREN || t.kind == TokKind.TERM

    fun parseAll(): SearchNode? {
        if (toks.isEmpty()) return SearchNode.All(SourceSpan(0, n))
        val tops = ArrayList<SearchNode?>()
        while (pos < toks.size) {
            val t = toks[pos]
            if (t.kind == TokKind.RPAREN) {
                err(SearchErrorCode.UNBALANCED_PAREN, t.start, t.end)
                pos++
            } else {
                tops += parseOr()
            }
        }
        return if (tops.size == 1) tops[0] else null
    }

    fun parseOr(): SearchNode? {
        val kids = ArrayList<SearchNode?>()
        kids += parseAnd(false)
        while (peek()?.kind == TokKind.OR) {
            val op = toks[pos++]
            if (!op.spaceBefore) err(SearchErrorCode.DANGLING_OPERATOR, op.start, op.end)
            kids += parseAnd(true)
        }
        return join(kids, true)
    }

    fun parseAnd(afterOp: Boolean): SearchNode? {
        val kids = ArrayList<SearchNode?>()
        var expectOperand = true
        var needSpace = afterOp
        while (true) {
            val t = peek()
            if (expectOperand) {
                if (t != null && operandStart(t)) {
                    if (needSpace && !t.spaceBefore) err(SearchErrorCode.DANGLING_OPERATOR, t.start, t.end)
                    kids += parseUnary()
                    expectOperand = false
                    needSpace = false
                } else if (t != null && t.kind == TokKind.AND) {
                    err(SearchErrorCode.DANGLING_OPERATOR, t.start, t.end)
                    pos++
                } else {
                    if (t != null) err(SearchErrorCode.DANGLING_OPERATOR, t.start, t.end) else err(SearchErrorCode.DANGLING_OPERATOR, n, n)
                    return null
                }
            } else {
                if (t == null || t.kind == TokKind.RPAREN || t.kind == TokKind.OR) break
                if (t.kind == TokKind.AND) {
                    pos++
                    if (!t.spaceBefore) err(SearchErrorCode.DANGLING_OPERATOR, t.start, t.end)
                    expectOperand = true
                    needSpace = true
                } else {
                    if (!t.spaceBefore) err(SearchErrorCode.UNBALANCED_PAREN, t.start, t.end)
                    kids += parseUnary()
                }
            }
        }
        return join(kids, false)
    }

    fun parseUnary(): SearchNode? {
        val t = toks[pos++]
        when (t.kind) {
            TokKind.MINUS -> {
                val nx = peek()
                if (nx == null || !operandStart(nx) || nx.spaceBefore) {
                    err(SearchErrorCode.DANGLING_OPERATOR, t.start, t.end)
                    return null
                }
                val child = parseUnary() ?: return null
                return SearchNode.Not(child, SourceSpan(t.start, child.span.end))
            }
            TokKind.LPAREN -> {
                val nx = peek()
                if (nx != null && nx.kind == TokKind.RPAREN) {
                    pos++
                    err(SearchErrorCode.UNBALANCED_PAREN, t.start, nx.end)
                    return null
                }
                val inner = parseOr()
                val close = peek()
                if (close != null && close.kind == TokKind.RPAREN) {
                    pos++
                    return inner?.withSpan(SourceSpan(t.start, close.end))
                }
                err(SearchErrorCode.UNBALANCED_PAREN, t.start, t.end)
                return null
            }
            else -> {
                val term = t.term ?: return null
                return SearchNode.Term(term, SourceSpan(t.start, t.end))
            }
        }
    }

    /** Joins [kids] with AND (OR when [isOr]), flattening same-operator children. Null if any child is null. */
    fun join(kids: List<SearchNode?>, isOr: Boolean): SearchNode? {
        if (kids.any { it == null }) return null
        val nodes = kids.filterNotNull()
        if (nodes.size == 1) return nodes[0]
        val flat = ArrayList<SearchNode>()
        for (k in nodes) {
            when {
                isOr && k is SearchNode.Or -> flat += k.children
                !isOr && k is SearchNode.And -> flat += k.children
                else -> flat += k
            }
        }
        val span = SourceSpan(nodes.first().span.start, nodes.last().span.end)
        return if (isOr) SearchNode.Or(flat, span) else SearchNode.And(flat, span)
    }
}

private fun SearchNode.withSpan(s: SourceSpan): SearchNode = when (this) {
    is SearchNode.All -> copy(span = s)
    is SearchNode.And -> copy(span = s)
    is SearchNode.Or -> copy(span = s)
    is SearchNode.Not -> copy(span = s)
    is SearchNode.Term -> copy(span = s)
}
