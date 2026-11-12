package kotobaya.search

import kotobaya.contracts.search.SearchNode
import kotobaya.contracts.search.SearchPattern
import kotobaya.contracts.search.SearchTerm

internal fun formatSearch(node: SearchNode): String {
    if (node is SearchNode.All) return ""
    return fmtNode(node)
}

private fun fmtNode(node: SearchNode): String = when (node) {
    is SearchNode.All -> throw IllegalArgumentException("All is only well-formed as the root")
    is SearchNode.Term -> fmtTerm(node.term)
    is SearchNode.And -> {
        require(node.children.size >= 2) { "And needs at least two children" }
        require(node.children.none { it is SearchNode.And || it is SearchNode.All }) { "And directly inside And, or All inside And" }
        node.children.joinToString(" ") { if (it is SearchNode.Or) "(" + fmtNode(it) + ")" else fmtNode(it) }
    }
    is SearchNode.Or -> {
        require(node.children.size >= 2) { "Or needs at least two children" }
        require(node.children.none { it is SearchNode.Or || it is SearchNode.All }) { "Or directly inside Or, or All inside Or" }
        node.children.joinToString(" or ") { fmtNode(it) }
    }
    is SearchNode.Not -> {
        val c = node.child
        "-" + if (c is SearchNode.And || c is SearchNode.Or) "(" + fmtNode(c) + ")" else fmtNode(c)
    }
}

private fun fmtTerm(t: SearchTerm): String = when (t) {
    is SearchTerm.Text -> fmtPattern(t.pattern, true)
    is SearchTerm.Deck -> "deck:" + fmtPattern(t.pattern, false)
    is SearchTerm.Tag -> "tag:" + fmtPattern(t.pattern, false)
    is SearchTerm.NoteType -> "notetype:" + fmtPattern(t.pattern, false)
    is SearchTerm.Preset -> "preset:" + fmtPattern(t.pattern, false)
    is SearchTerm.Field -> {
        require(KEY_RE.matches(t.key)) { "bad field key" }
        "field:" + t.key + ":" + fmtPattern(t.pattern, false)
    }
    is SearchTerm.Is -> "is:" + t.value.wire
    is SearchTerm.Kind -> {
        require(KEY_RE.matches(t.kind)) { "bad kind" }
        "kind:" + t.kind
    }
    is SearchTerm.Type -> "type:" + t.baseType.wire
    is SearchTerm.Kanji -> {
        require(isSingleKanji(t.literal)) { "not one kanji" }
        "kanji:" + t.literal
    }
    is SearchTerm.Entry -> {
        require(t.entrySeq >= 1) { "entry must be positive" }
        "entry:" + t.entrySeq
    }
    is SearchTerm.Status -> "status:" + t.status.wire
    is SearchTerm.Flag -> {
        require(t.flag in 0..7) { "flag out of range" }
        "flag:" + t.flag
    }
    is SearchTerm.Rated -> {
        require(t.days in 1..MAX_DAYS) { "days out of range" }
        require(t.rating == null || t.rating in 1..4) { "rating out of range" }
        "rated:" + t.days + (if (t.rating != null) ":" + t.rating else "")
    }
    is SearchTerm.Added -> {
        require(t.days in 1..MAX_DAYS) { "days out of range" }
        "added:" + t.days
    }
    is SearchTerm.NoteId -> {
        require(UUID_RE.matches(t.id)) { "bad uuid" }
        "nid:" + t.id
    }
    is SearchTerm.CardId -> {
        require(UUID_RE.matches(t.id)) { "bad uuid" }
        "cid:" + t.id
    }
    is SearchTerm.Compare -> {
        require(!t.value.isNaN() && !t.value.isInfinite()) { "non-finite number" }
        t.property.wire + t.op.wire + formatNumber(t.value)
    }
}

private fun esc(seg: String, chars: String): String {
    val sb = StringBuilder()
    for (c in seg) {
        if (chars.indexOf(c) >= 0) sb.append('\\')
        sb.append(c)
    }
    return sb.toString()
}

/** Canonical text of [p]: bare when possible, else quoted. [word] = free-text word (stricter bare rules). */
internal fun fmtPattern(p: SearchPattern, word: Boolean): String {
    require(p.segments.isNotEmpty() && (p.segments.size > 1 || p.segments[0].isNotEmpty())) { "empty pattern" }
    val bare = p.segments.joinToString("*") { esc(it, "\\*()\"") }
    var ok = bare.isNotEmpty() && bare.none { isWs(it) }
    if (word) {
        ok = ok && bare.none { it == ':' || it == '<' || it == '>' || it == '=' } &&
            !bare.startsWith("-") && bare != "or" && bare != "OR" && bare != "and" && bare != "AND"
    }
    if (ok) return bare
    return "\"" + p.segments.joinToString("*") { esc(it, "\\\"*") } + "\""
}

/** Shortest round-trip decimal text without exponent; `-` iff the sign bit is set. */
internal fun formatNumber(v: Double): String {
    val neg = v < 0 || (v == 0.0 && 1.0 / v < 0)
    var s = (if (neg) -v else v).toString().lowercase()
    var exp = 0
    val e = s.indexOf('e')
    if (e >= 0) {
        exp = s.substring(e + 1).removePrefix("+").toInt()
        s = s.substring(0, e)
    }
    val dot = s.indexOf('.')
    var digits = if (dot < 0) s else s.substring(0, dot) + s.substring(dot + 1)
    var point = (if (dot < 0) s.length else dot) + exp
    val trimmedLeading = digits.trimStart('0')
    point -= digits.length - trimmedLeading.length
    digits = trimmedLeading.trimEnd('0')
    val body = when {
        digits.isEmpty() -> "0"
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length)
        else -> digits.substring(0, point) + "." + digits.substring(point)
    }
    return if (neg) "-$body" else body
}
