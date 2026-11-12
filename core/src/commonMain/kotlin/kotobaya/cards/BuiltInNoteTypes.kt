package kotobaya.cards

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.cards.TemplateInfo

/** One field definition of a note type (`note_type.fields[]`). */
public data class FieldDef(
    /** Field key, `^[a-z][a-z0-9_]*$`. */
    public val key: String,
    /** Human label. */
    public val label: String,
    /** `text`, `audio` or `image`. */
    public val kind: String,
)

/** A seeded built-in note type; mirrors one `app.note_type` row of `contracts/db/V1__init.sql`. */
public data class BuiltInNoteType(
    /** `note_type.id`. */
    public val id: String,
    /** `note_type.name`. */
    public val name: String,
    /** Ordered field definitions. */
    public val fields: List<FieldDef>,
    /** The parts the core reads (base type, field keys, templates, css). */
    public val info: NoteTypeInfo,
)

/** The six built-in note types, equal to the V1 seeds (checked by a drift test). */
public object BuiltInNoteTypes {
    private fun text(key: String, label: String) = FieldDef(key, label, "text")
    private fun audio(key: String, label: String) = FieldDef(key, label, "audio")
    private fun image(key: String, label: String) = FieldDef(key, label, "image")

    private fun templates(default: List<String>, optional: List<String>): List<TemplateInfo> =
        default.map { TemplateInfo(it, true, null, null) } + optional.map { TemplateInfo(it, false, null, null) }

    private fun make(
        id: String,
        name: String,
        base: NoteBaseType,
        fields: List<FieldDef>,
        templates: List<TemplateInfo>,
    ) = BuiltInNoteType(id, name, fields, NoteTypeInfo(base, fields.map { it.key }, templates, ""))

    /** Vocab (`...0101`). */
    public val VOCAB: BuiltInNoteType = make(
        "00000000-0000-7000-8000-000000000101", "Vocab", NoteBaseType.VOCAB,
        listOf(
            text("expression", "Expression"), text("reading", "Reading"), text("meaning", "Meaning"),
            text("sentence", "Sentence"), text("sentence_translation", "Sentence translation"),
            audio("audio", "Audio"), audio("sentence_audio", "Sentence audio"),
            image("image", "Image"), text("notes", "Notes"),
        ),
        templates(listOf("meaning", "reading"), listOf("listening", "production", "pitch")),
    )

    /** Kanji (`...0102`). */
    public val KANJI: BuiltInNoteType = make(
        "00000000-0000-7000-8000-000000000102", "Kanji", NoteBaseType.KANJI,
        listOf(text("literal", "Kanji"), text("keyword", "Keyword"), text("readings", "Readings"), text("notes", "Notes")),
        templates(listOf("keyword"), listOf("writing", "components")),
    )

    /** Phrase (`...0103`). */
    public val PHRASE: BuiltInNoteType = make(
        "00000000-0000-7000-8000-000000000103", "Phrase", NoteBaseType.PHRASE,
        listOf(
            text("expression", "Expression"), text("reading", "Reading"), text("meaning", "Meaning"),
            audio("audio", "Audio"), text("notes", "Notes"),
        ),
        templates(listOf("meaning"), listOf("production", "listening")),
    )

    /** Grammar (`...0104`). */
    public val GRAMMAR: BuiltInNoteType = make(
        "00000000-0000-7000-8000-000000000104", "Grammar", NoteBaseType.GRAMMAR,
        listOf(text("pattern", "Pattern"), text("meaning", "Meaning"), text("notes", "Notes")),
        templates(listOf("cloze"), listOf("recognition", "spot", "production")),
    )

    /** Sentence (`...0105`). */
    public val SENTENCE: BuiltInNoteType = make(
        "00000000-0000-7000-8000-000000000105", "Sentence", NoteBaseType.SENTENCE,
        listOf(
            text("sentence", "Sentence"), text("reading", "Reading"), text("translation", "Translation"),
            audio("audio", "Audio"), image("image", "Image"), text("notes", "Notes"),
        ),
        templates(listOf("comprehension"), listOf("listening")),
    )

    /** Custom (`...0106`). */
    public val CUSTOM: BuiltInNoteType = make(
        "00000000-0000-7000-8000-000000000106", "Custom", NoteBaseType.CUSTOM,
        listOf(text("front", "Front"), text("back", "Back")),
        templates(listOf("basic"), emptyList()),
    )

    /** All six, in seed order. */
    public val ALL: List<BuiltInNoteType> = listOf(VOCAB, KANJI, PHRASE, GRAMMAR, SENTENCE, CUSTOM)
}
