package kotobaya.android.shell.api

import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson

/**
 * One API request as the bridge carries it (SHELL.md 2.2 `req`). Transport-free: the bridge (A3) and
 * any test build it the same way.
 */
data class ShellRequest(
    /** Upper-case HTTP method. */
    val method: String,
    /** Path exactly as the generated client built it (`/api/v1/...` or `/shell/v1/...`), percent-encoded. */
    val path: String,
    /** Query parameters, already decoded; see [parseQuery] for the raw form. */
    val query: Map<String, List<String>> = emptyMap(),
    /** Header fields; names are compared lower-case. */
    val headers: Map<String, String> = emptyMap(),
    /** Body text; ignored on `GET`. */
    val body: String? = null,
) {
    /** The value of header [name] (case-insensitive), or null. */
    fun header(name: String): String? {
        val n = name.lowercase()
        return headers.entries.firstOrNull { it.key.lowercase() == n }?.value
    }

    companion object {
        /** Builds a request from the bridge's raw query string (SHELL.md 2.2 `req.query`). */
        fun fromRaw(method: String, path: String, rawQuery: String, headers: Map<String, String>, body: String?): ShellRequest =
            ShellRequest(method.uppercase(), path, parseQuery(rawQuery), headers, body)
    }
}

/** One response (SHELL.md 2.2 `res`). Header names are lower-case. */
data class ShellResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    companion object {
        /** A `200` (or [status]) JSON answer. [LocalApi] adds `kotobaya-local` and `x-request-id`. */
        fun json(value: JsonValue, status: Int = 200, headers: Map<String, String> = emptyMap()): ShellResponse =
            ShellResponse(status, mapOf(ShellHeaders.CONTENT_TYPE to ShellHeaders.JSON) + headers, DefaultCanonicalJson.encode(value))

        /** `204`, no body. */
        fun noContent(): ShellResponse = ShellResponse(204)
    }
}

/**
 * Returned by a handler (identity-compared) when it cannot answer locally: [LocalApi] forwards the
 * request through [Upstream] instead (rule L-7 for `local_or_proxy`; the forwarded `bulkCards`
 * actions and `createDeck` `type: list` of SHELL.md 4.3).
 */
val Fallthrough: ShellResponse = ShellResponse(0)

/** Header names and values the kernel uses (SHELL.md 3.3). */
object ShellHeaders {
    const val CONTENT_TYPE: String = "content-type"
    const val JSON: String = "application/json"
    const val PROBLEM_JSON: String = "application/problem+json"
    const val LOCAL: String = "kotobaya-local"
    const val REQUEST_ID: String = "x-request-id"
    const val RETRY_AFTER: String = "retry-after"
    const val CONTENT_LOCATION: String = "content-location"
}

/**
 * Parses a raw query string exactly as the server does (SHELL.md 2.2): `&`-separated, the first `=`
 * splits name and value, percent-decoding as UTF-8, `+` is a literal plus, empty parts are skipped,
 * a part without `=` has the value `""`. An undecodable escape is kept literally.
 */
fun parseQuery(raw: String): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    if (raw.isEmpty()) return out
    for (part in raw.split('&')) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        val name = percentDecode(if (eq < 0) part else part.substring(0, eq))
        val value = if (eq < 0) "" else percentDecode(part.substring(eq + 1))
        out.getOrPut(name) { ArrayList() }.add(value)
    }
    return out
}

/** Percent-decodes [s] as UTF-8; `+` stays `+`. Invalid escapes are kept as they are. */
fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val bytes = java.io.ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0) {
            bytes.write(hex(s[i + 1]) * 16 + hex(s[i + 2]))
            i += 3
        } else {
            val chunkEnd = if (Character.isHighSurrogate(c) && i + 1 < s.length) i + 2 else i + 1
            bytes.write(s.substring(i, chunkEnd).toByteArray(Charsets.UTF_8))
            i = chunkEnd
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}

private fun hex(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}
