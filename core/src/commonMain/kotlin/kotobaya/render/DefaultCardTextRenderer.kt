package kotobaya.render

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.CardText
import kotobaya.contracts.render.CardTextRenderer
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.render.TextRenderInput

/**
 * The one [CardTextRenderer] (ARCH-CARD-TEXT). Common code like [DefaultCardRenderer], whose field
 * resolution ([DefaultCardRenderer.resolveField]) and [Sanitizer] it reuses, so a field reads the same
 * in the HTML and the text render on every platform.
 */
public object DefaultCardTextRenderer : CardTextRenderer {
    private const val BLANK = "＿＿＿"
    private const val MARK_OPEN = "【"
    private const val MARK_CLOSE = "】"
    private const val DOWNSTEP = "ꜜ"
    private val BLOCK = setOf("div", "p", "ul", "ol", "li", "br", "hr")
    private val SPACE = Regex("[ \\t\\r\\u000C\\u00A0]+")
    private val LINE_BREAK = Regex("\r\n|\n|\r")
    private val FALLBACK_OPTIONS = RenderOptions(furigana = false, pitch = false, showMnemonic = false, mediaUrlTemplate = "{sha256}")

    override fun renderText(input: TextRenderInput): CardText {
        val ctx = Ctx(input)
        val base = input.noteType.baseType
        val template = input.noteType.templates.firstOrNull { it.kind == input.kind }
        val custom = template != null && (template.front != null || template.back != null)
        val sides = if (custom) null else ctx.rule(base, input.kind)
        val front: List<String>
        val backLines: List<String>
        if (sides != null) {
            front = sides.first
            backLines = sides.second
        } else {
            // only the HTML fallback de-duplicates: it removes what `{{FrontSide}}` repeated on the back
            val (f, back) = ctx.fallback()
            front = f
            backLines = ArrayList<String>().also { out -> for (line in back) if (line !in f && line !in out) out.add(line) }
        }
        val reading = when (base) {
            NoteBaseType.VOCAB, NoteBaseType.PHRASE, NoteBaseType.SENTENCE -> ctx.field("reading", TextMode.KANA)
            NoteBaseType.KANJI -> ctx.field("readings")
            NoteBaseType.GRAMMAR, NoteBaseType.CUSTOM -> emptyList()
        }
        val sentence = if (base == NoteBaseType.SENTENCE) emptyList() else ctx.field("sentence", TextMode.KANJI)
        return CardText(
            front = front.joinToString("\n"),
            back = backLines.joinToString("\n"),
            reading = reading.joinToString("\n").ifEmpty { null },
            sentence = sentence.joinToString("\n").ifEmpty { null },
            warnings = ctx.warnings.map { "render.warning.$it" },
        )
    }

    /** Lines of plain [text]: split at line breaks, spaces collapsed and trimmed (not U+3000), empty lines dropped. */
    internal fun lines(text: String): List<String> =
        LINE_BREAK.split(text).mapNotNull { line ->
            SPACE.replace(line, " ").trim { it == ' ' }.takeIf { it.isNotEmpty() }
        }

    /** Lines of a sanitised HTML fragment: tags removed, entities decoded, block boundaries and `<br>` are line breaks. */
    internal fun htmlLines(html: String): List<String> {
        val sb = StringBuilder()
        var skip: String? = null
        var depth = 0
        for (tok in HtmlTokenizer.tokenize(html)) {
            if (skip != null) {
                if (tok is HtmlToken.Start && tok.name == skip) depth++
                if (tok is HtmlToken.End && tok.name == skip && --depth == 0) skip = null
                continue
            }
            when (tok) {
                is HtmlToken.Text -> sb.append(tok.text)
                is HtmlToken.Start -> when (tok.name) {
                    in BLOCK -> sb.append('\n')
                    "rt", "rp" -> {
                        skip = tok.name
                        depth = 1
                    }
                    else -> Unit
                }
                is HtmlToken.End -> if (tok.name in BLOCK) sb.append('\n')
            }
        }
        return lines(Sanitizer.stripSentinels(sb.toString()))
    }

    private class Ctx(val input: TextRenderInput) {
        val warnings = LinkedHashSet<String>()
        private val sanitizer = Sanitizer(showRuby = false, mediaUrl = { it }, warnings = warnings)

