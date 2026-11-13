package kotobaya.android.intake

import java.net.URLDecoder

/** What an incoming intent asks for. */
sealed interface IntakeTarget {
    /** Prefill the confirm sheet. [trimmed]: something was cut to [IntakeParser.MAX_CHARS]. */
    data class AddNote(
        val word: String,
        val reading: String?,
        val sentence: String?,
        val origin: String,
        val trimmed: Boolean,
    ) : IntakeTarget

    /** `kotobaya://word/<id>`. */
    data class OpenWord(val id: String) : IntakeTarget

    data class Invalid(val reason: InvalidReason) : IntakeTarget
}

enum class InvalidReason { EMPTY, MISSING_WORD, MALFORMED, UNSUPPORTED }

/** Pure parsing of the share / PROCESS_TEXT / deep-link inputs (no Android types, so plain JVM tests). */
object IntakeParser {
    const val MAX_CHARS: Int = 2000
    const val ACTION_SEND: String = "android.intent.action.SEND"
    const val ACTION_PROCESS_TEXT: String = "android.intent.action.PROCESS_TEXT"
    const val ACTION_VIEW: String = "android.intent.action.VIEW"
    const val SCHEME: String = "kotobaya"
    const val ORIGIN_SHARE: String = "share"
    const val ORIGIN_LINK: String = "deeplink"

    /** Text up to this long with no spaces or sentence punctuation is taken to be a word, longer text a sentence. */
    private const val WORD_MAX = 24
    private val sentenceMarks = Regex("[\\s。！？!?.,、，]")
    private val wordId = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun parse(action: String?, mimeType: String?, extraText: String?, processText: String?, data: String?): IntakeTarget = when (action) {
        ACTION_SEND -> if (mimeType != null && !mimeType.startsWith("text/")) {
            IntakeTarget.Invalid(InvalidReason.UNSUPPORTED)
        } else {
            fromText(extraText, ORIGIN_SHARE)
        }
        ACTION_PROCESS_TEXT -> fromText(processText, ORIGIN_SHARE)
        ACTION_VIEW -> fromUri(data)
        else -> IntakeTarget.Invalid(InvalidReason.UNSUPPORTED)
    }

    private fun fromText(raw: String?, origin: String): IntakeTarget {
        val (text, cut) = cap(raw?.trim().orEmpty())
        if (text.isEmpty()) return IntakeTarget.Invalid(InvalidReason.EMPTY)
        val isWord = text.codePointCount(0, text.length) <= WORD_MAX && !sentenceMarks.containsMatchIn(text)
        return if (isWord) {
            IntakeTarget.AddNote(text, null, null, origin, cut)
        } else {
            IntakeTarget.AddNote("", null, text, origin, cut)
        }
    }

    private fun fromUri(uri: String?): IntakeTarget {
        if (uri.isNullOrBlank()) return IntakeTarget.Invalid(InvalidReason.EMPTY)
        val prefix = "$SCHEME://"
        if (!uri.startsWith(prefix, ignoreCase = true)) return IntakeTarget.Invalid(InvalidReason.UNSUPPORTED)
        val rest = uri.substring(prefix.length).substringBefore('#')
        val pathPart = rest.substringBefore('?')
        val query = if ('?' in rest) rest.substringAfter('?') else ""
        val host = pathPart.substringBefore('/')
        val path = pathPart.substringAfter('/', "")
        return when (host.lowercase()) {
            "add" -> addUri(query)
            "word" -> {
                val id = decode(path.trimEnd('/')) ?: return IntakeTarget.Invalid(InvalidReason.MALFORMED)
                if (wordId.matches(id)) IntakeTarget.OpenWord(id) else IntakeTarget.Invalid(InvalidReason.MALFORMED)
            }
            else -> IntakeTarget.Invalid(InvalidReason.UNSUPPORTED)
        }
    }

    private fun addUri(query: String): IntakeTarget {
        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('=')) ?: return IntakeTarget.Invalid(InvalidReason.MALFORMED)
            val value = decode(pair.substringAfter('=', "")) ?: return IntakeTarget.Invalid(InvalidReason.MALFORMED)
            params.putIfAbsent(key, value) // first one wins, like Uri.getQueryParameter
        }
        val (word, c1) = cap(params["word"]?.trim().orEmpty())
        val (reading, c2) = cap(params["reading"]?.trim().orEmpty())
        val (sentence, c3) = cap(params["sentence"]?.trim().orEmpty())
        if (word.isEmpty() && reading.isEmpty()) return IntakeTarget.Invalid(InvalidReason.MISSING_WORD)
        return IntakeTarget.AddNote(word, reading.ifEmpty { null }, sentence.ifEmpty { null }, ORIGIN_LINK, c1 || c2 || c3)
    }

    /** Percent-decoding as UTF-8 ('+' is a space); null when malformed. */
    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Cuts to [MAX_CHARS] without splitting a surrogate pair. */
    internal fun cap(s: String): Pair<String, Boolean> {
        if (s.length <= MAX_CHARS) return s to false
        var end = MAX_CHARS
        if (Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end) to true
    }
}
