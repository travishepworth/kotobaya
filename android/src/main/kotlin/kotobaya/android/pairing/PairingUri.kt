package kotobaya.android.pairing

import java.net.URLDecoder

data class PairingTarget(val serverBaseUrl: String, val code: String)

/** Parsing of `kotobaya://pair?server=<percent-encoded base URL incl. /api/v1>&code=<code>`. */
object PairingUri {
    /** Null unless [raw] is a pair URI with a usable http(s) `server` and a non-empty `code`. */
    fun parse(raw: String): PairingTarget? = try {
        parseOrThrow(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseOrThrow(raw: String): PairingTarget? {
        val text = raw.trim()
        val prefix = "kotobaya://pair"
        if (!text.startsWith(prefix, ignoreCase = true)) return null
        val rest = text.substring(prefix.length)
        if (!rest.startsWith("?")) return null
        val params = rest.substring(1).split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            val k = if (i < 0) it else it.substring(0, i)
            val v = if (i < 0) "" else it.substring(i + 1)
            decode(k) to decode(v)
        }
        val server = normalizeServerUrl(params["server"] ?: return null) ?: return null
        val code = normalizeCode(params["code"] ?: return null)
        if (code.isEmpty()) return null
        return PairingTarget(server, code)
    }

    /** Hyphens and spaces typed by the user are ignored; codes compare case-insensitively. */
    fun normalizeCode(code: String): String = code.filter { it != '-' && !it.isWhitespace() }.uppercase()

    /**
     * Accepts `https://host`, `https://host/` or `https://host/api/v1`; returns the base URL
     * ending in `/api/v1`, or null if it is not an http(s) URL.
     */
    fun normalizeServerUrl(input: String): String? {
        var u = input.trim().trimEnd('/')
        if (!(u.startsWith("https://", true) || u.startsWith("http://", true))) return null
        if (u.length <= u.indexOf("://") + 3) return null
        if (!u.endsWith("/api/v1")) u += "/api/v1"
        return u
    }

    private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")
}
