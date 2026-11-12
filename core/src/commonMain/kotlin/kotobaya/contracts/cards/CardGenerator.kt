package kotobaya.contracts.cards

import kotobaya.contracts.NoteBaseType

/**
 * One card kind of a note type (`note_type.templates.<kind>`).
 *
 * Built-in kinds per base type (seeded in `contracts/db/V1__init.sql`; default ones marked *):
 * vocab: meaning*, reading*, listening, production, pitch · kanji: keyword*, writing, components ·
 * phrase: meaning*, production, listening · grammar: cloze*, recognition, spot, production ·
 * sentence: comprehension*, listening · custom: basic*.
 */
public data class TemplateInfo(
    /** The kind key, `^[a-z][a-z0-9_]*$`. */
    public val kind: String,
    /** `default`: create this card automatically with a new note. */
    public val isDefault: Boolean,
    /** `front`: custom template HTML, or null = the core's built-in template for (base type, kind). */
    public val front: String?,
    /** `back`: custom template HTML, or null = built-in. */
    public val back: String?,
)

/** The parts of a note type the core reads. */
public data class NoteTypeInfo(
    /** `note_type.base_type`. */
    public val baseType: NoteBaseType,
    /**
     * Field keys in `note_type.fields` order. For [NoteBaseType] vocab they always include
     * `expression`, `reading` and `meaning` (OPS.md `note_type.created`, C-2): the core relies on these
     * keys for built-in templates and dictionary fill-in; custom vocab types (e.g. Takoboto's) map
     * their fields onto them.
     */
    public val fieldKeys: List<String>,
    /** `note_type.templates` in the object's key order (the order the JSON stores; insertion order). */
    public val templates: List<TemplateInfo>,
    /** `note_type.css` (appended after `assets/card/` CSS); `""` if none. */
    public val css: String,
)

/**
 * Decides which cards a note gets (SYNC.md 4 "A new note's cards are separate `card.created` ops …
 * one per template with `default = true`, unless the deck setting `card_kinds` says otherwise") and
 * which kanji notes to add for a word (deck setting `add_kanji`). Pure; the caller mints the ops
 * (`note.created`, then one `card.created` per returned kind, in the returned order).
 */
public interface CardGenerator {
    /**
     * Card kinds to create for a **new** note of [noteType].
     * - [deckCardKinds] non-null (the `card_kinds` setting of the deck the note is added to): its
     *   entries in its order, keeping only kinds that exist in `noteType.templates`, duplicates
     *   removed (first occurrence kept). May be empty: a note with no cards is legal.
     * - null: every template with `isDefault`, in template order.
     *
     * Example: built-in vocab, `deckCardKinds = null` → `["meaning", "reading"]`;
     * `deckCardKinds = ["listening", "foo", "meaning", "listening"]` → `["listening", "meaning"]`.
     */
    public fun kindsForNewNote(noteType: NoteTypeInfo, deckCardKinds: List<String>?): List<String>

    /**
     * Kinds from [requested] that may be created for an **existing** note: those that exist in the
     * note type and are not in [liveKinds] (the kinds of the note's live cards). A client MUST NOT
     * knowingly create a duplicate `(note, kind)` (SYNC.md 4). Order and de-duplication as above.
     */
    public fun missingKinds(noteType: NoteTypeInfo, liveKinds: Set<String>, requested: List<String>): List<String>

    /**
     * The kanji of [expression] in order of first appearance, distinct: code points in
     * U+3400–U+4DBF, U+4E00–U+9FFF, U+F900–U+FAFF, U+20000–U+2FA1F. `々` (U+3005), kana and
     * everything else are not kanji. Example: `"食べ物々"` → `["食", "物"]`.
     */
    public fun kanjiOf(expression: String): List<String>

    /**
     * Kanji notes to create for a new vocab note when the deck's `add_kanji` is not `off`:
     * `kanjiOf(expression)` minus [existingKanji] (literals that already have a live kanji note),
     * in the same order. The caller creates each as a kanji note (`origin = auto_kanji`,
     * `kanji_literal` = the literal) with [kindsForNewNote] of the kanji note type, **before** the
     * vocab note's ops. Empty when [mode] is `off`.
     */
    public fun kanjiToAdd(
        expression: String,
        existingKanji: Set<String>,
        mode: kotobaya.contracts.queue.AddKanjiMode,
    ): List<String>
}
