package kotobaya.render

import kotobaya.cards.TemplateFields.escapeHtml
import kotobaya.contracts.render.DictionaryData

/** How text of a field is turned into HTML (`{{key}}`, `{{furigana:key}}`, `{{kana:key}}`, `{{kanji:key}}`, `{{text:key}}`). */
internal enum class TextMode { HTML, FURIGANA, KANA, KANJI, TEXT }

/** Bracket furigana (`漢字[かんじ]`, Anki style) and `[sound:<sha256>]` handling for decoded text. */
internal object Furigana {
    /** Anki's rule: the base runs back to the previous space or bracket; one leading space is a separator and is consumed. */
    private val bracket = Regex("""( ?)([^ \[\]]+)\[([^\[\]]+)\]""")
    private val sound = Regex("""\[sound:([0-9a-fA-F]{64})\]""")

    /** Decoded [text] to HTML under [mode] (never [TextMode.TEXT]); `showRuby = false` turns furigana into base text. */
    fun toHtml(text: String, mode: TextMode, showRuby: Boolean): String {
        val sb = StringBuilder()
        var pos = 0
        for (m in sound.findAll(text)) {
            sb.append(brackets(text.substring(pos, m.range.first), mode, showRuby))
            val hash = m.groupValues[1].lowercase()
            sb.append("<button class=\"kb-audio\" data-kb-audio=\"").append(hash).append("\"></button>")
            pos = m.range.last + 1
        }
        sb.append(brackets(text.substring(pos), mode, showRuby))
        return sb.toString()
    }

    private fun brackets(text: String, mode: TextMode, showRuby: Boolean): String {
        if (mode == TextMode.HTML || mode == TextMode.TEXT) return escapeHtml(text)
        val effective = if (mode == TextMode.FURIGANA && !showRuby) TextMode.KANJI else mode
        val sb = StringBuilder()
        var pos = 0
        for (m in bracket.findAll(text)) {
            sb.append(escapeHtml(text.substring(pos, m.range.first)))
            val base = escapeHtml(m.groupValues[2])
            val reading = escapeHtml(m.groupValues[3])
            when (effective) {
                TextMode.FURIGANA -> sb.append("<ruby>").append(base).append("<rt>").append(reading).append("</rt></ruby>")
                TextMode.KANA -> sb.append(reading)
                else -> sb.append(base)
            }
            pos = m.range.last + 1
        }
        sb.append(escapeHtml(text.substring(pos)))
        return sb.toString()
    }

    /**
     * The dictionary reading as bracket furigana, e.g. `食[た]べる`, `食[た]べ 物[もの]` (a space before
     * each annotated segment that follows other text, so the bracket base stays the segment alone).
     * No alignment: `expression[reading]`, or just the reading when it equals the expression.
     */
    fun bracketOf(d: DictionaryData): String {
        if (d.furigana.isEmpty()) {
            return if (d.reading.isEmpty() || d.reading == d.expression) d.expression.ifEmpty { d.reading } else "${d.expression}[${d.reading}]"
        }
        val sb = StringBuilder()
        for (seg in d.furigana) {
            if (seg.reading.isNullOrEmpty()) {
                sb.append(seg.text)
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(seg.text).append('[').append(seg.reading).append(']')
            }
        }
        return sb.toString()
    }
}
