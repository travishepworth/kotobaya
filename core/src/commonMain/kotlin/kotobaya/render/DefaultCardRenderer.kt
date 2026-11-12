package kotobaya.render

import kotobaya.cards.TemplateFields.escapeHtml
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.render.CardRenderer
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.ClozeSentence
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.render.RenderedCard

/**
 * The one [CardRenderer] (ADR-002). All logic is common code, so jvm, js and android produce the
 * same string for the same input.
 *
 * Pipeline per side: pick the template (custom or built-in), resolve sections, replace every value
 * placeholder by a sentinel, run the whole template through the [Sanitizer], then put the
 * separately sanitised values back in place of the sentinels. Values therefore can never change the
 * structure the template builds, and the template itself obeys the same allowlist as values.
 */
public object DefaultCardRenderer : CardRenderer {
    private const val FRONT_SIDE_PLACEHOLDER = "{{FrontSide}}"
    private val placeholder = Regex("""\{\{\s*([#^/]?)\s*([^{}]*?)\s*\}\}""")
    private val keyPattern = Regex("[a-z][a-z0-9_]*")
    private val sentinelRef = Regex("${Sanitizer.SENTINEL_OPEN}(\\d+)${Sanitizer.SENTINEL_CLOSE}")
    private val audioRef = Regex("data-kb-audio=\"([0-9a-f]{64})\"")
    private val lineBreak = Regex("\r\n|\n|\r")
    private val tag = Regex("<[^>]*>")

    override fun render(input: RenderInput): RenderedCard {
        val urlTemplate = input.options.mediaUrlTemplate
        require(urlTemplate.split("{sha256}").size == 2) { "mediaUrlTemplate must contain {sha256} exactly once" }
        val ctx = Ctx(input)
        val front = ctx.side(CardSide.FRONT, null, extras = true)
        val inner = if (input.side == CardSide.FRONT) front else ctx.back(front)
        val html = "<div class=\"kb-card kb-${input.noteType.baseType.wire} kb-kind-${escapeHtml(input.kind)} " +
            (if (input.side == CardSide.FRONT) "kb-front" else "kb-back") + "\">" + inner + "</div>"
        val audio = audioRef.findAll(html).map { it.groupValues[1] }.distinct().toList()
        return RenderedCard(html, audio, input.noteType.css, ctx.warnings.map { "render.warning.$it" })
    }

    /**
     * True iff the front of [kind] for a note with these contents shows nothing (no text, image or
     * audio), not counting the sentence/image extras every built-in front carries. T201b skips such
     * kinds at note creation (e.g. `listening` without audio). Grammar `cloze`/`spot` fronts take
     * their sentence at review time, so they count as non-empty.
     */
    public fun frontIsEmpty(
        noteType: NoteTypeInfo,
        kind: String,
        fields: Map<String, String>,
        dictionary: DictionaryData? = null,
        kanji: KanjiData? = null,
    ): Boolean {
        val input = RenderInput(
            noteType = noteType,
            kind = kind,
            fields = fields,
            mnemonic = "",
            dictionary = dictionary,
            cloze = ClozeSentence(null, "…", 0, 1, null),
            side = CardSide.FRONT,
            options = RenderOptions(furigana = true, pitch = true, showMnemonic = false, mediaUrlTemplate = "{sha256}"),
            kanji = kanji,
        )
        val html = Ctx(input).side(CardSide.FRONT, null, extras = false)
        if ("<img" in html || "data-kb-audio" in html) return false
        return tag.replace(html, "").isBlank()
    }

    private class Ctx(val input: RenderInput) {
        val warnings = LinkedHashSet<String>()
        private val options = input.options
        private val sanitizer = Sanitizer(options.furigana, { options.mediaUrlTemplate.replace("{sha256}", it) }, warnings)

        fun back(front: String): String {
            val sb = StringBuilder(side(CardSide.BACK, front, extras = true))
            if (options.showMnemonic && input.mnemonic.isNotBlank()) {
                sb.append("<div class=\"kb-mnemonic\">")
                sb.append(lineBreak.split(input.mnemonic).joinToString("<br>") { escapeHtml(Sanitizer.stripSentinels(it)) })
                sb.append("</div>")
            }
            return sb.toString()
        }

        /** The sanitised inner HTML of one side. */
        fun side(side: CardSide, front: String?, extras: Boolean): String {
            val template = input.noteType.templates.firstOrNull { it.kind == input.kind }
            val custom = if (side == CardSide.FRONT) template?.front else template?.back
            if (custom != null) return expand(custom, builtIn = false, front = front)
            val builtIn = BuiltInTemplates.get(input.noteType.baseType, input.kind)
            if (builtIn == null) {
                warnings.add("unknown_kind")
                return ""
            }
            var source = if (side == CardSide.FRONT) builtIn.front else builtIn.back
            // the back usually repeats the front (and so its extras); otherwise it gets its own
            if (extras && (side == CardSide.FRONT || FRONT_SIDE_PLACEHOLDER !in source)) {
                source += BuiltInTemplates.extras(input.noteType.baseType)
            }
            return expand(source, builtIn = true, front = front)
        }

        private class Frame(val key: String, val active: Boolean)

