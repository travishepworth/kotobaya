package kotobaya.render

/** Which side of bracket furigana (`漢字[かんじ]`) [FieldPlainText] keeps. Ruby `<rt>` / `<rp>` are always dropped. */
public enum class PlainTextMode {
    /** The base text: `{{kanji:key}}` (the expression a word is judged and shown by). */
    KANJI,

    /** The bracket reading: `{{kana:key}}` (the reading of the furigana front). */
    KANA,
}

/**
 * The plain text of one stored field value, exactly as the renderers read it (CORE-28b; ADR-042 furigana
 * front, the "text of a field" of `CardTextRenderer`): the value is sanitised like any field (disallowed
 * markup such as `<img onerror=…>` dropped, sentinels stripped), ruby readings are dropped, bracket
 * furigana is reduced per [PlainTextMode], entities are decoded (`a&amp;b` → `a&b`), `<br>` and block
 * boundaries become `\n`, spaces are collapsed and blank lines dropped. The result is text, not HTML:
 * escape it before putting it into markup.
 *
 * The renderers' furigana front is built from this text (expression in [PlainTextMode.KANJI], reading in
 * [PlainTextMode.KANA]), so a server or device that derives kanji requirements or the front flag from a
 * field must use this function instead of its own tag stripping. It does not fall back to dictionary data
 * when the value is blank; the caller does (the renderer uses the dictionary expression / reading).
 */
public object FieldPlainText {
    /** Plain text of [value] (an HTML fragment, may be null); `""` for null or blank. */
    public fun of(value: String?, mode: PlainTextMode = PlainTextMode.KANJI): String {
        if (value.isNullOrBlank()) return ""
        return lines(value, if (mode == PlainTextMode.KANA) TextMode.KANA else TextMode.KANJI).joinToString("\n")
    }

    internal fun lines(value: String, mode: TextMode, warnings: MutableSet<String> = LinkedHashSet()): List<String> {
        val html = Sanitizer(showRuby = false, mediaUrl = { it }, warnings = warnings).sanitize(Sanitizer.stripSentinels(value), mode)
        return DefaultCardTextRenderer.htmlLines(html)
    }
}