        /** Text lines of a field under [mode] (resolved like the HTML renderer). */
        fun field(key: String, mode: TextMode = TextMode.HTML): List<String> {
            val raw = DefaultCardRenderer.resolveField(input.fields, input.dictionary, input.kanji, input.noteType.baseType, key).first
            if (raw.isBlank()) return emptyList()
            return htmlLines(sanitizer.sanitize(Sanitizer.stripSentinels(raw), mode))
        }

        private fun expression() = field("expression", TextMode.KANJI)
        private fun reading() = field("reading", TextMode.KANA)
        private fun meaning() = field("meaning")

        /** The table of the [CardTextRenderer] contract: (front lines, back lines), or null when (base, kind) is not in it. */
        fun rule(base: NoteBaseType, kind: String): Pair<List<String>, List<String>>? = when (base) {
            NoteBaseType.VOCAB, NoteBaseType.PHRASE -> when (kind) {
                "meaning" -> expression() to reading() + meaning()
                "reading" -> if (base == NoteBaseType.VOCAB) expression() to reading() + meaning() else null
                "listening" -> emptyList<String>() to expression() + reading() + meaning()
                "production" -> meaning() to expression() + reading()
                // the HTML front is expression + {{kana:reading}}: the question is the pitch, not the reading
                "pitch" -> if (base == NoteBaseType.VOCAB) expression() + reading() to pitch() else null
                else -> null
            }
            NoteBaseType.KANJI -> when (kind) {
                "keyword" -> field("literal") to field("keyword") + field("readings")
                "writing" -> field("keyword") to field("literal") + field("readings")
                "components" -> field("literal") to field("notes")
                else -> null
            }
            NoteBaseType.GRAMMAR -> when (kind) {
                "cloze", "spot" -> cloze(kind)
                "recognition" -> field("pattern") to meaning()
                "production" -> meaning() to field("pattern")
                else -> null
            }
            NoteBaseType.SENTENCE -> when (kind) {
                "comprehension" -> field("sentence", TextMode.KANJI) to reading() + field("translation")
                "listening" -> emptyList<String>() to field("sentence", TextMode.KANJI) + reading() + field("translation")
                else -> null
            }
            NoteBaseType.CUSTOM -> if (kind == "basic") field("front") to field("back") else null
        }

        private fun cloze(kind: String): Pair<List<String>, List<String>> {
            val c = input.cloze
            if (c == null) {
                warnings.add("missing_cloze")
                return field("pattern") to meaning()
            }
            val text = Sanitizer.stripSentinels(c.text)
            val valid = c.start >= 0 && c.start < c.end && c.end <= text.length && text.length == c.text.length
            if (!valid) warnings.add("invalid_cloze")
            fun marked(inner: String) = if (!valid) text else text.substring(0, c.start) + inner + text.substring(c.end)
            val translation = c.translation?.let { lines(Sanitizer.stripSentinels(it)) }.orEmpty()
            val answer = lines(marked(MARK_OPEN + (if (valid) text.substring(c.start, c.end) else "") + MARK_CLOSE)) +
                field("pattern") + meaning()
            // cloze shows the translation as the hint on the front; spot reveals it on the back
            return if (kind == "cloze") lines(marked(BLANK)) + translation to answer else lines(text) to answer + translation
        }

        /** The pitch line of the contract, or empty. */
        private fun pitch(): List<String> {
            val d = input.dictionary ?: return emptyList()
            val morae = Pitch.morae(Sanitizer.stripSentinels(d.reading))
            if (morae.isEmpty()) return emptyList()
            val patterns = d.pitchDrops.filter { it in 0..morae.size }.map { drop ->
                morae.mapIndexed { i, m -> if (i + 1 == drop) m + DOWNSTEP else m }.joinToString("")
            }.distinct()
            return lines(patterns.joinToString(" / "))
        }

        /** Front and back lines of the HTML render (custom templates, unknown kinds). */
        fun fallback(): Pair<List<String>, List<String>> {
            fun side(side: CardSide): List<String> {
                val out = DefaultCardRenderer.render(
                    RenderInput(input.noteType, input.kind, input.fields, "", input.dictionary, input.cloze, side, FALLBACK_OPTIONS, input.kanji),
                )
                out.warnings.forEach { warnings.add(it.removePrefix("render.warning.")) }
                return htmlLines(out.html)
            }
            return side(CardSide.FRONT) to side(CardSide.BACK)
        }
    }
}
