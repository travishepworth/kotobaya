package kotobaya.ankiconnect

import java.util.UUID

/**
 * `anki_id(note)`: the first 48 bits of the UUIDv7 (Unix ms) x 32 plus the 5 bits that follow the version nibble.
 * Derived, never stored; below 2^53, so it survives JSON numbers.
 */
internal fun ankiId(id: UUID): Long {
    val ms = id.mostSignificantBits ushr 16
    val randA = id.mostSignificantBits and 0xFFF
    return ms * 32 + (randA ushr 7)
}

/** The same derivation in SQL, for a note row aliased `n`. */
internal const val ANKI_ID_SQL =
    "(('x' || substr(replace(n.id::text, '-', ''), 1, 12))::bit(48)::bigint * 32 + " +
        "((('x' || substr(replace(n.id::text, '-', ''), 14, 2))::bit(8)::int) >> 3))"

/** One parsed term of a `findNotes` query. */
internal sealed interface FindTerm {
    data class Field(val key: String, val value: String) : FindTerm
    data class Deck(val name: String) : FindTerm
    data class Nid(val id: Long) : FindTerm
}

private val FIELD_KEY = Regex("^[a-z][a-z0-9_]*$")

/** Splits on whitespace outside double quotes; quotes are dropped, `\"` is a literal quote. */
internal fun tokenizeQuery(q: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quoted = false
    var started = false
    var i = 0
    while (i < q.length) {
        val c = q[i]
        when {
            c == '\\' && i + 1 < q.length && q[i + 1] == '"' -> {
                cur.append('"')
                started = true
                i++
            }
            c == '"' -> {
                quoted = !quoted
                started = true
            }
            c.isWhitespace() && !quoted -> if (started) {
                out += cur.toString()
                cur.clear()
                started = false
            }
            else -> {
                cur.append(c)
                started = true
            }
        }
        i++
    }
    if (started) out += cur.toString()
    return out
}

/** The terms of [q], or null when any part is a form this endpoint does not support (the query then matches nothing). */
internal fun parseFindQuery(q: String): List<FindTerm>? {
    val tokens = tokenizeQuery(q)
    if (tokens.isEmpty()) return null
    return tokens.map { t ->
        val colon = t.indexOf(':')
        if (colon <= 0) return null
        val name = t.substring(0, colon).lowercase()
        val value = t.substring(colon + 1)
        when {
            name == "nid" -> FindTerm.Nid(value.toLongOrNull() ?: return null)
            name == "deck" -> FindTerm.Deck(value)
            FIELD_KEY.matches(name) -> FindTerm.Field(name, value)
            else -> return null
        }
    }
}

private val TAGS = Regex("<[^>]*>")

/** Field text as `findNotes` and the duplicate rule compare it: HTML tags removed. */
internal fun stripHtml(s: String): String = TAGS.replace(s, "")
