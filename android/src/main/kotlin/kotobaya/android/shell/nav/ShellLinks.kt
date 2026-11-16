package kotobaya.android.shell.nav

import android.content.Intent
import java.net.URLDecoder
import kotobaya.android.intake.IntakeParser
import kotobaya.android.intake.IntakeTarget
import kotobaya.android.shell.web.NavPath

/**
 * Native intent → SPA path (SHELL.md 6.1, design table of ADR-036 section 4). The result always satisfies the
 * path rule, else it is `/`. Query values are percent-encoded with `encodeURIComponent` rules.
 */
object ShellLinks {
    /** Extra carrying an already-computed path from [kotobaya.android.intake.IntakeActivity] to `MainActivity`. */
    const val EXTRA_PATH: String = "kotobaya.shell.path"

    private const val MAX_PATH = 2048
    private val DIGITS = Regex("^[0-9]{1,18}$")
    private const val HEX = "0123456789ABCDEF"

    /** The path to show for [intent], or null for a plain launch (no navigation wanted). */
    fun target(intent: Intent?): String? {
        if (intent == null) return null
        intent.getStringExtra(EXTRA_PATH)?.let { return NavPath.sanitize(it) }
        return pathForOrNull(intent)
    }

    /** Like [pathForOrNull] but `/` for a plain launch. */
    fun pathFor(intent: Intent?): String = pathForOrNull(intent) ?: "/"

    fun pathForOrNull(intent: Intent?): String? = intent?.let {
        pathForOrNull(
            action = it.action,
            mimeType = it.type,
            extraText = runCatching { it.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull(),
            processText = runCatching { it.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() }.getOrNull(),
            data = it.dataString,
        )
    }

    fun pathFor(action: String?, mimeType: String?, extraText: String?, processText: String?, data: String?): String =
        pathForOrNull(action, mimeType, extraText, processText, data) ?: "/"

    /** Null for actions that do not ask for navigation (MAIN, unknown). */
    fun pathForOrNull(action: String?, mimeType: String?, extraText: String?, processText: String?, data: String?): String? {
        val path = when (action) {
            IntakeParser.ACTION_SEND, IntakeParser.ACTION_PROCESS_TEXT -> intake(action, mimeType, extraText, processText, data)
            IntakeParser.ACTION_VIEW -> view(action, mimeType, extraText, processText, data)
            else -> return null
        }
        return NavPath.sanitize(path)
    }

    private fun intake(action: String?, mimeType: String?, extraText: String?, processText: String?, data: String?): String =
        when (val t = IntakeParser.parse(action, mimeType, extraText, processText, data)) {
            is IntakeTarget.AddNote -> search(t)
            else -> "/"
        }

    private fun view(action: String?, mimeType: String?, extraText: String?, processText: String?, data: String?): String {
        val uri = data ?: return "/"
        val prefix = "${IntakeParser.SCHEME}://"
        if (!uri.startsWith(prefix, ignoreCase = true)) return "/"
        val rest = uri.substring(prefix.length).substringBefore('#')
        val pathPart = rest.substringBefore('?')
        val host = pathPart.substringBefore('/').lowercase()
        val tail = pathPart.substringAfter('/', "").trimEnd('/')
        return when (host) {
            "review" -> "/review"
            "settings" -> "/settings"
            "media" -> "/media"
            // hot-word widget: `kotobaya://struggle/word/<seq>` opens the word (the table's "Hot-word widget" row)
            "struggle" -> {
                val seq = tail.removePrefix("word/").takeIf { tail.startsWith("word/") }
                if (seq != null && DIGITS.matches(seq)) "/dictionary?seq=$seq" else "/struggle"
            }
            "word" -> {
                val seq = runCatching { URLDecoder.decode(tail, Charsets.UTF_8) }.getOrNull()
                if (seq != null && DIGITS.matches(seq)) "/dictionary?seq=$seq" else "/"
            }
            "pair" -> pair(uri)
            "add" -> intake(action, mimeType, extraText, processText, data)
            else -> "/"
        }
    }

    private fun pair(uri: String): String {
        val path = "/login?pair=${encode(uri.substringBefore('#'))}"
        return if (path.length <= MAX_PATH) path else "/"
    }

    private fun search(t: IntakeTarget.AddNote): String {
        val q = t.word.ifEmpty { t.reading ?: t.sentence.orEmpty() }
        if (q.isEmpty()) return "/"
        val sentence = if (t.word.isNotEmpty()) t.sentence else null
        val full = build(q, sentence)
        if (full.length <= MAX_PATH) return full
        if (build(q, null).length <= MAX_PATH) return build(q, null)
        // shorten q: the longest code-point prefix that fits (binary search)
        var lo = 0
        var hi = q.codePointCount(0, q.length)
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (build(q.substring(0, q.offsetByCodePoints(0, mid)), null).length <= MAX_PATH) lo = mid else hi = mid - 1
        }
        if (lo == 0) return "/"
        return build(q.substring(0, q.offsetByCodePoints(0, lo)), null)
    }

    private fun build(q: String, sentence: String?): String =
        "/search?q=${encode(q)}" + (if (sentence != null) "&sentence=${encode(sentence)}" else "")

    /** `encodeURIComponent` restricted to the path rule's alphabet: only `A-Za-z0-9-._~` stay literal. */
    internal fun encode(s: String): String {
        val sb = StringBuilder(s.length * 3)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~')) {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
        }
        return sb.toString()
    }
}
