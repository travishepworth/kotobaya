package kotobaya.render

import kotobaya.contracts.NoteBaseType

/** A built-in front/back template pair. */
internal class Template(val front: String, val back: String)

/**
 * The core's built-in templates (`TemplateInfo.front`/`back` null), per the `CardRenderer` contract's
 * list. They use the public template language plus two built-in-only prefixes: `{{pitch:reading}}`
 * (pitch spans from `DictionaryData.pitchDrops`) and `{{cloze:blank|answer|sentence|spot|translation|hint}}`
 * (the grammar cloze sentence). The sentence/image extras are appended by the renderer, not here.
 */
internal object BuiltInTemplates {
    private const val HR = "<hr class=\"kb-divider\">"
    private const val EXPRESSION = "<div class=\"kb-expression\">{{kanji:expression}}</div>"
    private const val READING = "{{#reading}}<div class=\"kb-reading\">{{furigana:reading}}</div>{{/reading}}{{pitch:reading}}"
    private const val MEANING = "<div class=\"kb-meaning\">{{meaning}}</div>"
    private const val AUDIO = "{{audio}}"
    private const val NOTES = "{{#notes}}<div class=\"kb-notes\">{{notes}}</div>{{/notes}}"
    private const val VOCAB_TAIL =
        "{{#sentence_translation}}<div class=\"kb-translation\">{{sentence_translation}}</div>{{/sentence_translation}}" +
            "{{sentence_audio}}$NOTES"

    private fun back(vararg parts: String) = "{{FrontSide}}$HR" + parts.joinToString("")

    private val vocab = mapOf(
        "meaning" to Template(EXPRESSION, back(READING, MEANING, AUDIO, VOCAB_TAIL)),
        "reading" to Template(EXPRESSION, back(READING, MEANING, AUDIO, VOCAB_TAIL)),
        "listening" to Template(AUDIO, back(EXPRESSION, READING, MEANING, VOCAB_TAIL)),
        "production" to Template(MEANING, back(EXPRESSION, READING, AUDIO, VOCAB_TAIL)),
        "pitch" to Template(
            EXPRESSION + "{{#reading}}<div class=\"kb-reading\">{{kana:reading}}</div>{{/reading}}",
            back("{{pitch:reading}}", AUDIO, VOCAB_TAIL),
        ),
    )

    private val kanji = mapOf(
        "keyword" to Template(
            "<div class=\"kb-literal\">{{literal}}</div>",
            back("<div class=\"kb-keyword\">{{keyword}}</div>", "<div class=\"kb-reading\">{{readings}}</div>", NOTES),
        ),
        "writing" to Template(
            "<div class=\"kb-keyword\">{{keyword}}</div>",
            back("<div class=\"kb-literal\">{{literal}}</div>", "<div class=\"kb-reading\">{{readings}}</div>", NOTES),
        ),
        "components" to Template(
            "<div class=\"kb-literal\">{{literal}}</div>",
            back("<div class=\"kb-notes\">{{notes}}</div>"),
        ),
    )

    private val phrase = mapOf(
        "meaning" to Template(EXPRESSION, back(READING, MEANING, AUDIO, NOTES)),
        "production" to Template(MEANING, back(EXPRESSION, READING, AUDIO, NOTES)),
        "listening" to Template(AUDIO, back(EXPRESSION, READING, MEANING, NOTES)),
    )

    private const val PATTERN = "<div class=\"kb-pattern\">{{pattern}}</div>"

    private val grammar = mapOf(
        "cloze" to Template("{{cloze:blank}}{{cloze:hint}}", "{{cloze:answer}}$HR$PATTERN$MEANING{{cloze:translation}}$NOTES"),
        "recognition" to Template(PATTERN, back(MEANING, NOTES)),
        "spot" to Template("{{cloze:sentence}}", "{{cloze:spot}}$HR$PATTERN$MEANING{{cloze:translation}}$NOTES"),
        "production" to Template(MEANING, back(PATTERN, NOTES)),
    )

    private const val SENTENCE_READING = "{{#reading}}<div class=\"kb-reading\">{{furigana:reading}}</div>{{/reading}}"
    private const val TRANSLATION = "<div class=\"kb-translation\">{{translation}}</div>"

    private val sentence = mapOf(
        "comprehension" to Template(
            "<div class=\"kb-sentence\">{{kanji:sentence}}</div>",
            back(SENTENCE_READING, TRANSLATION, AUDIO, NOTES),
        ),
        "listening" to Template(
            AUDIO,
            back("<div class=\"kb-sentence\">{{kanji:sentence}}</div>", SENTENCE_READING, TRANSLATION, NOTES),
        ),
    )

    private val custom = mapOf("basic" to Template("{{front}}", back("{{back}}")))

    fun get(base: NoteBaseType, kind: String): Template? = when (base) {
        NoteBaseType.VOCAB -> vocab
        NoteBaseType.KANJI -> kanji
        NoteBaseType.PHRASE -> phrase
        NoteBaseType.GRAMMAR -> grammar
        NoteBaseType.SENTENCE -> sentence
        NoteBaseType.CUSTOM -> custom
    }[kind]

    /** Shown under every built-in front (and so, via `{{FrontSide}}`, on the back): sentence (not for sentence notes, whose sentence is the card) and image. */
    fun extras(base: NoteBaseType): String =
        (if (base == NoteBaseType.SENTENCE) "" else "{{#sentence}}<div class=\"kb-sentence\">{{kanji:sentence}}</div>{{/sentence}}") +
            "{{#image}}<div class=\"kb-image\">{{image}}</div>{{/image}}"
}
