package kotobaya.render

import kotobaya.cards.TemplateFields.escapeHtml
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.render.CardRenderer
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.ClozeSentence
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FieldSource
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.render.RenderedCard
import kotobaya.contracts.render.RenderedField
import kotobaya.contracts.render.WordAudioRules

/**
 * The one [CardRenderer] (ADR-002). All logic is common code, so jvm, js and android produce the
 * same string for the same input.
 *
 * Pipeline per side: pick the template (custom or built-in), resolve sections, replace every value
 * placeholder by a sentinel, run the whole template through the [Sanitizer], then put the
 * separately sanitised values back in place of the sentinels. Values therefore can never change the
 * structure the template builds, and the template itself obeys the same allowlist as values.
 *
 * Field regions (C-19, `RenderOptions.fieldRegions`) are attributes on the wrapper span the renderer
 * itself writes around a sanitised value, so the sanitiser never sees (and never allows) them.
 */
public object DefaultCardRenderer : CardRenderer {
    private const val FRONT_SIDE_PLACEHOLDER = "{{FrontSide}}"
    private const val FRONT_SIDE = "FrontSide" // not a field key: keys are lowercase
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
        ctx.endFront()
        val shown = if (input.side == CardSide.FRONT) front else ctx.back(front)
        val html = "<div class=\"kb-card kb-${input.noteType.baseType.wire} kb-kind-${escapeHtml(input.kind)} " +
            (if (input.side == CardSide.FRONT) "kb-front" else "kb-back") + "\">" + shown.html + "</div>"
        val audio = audioRef.findAll(html).map { it.groupValues[1] }.distinct().toList()
        // the FRONT shown alone never emits the back-only W, so `front.frontWordAudio` is the FRONT's word audio
        val autoplay = if (input.side == CardSide.FRONT || front.frontWordAudio == null) shown.wordAudio else null
        return RenderedCard(html, audio, input.noteType.css, ctx.warnings.map { "render.warning.$it" }, ctx.fields(), shown.wordAudio, autoplay)
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
        val html = Ctx(input).side(CardSide.FRONT, null, extras = false).html
        if ("<img" in html || "data-kb-audio" in html) return false
        return tag.replace(html, "").isBlank()
    }

    /**
     * One expanded side: its HTML, the hash of the first word-audio block W it emitted ([wordAudio]), and the
     * same counting only W that the side emits when shown as itself ([frontWordAudio]: a built-in front's
     * back-only W, emitted only inside a back's `{{FrontSide}}`, does not count).
     */
    private class Expanded(val html: String, val wordAudio: String?, val frontWordAudio: String?)

    private class Ctx(val input: RenderInput) {
        val warnings = LinkedHashSet<String>()
        private val options = input.options
        private val sanitizer = Sanitizer(options.furigana, { options.mediaUrlTemplate.replace("{sha256}", it) }, warnings)

        /** Field key → "has a region", in order of first reference, for the side being expanded (C-19). */
        private var referred = LinkedHashMap<String, Boolean>()
        private var frontReferred: Map<String, Boolean> = emptyMap()

        /** Sets the front's references aside: they count on the back only where `{{FrontSide}}` is expanded. */
        fun endFront() {
            if (input.side == CardSide.FRONT) return
            frontReferred = referred
            referred = LinkedHashMap()
        }

        fun fields(): List<RenderedField> =
            if (!options.fieldRegions) emptyList() else referred.map { (key, rendered) -> RenderedField(key, resolve(key).second, rendered) }

        private fun refer(key: String, rendered: Boolean) {
            if (options.fieldRegions) referred[key] = rendered || (referred[key] ?: false)
        }

        /** The field key a value placeholder names, or null (`FrontSide`, built-in-only and unknown prefixes, bad names). */
        private fun fieldKeyOf(name: String): String? {
            val colon = name.indexOf(':')
            if (colon >= 0 && name.substring(0, colon) !in VALUE_PREFIXES) return null
            return name.substring(colon + 1).takeIf { keyPattern.matches(it) }
        }

        fun back(front: Expanded): Expanded {
            val expanded = side(CardSide.BACK, front, extras = true)
            val sb = StringBuilder(expanded.html)
            if (options.showMnemonic && input.mnemonic.isNotBlank()) {
                sb.append("<div class=\"kb-mnemonic\">")
                sb.append(lineBreak.split(input.mnemonic).joinToString("<br>") { escapeHtml(Sanitizer.stripSentinels(it)) })
                sb.append("</div>")
            }
            return Expanded(sb.toString(), expanded.wordAudio, expanded.frontWordAudio)
        }

        /** True while the front side is being expanded (the furigana front applies there only). */
        private var expandingFront = false

        /** The sanitised inner HTML of one side. */
        fun side(side: CardSide, front: Expanded?, extras: Boolean): Expanded {
            expandingFront = side == CardSide.FRONT
            val template = input.noteType.templates.firstOrNull { it.kind == input.kind }
            val custom = if (side == CardSide.FRONT) template?.front else template?.back
            if (custom != null) return expand(custom, builtIn = false, front = front)
            val builtIn = BuiltInTemplates.get(input.noteType.baseType, input.kind)
            if (builtIn == null) {
                warnings.add("unknown_kind")
                return Expanded("", null, null)
            }
            var source = if (side == CardSide.FRONT) builtIn.front else builtIn.back
            // the back usually repeats the front (and so its extras); otherwise it gets its own
            if (extras && (side == CardSide.FRONT || FRONT_SIDE_PLACEHOLDER !in source)) {
                source += BuiltInTemplates.extras(input.noteType.baseType)
            }
            return expand(source, builtIn = true, front = front)
        }

        private class Frame(val key: String, val active: Boolean)

        private fun expand(template: String, builtIn: Boolean, front: Expanded?): Expanded {
            val source = Sanitizer.stripSentinels(template)
            val pieces = ArrayList<String>()
            val pieceKeys = ArrayList<String?>() // per piece: its field key, FRONT_SIDE, or null (not a field)
            val pieceAudio = ArrayList<String?>() // per piece: the word audio it carries (W, or the front's first W)
            val pieceBackOnly = ArrayList<Boolean>() // per piece: a built-in front's back-only W
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
                        if (keyPattern.matches(name)) refer(name, false) else warnings.add("unknown_placeholder")
                        val nonEmpty = value(name).isNotBlank()
                        stack.add(Frame(name, if (sigil == "#") nonEmpty else !nonEmpty))
                    }
                    "/" ->
                        if (stack.isNotEmpty() && stack.last().key == name) stack.removeAt(stack.size - 1)
                        else warnings.add("unbalanced_section")
                    else -> if (isWordAudio(name, builtIn)) {
                        if (wordAudioApplies(name)) {
                            refer(WordAudioRules.NOTE_FIELD, false)
                            val w = wordAudio
                            if (stack.all { it.active } && w.html.isNotEmpty()) {
                                sb.append(Sanitizer.SENTINEL_OPEN).append(pieces.size).append(Sanitizer.SENTINEL_CLOSE)
                                pieces.add(w.html)
                                pieceKeys.add(if (w.showsField) WordAudioRules.NOTE_FIELD else null)
                                pieceAudio.add(w.hash)
                                pieceBackOnly.add(name == WORD_AUDIO_BACK)
                            }
                        }
                    } else if (stack.any { !it.active }) {
                        fieldKeyOf(name)?.let { refer(it, false) }
                    } else {
                        val key = fieldKeyOf(name) ?: FRONT_SIDE.takeIf { name == it && front != null }
                        if (key == FRONT_SIDE) frontReferred.keys.forEach { refer(it, false) } else if (key != null) refer(key, false)
                        val piece = piece(name, builtIn, front?.html)
                        if (piece.isNotEmpty()) {
                            sb.append(Sanitizer.SENTINEL_OPEN).append(pieces.size).append(Sanitizer.SENTINEL_CLOSE)
                            pieces.add(piece)
                            pieceKeys.add(key)
                            pieceAudio.add(if (key == FRONT_SIDE) front?.wordAudio else null)
                            pieceBackOnly.add(false)
                        }
                    }
                }
            }
            if (stack.all { it.active }) sb.append(source, pos, source.length)
            if (stack.isNotEmpty()) warnings.add("unbalanced_section")
            val clean = sanitizer.sanitize(sb.toString(), TextMode.HTML)
            // a piece whose sentinel did not survive (it sat in a template attribute) is not in the output:
            // only a piece put back here has a region (and only a W put back here is emitted)
            var firstAudio: String? = null
            var firstFrontAudio: String? = null
            val filled = sentinelRef.replace(clean) { r ->
                val i = r.groupValues[1].toIntOrNull()
                val piece = i?.let { pieces.getOrNull(it) }
                if (i != null && piece != null) {
                    val key = pieceKeys[i]
                    if (key == FRONT_SIDE) frontReferred.forEach { (k, rendered) -> refer(k, rendered) } else if (key != null) refer(key, true)
                    val h = pieceAudio[i]
                    if (h != null) {
                        if (firstAudio == null) firstAudio = h
                        if (firstFrontAudio == null && !pieceBackOnly[i]) firstFrontAudio = h
                    }
                }
                piece ?: ""
            }
            return Expanded(Sanitizer.stripSentinels(filled), firstAudio, firstFrontAudio)
        }

        /** True for the placeholders of block W: `{{audio:word}}` anywhere, the back-only one in built-in templates. */
        private fun isWordAudio(name: String, builtIn: Boolean): Boolean =
            name == WORD_AUDIO || (builtIn && name == WORD_AUDIO_BACK)

        /** True when the W placeholder [name] stands for W in this render (vocab; the back-only one only on a BACK). */
        private fun wordAudioApplies(name: String): Boolean =
            input.noteType.baseType == NoteBaseType.VOCAB && (name != WORD_AUDIO_BACK || input.side == CardSide.BACK)

        /** Block W: its HTML (may be empty), the first hash in it, and whether it shows the `audio` field. */
        private class WordAudioBlock(val html: String, val hash: String?, val showsField: Boolean)

        private val wordAudio: WordAudioBlock by lazy {
            if (DefaultWordAudioResolver.noteSound(input.fields) != null) {
                val html = fieldPiece(WordAudioRules.NOTE_FIELD, TextMode.HTML)
                WordAudioBlock(html, audioRef.find(html)?.groupValues?.get(1), showsField = html.isNotEmpty())
            } else {
                val h = input.wordAudio?.takeIf { sha.matches(it) }
                if (h == null) WordAudioBlock("", null, false) else WordAudioBlock("<button class=\"kb-audio\" data-kb-audio=\"$h\"></button>", h, false)
            }
        }

        /** Safe HTML for one value placeholder (already sanitised), or `""`. */
        private fun piece(name: String, builtIn: Boolean, front: String?): String {
            if (name == FRONT_SIDE) return front ?: ""
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
            if (builtIn && expandingFront && mode == TextMode.KANJI && key == "expression") furiganaFront(resolve(key).second)?.let { return it }
            return fieldPiece(key, mode)
        }

        /** A field's sanitised value in its wrapper span (with the region when on), or `""`. */
        private fun fieldPiece(key: String, mode: TextMode): String {
            val (raw, source) = resolve(key)
            val html = if (raw.isBlank()) "" else sanitizer.sanitize(Sanitizer.stripSentinels(raw), mode)
            if (html.isEmpty()) return ""
            // key is [a-z][a-z0-9_]* and source.wire a constant: nothing here needs escaping
            val region = if (options.fieldRegions) " data-field=\"$key\" data-field-source=\"${source.wire}\"" else ""
            return "<span class=\"kb-field-$key\"$region>$html</span>"
        }

        /**
         * ADR-042 "Furigana front": the built-in front's `{{kanji:expression}}` of a vocab `meaning` / `pitch`
         * card as one ruby over the whole word, or null when it does not apply (then the plain value is used).
         */
        private fun furiganaFront(source: FieldSource): String? {
            if (!input.frontFurigana || input.noteType.baseType != NoteBaseType.VOCAB || input.kind !in FURIGANA_FRONT_KINDS) return null
            val e = DefaultCardTextRenderer.fieldText(input.fields, input.dictionary, input.kanji, NoteBaseType.VOCAB, "expression", TextMode.KANJI)
            val r = DefaultCardTextRenderer.fieldText(input.fields, input.dictionary, input.kanji, NoteBaseType.VOCAB, "reading", TextMode.KANA)
            if (e.isEmpty() || r.isEmpty() || r == e) return null
            val region = if (options.fieldRegions) " data-field=\"expression\" data-field-source=\"${source.wire}\"" else ""
            return "<span class=\"kb-field-expression\"$region><ruby>" + escapeHtml(e) + "<rt>" + escapeHtml(r) + "</rt></ruby></span>"
        }

        private fun unknown(): String {
            warnings.add("unknown_placeholder")
            return ""
        }

        /** The field value, filled from dictionary / kanji data when empty (as an HTML fragment). */
        fun value(key: String): String = resolve(key).first

        /** [value] with where it came from. */
        private fun resolve(key: String): Pair<String, FieldSource> =
            resolveField(input.fields, input.dictionary, input.kanji, input.noteType.baseType, key)

        private fun pitch(): String {
            val d = input.dictionary ?: return ""
            if (!options.pitch || d.pitchDrops.isEmpty()) return ""
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

    /**
     * A field's value as an HTML fragment and where it came from (shared with [DefaultCardTextRenderer]): a non-blank
     * note value wins; else the dictionary fill (`expression`, `reading`, `meaning`); else, for kanji notes, the kanji fill.
     */
    internal fun resolveField(
        fields: Map<String, String>,
        dictionary: DictionaryData?,
        kanji: KanjiData?,
        baseType: NoteBaseType,
        key: String,
    ): Pair<String, FieldSource> {
        val raw = fields[key] ?: ""
        if (raw.isNotBlank()) return raw to FieldSource.NOTE
        if (dictionary != null) {
            val filled = when (key) {
                "expression" -> dictionary.expression
                "reading" -> Furigana.bracketOf(dictionary)
                "meaning" -> dictionary.glosses.joinToString("; ")
                else -> ""
            }
            if (filled.isNotBlank()) return escapeHtml(filled) to FieldSource.DICTIONARY
        }
        if (kanji != null && baseType == NoteBaseType.KANJI) {
            val filled = when (key) {
                "literal" -> kanji.literal
                "keyword" -> kanji.meanings.joinToString("; ")
                "readings" -> (kanji.onyomi + kanji.kunyomi).joinToString("、")
                else -> ""
            }
            if (filled.isNotBlank()) return escapeHtml(filled) to FieldSource.KANJI
        }
        return raw to FieldSource.EMPTY
    }

    private val SPAN_PARTS = setOf("blank", "answer", "spot")

    /** Built-in vocab kinds whose front asks with the expression (ADR-042 furigana front). */
    internal val FURIGANA_FRONT_KINDS: Set<String> = setOf("meaning", "pitch")
    private val VALUE_PREFIXES = setOf("furigana", "kana", "kanji", "text")

    /** `{{audio:word}}` (ADR-047): block W, public. */
    private const val WORD_AUDIO = "audio:word"

    /** Built-in only: W on the BACK (inside the front its `{{FrontSide}}` repeats), nothing on the FRONT. */
    internal const val WORD_AUDIO_BACK: String = "wordaudio:back"
    private val sha = Regex("[0-9a-f]{64}")
    private const val BLANK = "＿＿＿"
}
