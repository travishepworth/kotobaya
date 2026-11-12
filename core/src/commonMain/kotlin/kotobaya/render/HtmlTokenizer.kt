package kotobaya.render

/** A token of the lenient HTML tokenizer used by [Sanitizer]. Text is entity-decoded. */
internal sealed interface HtmlToken {
    class Text(val text: String) : HtmlToken

    class Start(val name: String, val attrs: List<Pair<String, String>>) : HtmlToken

    class End(val name: String) : HtmlToken
}

/**
 * A small, lenient HTML tokenizer. It never throws; anything that is not a well-formed tag is text
 * (`<` not followed by a letter) or dropped (comments, `<!…>`, `<?…>`, tags cut off by the end of input).
 * Raw-text elements (`script`, `style`, …) yield one [HtmlToken.Text] for their whole content, so their
 * content can never be mistaken for markup.
 */
internal object HtmlTokenizer {
    private val RAW_TEXT = setOf("script", "style", "iframe", "xmp", "noembed", "noframes", "noscript", "textarea", "title")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "middot" to "·", "times" to "×",
    )

    fun tokenize(s: String): List<HtmlToken> {
        val out = ArrayList<HtmlToken>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                out.add(HtmlToken.Text(text.toString()))
                text.clear()
            }
        }
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '&') {
                i = decodeEntity(s, i, text)
                continue
            }
            if (c != '<') {
                text.append(c)
                i++
                continue
            }
            val next = if (i + 1 < n) s[i + 1] else ' '
            when {
                s.startsWith("<!--", i) -> {
                    flush()
                    val end = s.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                }
                next == '!' || next == '?' -> {
                    flush()
                    val end = s.indexOf('>', i)
                    i = if (end < 0) n else end + 1
                }
                next == '/' -> {
                    flush()
                    val end = s.indexOf('>', i)
                    if (end < 0) {
                        i = n
                    } else {
                        if (i + 2 < n && isLetter(s[i + 2])) {
                            var j = i + 2
                            while (j < end && !s[j].isWhitespace() && s[j] != '/') j++
                            out.add(HtmlToken.End(s.substring(i + 2, j).lowercase()))
                        }
                        i = end + 1
                    }
                }
                isLetter(next) -> {
                    flush()
                    i = parseStartTag(s, i, out)
                }
                else -> {
                    text.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** Parses the start tag at [start] (`s[start] == '<'`); returns the index after it (and after raw-text content). */
    private fun parseStartTag(s: String, start: Int, out: MutableList<HtmlToken>): Int {
        val n = s.length
        var i = start + 1
        while (i < n && !s[i].isWhitespace() && s[i] != '/' && s[i] != '>') i++
        val name = s.substring(start + 1, i).lowercase()
        val attrs = ArrayList<Pair<String, String>>()
        while (true) {
            while (i < n && (s[i].isWhitespace() || s[i] == '/')) i++
            if (i >= n) return n // cut off by end of input: dropped
            if (s[i] == '>') {
                i++
                break
            }
            val nameStart = i
            while (i < n && !s[i].isWhitespace() && s[i] != '/' && s[i] != '>' && (s[i] != '=' || i == nameStart)) i++
            val attrName = s.substring(nameStart, i).lowercase()
            var j = i
            while (j < n && s[j].isWhitespace()) j++
            var value = ""
            if (j < n && s[j] == '=') {
                j++
                while (j < n && s[j].isWhitespace()) j++
                if (j >= n) return n
                val q = s[j]
                if (q == '"' || q == '\'') {
                    val end = s.indexOf(q, j + 1)
                    if (end < 0) return n
                    value = decodeAll(s.substring(j + 1, end))
                    j = end + 1
                } else {
                    val vs = j
                    while (j < n && !s[j].isWhitespace() && s[j] != '>') j++
                    value = decodeAll(s.substring(vs, j))
                }
                i = j
            }
            attrs.add(attrName to value)
        }
        out.add(HtmlToken.Start(name, attrs))
        if (name in RAW_TEXT) {
            val close = indexOfCloseTag(s, i, name)
            val content = s.substring(i, if (close < 0) n else close)
            if (content.isNotEmpty()) out.add(HtmlToken.Text(if (name == "textarea" || name == "title") decodeAll(content) else content))
            if (close < 0) return n
            out.add(HtmlToken.End(name))
            val gt = s.indexOf('>', close)
            return if (gt < 0) n else gt + 1
        }
        if (name == "plaintext") {
            if (i < n) out.add(HtmlToken.Text(s.substring(i)))
            return n
        }
        return i
    }

    private fun indexOfCloseTag(s: String, from: Int, name: String): Int {
        var i = from
        while (true) {
            val k = s.indexOf("</", i)
            if (k < 0) return -1
            val after = k + 2 + name.length
            if (s.regionMatches(k + 2, name, 0, name.length, ignoreCase = true) &&
                (after >= s.length || s[after].isWhitespace() || s[after] == '/' || s[after] == '>')
            ) return k
            i = k + 2
        }
    }

    private fun decodeAll(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '&') i = decodeEntity(s, i, sb) else sb.append(s[i++])
        }
        return sb.toString()
    }

    /** Decodes the entity at [start] (`s[start] == '&'`) into [sb]; returns the next index. Unknown: a literal `&`. */
    private fun decodeEntity(s: String, start: Int, sb: StringBuilder): Int {
        val n = s.length
        var i = start + 1
        if (i < n && s[i] == '#') {
            i++
            val hex = i < n && (s[i] == 'x' || s[i] == 'X')
            if (hex) i++
            val ds = i
            val maxDigits = if (hex) 7 else 8 // fits an Int
            while (i < n && i - ds < maxDigits && (if (hex) s[i].isHexDigit() else s[i] in '0'..'9')) i++
            if (i == ds) {
                sb.append('&')
                return start + 1
            }
            val cp = s.substring(ds, i).toInt(if (hex) 16 else 10)
            if (i < n && s[i] == ';') i++
            appendCodePoint(sb, cp)
            return i
        }
        val ns = i
        while (i < n && i - ns < 10 && (s[i] in 'a'..'z' || s[i] in 'A'..'Z')) i++
        if (i < n && s[i] == ';') {
            val v = NAMED[s.substring(ns, i)]
            if (v != null) {
                sb.append(v)
                return i + 1
            }
        }
        sb.append('&')
        return start + 1
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        when {
            // the renderer's sentinels (U+E000/U+E001) must not be smuggled in through entities
            cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF || cp == 0xE000 || cp == 0xE001 -> sb.append('\uFFFD')
            cp < 0x10000 -> sb.append(cp.toChar())
            else -> {
                val v = cp - 0x10000
                sb.append((0xD800 + (v shr 10)).toChar())
                sb.append((0xDC00 + (v and 0x3FF)).toChar())
            }
        }
    }

    private fun isLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'
}
