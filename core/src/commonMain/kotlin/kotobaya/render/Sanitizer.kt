package kotobaya.render

import kotobaya.cards.TemplateFields.escapeHtml
import kotobaya.contracts.render.CardClasses

/**
 * The strict allowlist sanitiser of the `CardRenderer` contract, applied to field values and to
 * templates alike. Output is well-formed: every element it opens it closes, every text is escaped,
 * and only the contract's elements and attributes appear.
 *
 * @param showRuby false drops `rt`/`rp` (with content) and unwraps `ruby`/`rb` (furigana off).
 * @param mediaUrl maps a lowercase 64-hex blob hash to the host URL written into `img src`.
 * @param warnings receives `dropped_tag` / `dropped_attribute` codes.
 */
internal class Sanitizer(
    private val showRuby: Boolean,
    private val mediaUrl: (String) -> String,
    private val warnings: MutableSet<String>,
) {
    fun sanitize(html: String, mode: TextMode): String {
        val out = StringBuilder()
        val stack = ArrayList<String>()
        var skipName: String? = null
        var skipDepth = 0
        for (tok in HtmlTokenizer.tokenize(html)) {
            if (skipName != null) {
                if (tok is HtmlToken.Start && tok.name == skipName && tok.name !in VOID) skipDepth++
                if (tok is HtmlToken.End && tok.name == skipName && --skipDepth == 0) skipName = null
                continue
            }
            when (tok) {
                is HtmlToken.Text -> out.append(
                    if (mode == TextMode.TEXT) escapeHtml(tok.text) else Furigana.toHtml(tok.text, mode, showRuby),
                )
                is HtmlToken.Start -> {
                    val name = tok.name
                    val hideRuby = !showRuby || mode == TextMode.TEXT
                    when {
                        name in REMOVED -> {
                            warnings.add("dropped_tag")
                            if (name !in VOID) {
                                skipName = name
                                skipDepth = 1
                            }
                        }
                        hideRuby && (name == "rt" || name == "rp") -> {
                            skipName = name
                            skipDepth = 1
                        }
                        hideRuby && (name == "ruby" || name == "rb") -> Unit
                        mode == TextMode.TEXT -> Unit
                        name !in ALLOWED -> warnings.add("dropped_tag")
                        else -> {
                            val attrs = attributes(name, tok.attrs)
                            if (attrs == null) {
                                warnings.add("dropped_tag")
                            } else {
                                out.append('<').append(name).append(attrs).append('>')
                                if (name !in VOID) stack.add(name)
                            }
                        }
                    }
                }
                is HtmlToken.End -> {
                    val at = stack.lastIndexOf(tok.name)
                    if (at >= 0) {
                        while (stack.size > at) out.append("</").append(stack.removeAt(stack.size - 1)).append('>')
                    }
                }
            }
        }
        while (stack.isNotEmpty()) out.append("</").append(stack.removeAt(stack.size - 1)).append('>')
        return out.toString()
    }

    /** Serialised allowed attributes of [name] (leading spaces), or null if the element must be dropped (`img` without a blob src). */
    private fun attributes(name: String, attrs: List<Pair<String, String>>): String? {
        val seen = HashSet<String>()
        var cls: String? = null
        var lang: String? = null
        var src: String? = null
        var alt: String? = null
        var audio: String? = null
        for ((k, v) in attrs) {
            if (!seen.add(k)) continue // HTML: the first of duplicate attributes wins
            when {
                k == "class" -> {
                    val tokens = v.split(WS).filter { it.isNotEmpty() }
                    val kept = tokens.filter { allowedClass(it) }
                    if (kept.size < tokens.size) warnings.add("dropped_attribute")
                    cls = kept.joinToString(" ").ifEmpty { null }
                }
                k == "lang" && LANG.matches(v) -> lang = v
                name == "img" && k == "src" -> src = BLOB.matchEntire(v.trim())?.groupValues?.get(1)?.lowercase()
                name == "img" && k == "alt" -> alt = stripSentinels(v)
                name == "button" && k == "data-kb-audio" && SHA.matches(v.trim()) -> audio = v.trim().lowercase()
                else -> warnings.add("dropped_attribute")
            }
        }
        if (name == "img" && src == null) return null
        val sb = StringBuilder()
        cls?.let { sb.append(" class=\"").append(escapeHtml(it)).append('"') }
        lang?.let { sb.append(" lang=\"").append(escapeHtml(it)).append('"') }
        src?.let { sb.append(" src=\"").append(escapeHtml(stripSentinels(mediaUrl(it)))).append('"') }
        alt?.let { sb.append(" alt=\"").append(escapeHtml(it)).append('"') }
        audio?.let { sb.append(" data-kb-audio=\"").append(it).append('"') }
        return sb.toString()
    }

    private fun allowedClass(c: String): Boolean =
        c in CLASSES || (c.startsWith(CardClasses.USER_PREFIX) && USER_CLASS.matches(c))

    companion object {
        val ALLOWED: Set<String> = setOf(
            "div", "span", "p", "br", "hr", "b", "i", "u", "em", "strong", "small", "sub", "sup",
            "ruby", "rb", "rt", "rp", "ul", "ol", "li", "img", "button",
        )
        val REMOVED: Set<String> = setOf("script", "style", "iframe", "object", "embed", "svg", "math", "template")
        private val VOID = setOf("br", "hr", "img", "embed", "wbr", "input", "meta", "link", "source", "area", "base", "col", "param", "track")
        private val CLASSES = CardClasses.ALL.toSet()
        private val WS = Regex("\\s+")
        private val USER_CLASS = Regex("user-[A-Za-z0-9_-]+")
        private val LANG = Regex("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*")
        private val BLOB = Regex("blob:([0-9a-fA-F]{64})")
        private val SHA = Regex("[0-9a-fA-F]{64}")

        /** Private-use characters the renderer uses as placeholder sentinels. */
        const val SENTINEL_OPEN: Char = '\uE000'
        const val SENTINEL_CLOSE: Char = '\uE001'

        fun stripSentinels(s: String): String =
            if (SENTINEL_OPEN in s || SENTINEL_CLOSE in s) s.filter { it != SENTINEL_OPEN && it != SENTINEL_CLOSE } else s
    }
}
