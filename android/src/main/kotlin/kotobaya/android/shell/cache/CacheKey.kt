package kotobaya.android.shell.cache

import kotobaya.android.shell.api.parseQuery

/** SHELL.md rule K-1: the response cache's key and the canonical query. */
object CacheKey {
    /** `operationId + "\n" + path + "\n" + canonicalQuery`. */
    fun of(operationId: String, path: String, canonicalQuery: String): String = "$operationId\n$path\n$canonicalQuery"

    /** [canonicalQuery] of a raw query string (split at `&`, first `=`, percent-decoded as the server does). */
    fun canonicalQuery(raw: String): String = canonicalQuery(parseQuery(raw))

    /**
     * Every (name, value) pair, sorted by name then value comparing Unicode code points, each part
     * re-encoded with the RFC 3986 unreserved characters literal and every other UTF-8 byte as
     * upper-case `%XX`, joined as `name=value` with `&`. A repeated name keeps all its pairs; `""` for none.
     */
    fun canonicalQuery(decoded: Map<String, List<String>>): String {
        val pairs = ArrayList<Pair<String, String>>()
        for ((name, values) in decoded) {
            if (values.isEmpty()) pairs += name to "" else values.forEach { pairs += name to it }
        }
        pairs.sortWith { a, b ->
            val byName = compareCodePoints(a.first, b.first)
            if (byName != 0) byName else compareCodePoints(a.second, b.second)
        }
        return pairs.joinToString("&") { (n, v) -> encode(n) + "=" + encode(v) }
    }

    internal fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private const val HEX = "0123456789ABCDEF"

    internal fun encode(s: String): String {
        val out = StringBuilder(s.length)
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch == '-' || ch == '.' || ch == '_' || ch == '~')) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }
}