        private fun expand(template: String, builtIn: Boolean, front: String?): String {
            val source = Sanitizer.stripSentinels(template)
            val pieces = ArrayList<String>()
            val stack = ArrayList<Frame>()
            val sb = StringBuilder()
            var pos = 0
            for (m in placeholder.findAll(source)) {
                if (stack.all { it.active }) sb.append(source, pos, m.range.first)
                pos = m.range.last + 1
                val sigil = m.groupValues[1]
                val name = m.groupValues[2]
                when (sigil) {
                    "#", "^" -> {
                        if (!keyPattern.matches(name)) warnings.add("unknown_placeholder")
                        val nonEmpty = value(name).isNotBlank()
                        stack.add(Frame(name, if (sigil == "#") nonEmpty else !nonEmpty))
                    }
                    "/" ->
                        if (stack.isNotEmpty() && stack.last().key == name) stack.removeAt(stack.size - 1)
                        else warnings.add("unbalanced_section")
                    else -> if (stack.all { it.active }) {
                        val piece = piece(name, builtIn, front)
                        if (piece.isNotEmpty()) {
                            sb.append(Sanitizer.SENTINEL_OPEN).append(pieces.size).append(Sanitizer.SENTINEL_CLOSE)
                            pieces.add(piece)
                        }
                    }
                }
            }
            if (stack.all { it.active }) sb.append(source, pos, source.length)
            if (stack.isNotEmpty()) warnings.add("unbalanced_section")
            val clean = sanitizer.sanitize(sb.toString(), TextMode.HTML)
            val filled = sentinelRef.replace(clean) { r -> pieces.getOrNull(r.groupValues[1].toInt()) ?: "" }
            return Sanitizer.stripSentinels(filled)
        }

        /** Safe HTML for one value placeholder (already sanitised), or `""`. */
        private fun piece(name: String, builtIn: Boolean, front: String?): String {
            if (name == "FrontSide") return front ?: ""
            val colon = name.indexOf(':')
            val prefix = if (colon < 0) "" else name.substring(0, colon)
            val key = if (colon < 0) name else name.substring(colon + 1)
            if (!keyPattern.matches(key)) return unknown()
            val mode = when (prefix) {
                "" -> TextMode.HTML
                "furigana" -> TextMode.FURIGANA
                "kana" -> TextMode.KANA
                "kanji" -> TextMode.KANJI
                "text" -> TextMode.TEXT
                "pitch" -> return if (builtIn) pitch() else unknown()
                "cloze" -> return if (builtIn) cloze(key) else unknown()
                else -> return unknown()
            }
            val raw = value(key)
            if (raw.isBlank()) return ""
            val html = sanitizer.sanitize(Sanitizer.stripSentinels(raw), mode)
            return if (html.isEmpty()) "" else "<span class=\"kb-field-$key\">$html</span>"
        }

        private fun unknown(): String {
            warnings.add("unknown_placeholder")
            return ""
        }

        /** The field value, filled from dictionary / kanji data when empty (as an HTML fragment). */
        fun value(key: String): String {
            val raw = input.fields[key] ?: ""
            if (raw.isNotBlank()) return raw
            val d = input.dictionary
            if (d != null) {
                val filled = when (key) {
                    "expression" -> d.expression
                    "reading" -> Furigana.bracketOf(d)
                    "meaning" -> d.glosses.joinToString("; ")
                    else -> ""
                }
                if (filled.isNotBlank()) return escapeHtml(filled)
            }
            val k = input.kanji
            if (k != null && input.noteType.baseType == NoteBaseType.KANJI) {
                val filled = when (key) {
                    "literal" -> k.literal
                    "keyword" -> k.meanings.joinToString("; ")
                    "readings" -> (k.onyomi + k.kunyomi).joinToString("、")
                    else -> ""
                }
                if (filled.isNotBlank()) return escapeHtml(filled)
            }
            return raw
        }

        private fun pitch(): String {
            val d = input.dictionary ?: return ""
            val on = options.pitch || input.kind == "pitch"
            if (!on || d.pitchDrops.isEmpty()) return ""
            val html = Pitch.html(Sanitizer.stripSentinels(d.reading), d.pitchDrops)
            return if (html.isEmpty()) "" else "<div class=\"kb-reading\">$html</div>"
        }

        private fun cloze(part: String): String {
            val c = input.cloze
            if (c == null) {
                warnings.add("missing_cloze")
                return ""
            }
            val text = Sanitizer.stripSentinels(c.text)
            val valid = c.start >= 0 && c.start < c.end && c.end <= text.length && text.length == c.text.length
            if (!valid && part in SPAN_PARTS) warnings.add("invalid_cloze")
            fun marked(cls: String, content: String): String {
                if (!valid) return escapeHtml(text)
                return escapeHtml(text.substring(0, c.start)) + "<span class=\"$cls\">" + content + "</span>" +
                    escapeHtml(text.substring(c.end))
            }
            val span = if (valid) escapeHtml(text.substring(c.start, c.end)) else ""
            val translation = c.translation?.let { Sanitizer.stripSentinels(it) }?.takeIf { it.isNotBlank() }
            return when (part) {
                "blank" -> "<div class=\"kb-sentence kb-cloze\">" + marked("kb-cloze-blank", BLANK) + "</div>"
                "answer" -> "<div class=\"kb-sentence kb-cloze\">" + marked("kb-cloze-answer", span) + "</div>"
                "spot" -> "<div class=\"kb-sentence\">" + marked("kb-spot", span) + "</div>"
                "sentence" -> "<div class=\"kb-sentence\">" + escapeHtml(text) + "</div>"
                "hint" -> translation?.let { "<div class=\"kb-hint\">" + escapeHtml(it) + "</div>" } ?: ""
                "translation" -> translation?.let { "<div class=\"kb-translation\">" + escapeHtml(it) + "</div>" } ?: ""
                else -> unknown()
            }
        }
    }

    private val SPAN_PARTS = setOf("blank", "answer", "spot")
    private const val BLANK = "＿＿＿"
}
