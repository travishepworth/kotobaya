package kotobaya.android.ankiapi.takoboto

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue

/** One AnkiDroid-visible field and the Kotobaya `note.fields` key it maps to. */
data class FieldMapping(val ankiName: String, val key: String)

/**
 * The "Takoboto Vocab" note type (SPEC "Takoboto integration"): what AnkiDroid callers see, and the
 * `note_type` row it is backed by.
 *
 * **The field table is the only place Takoboto's export format lives.** It is seeded (no real call
 * log yet, HUMAN_CHECKS "Takoboto"): names and order follow OPS.md C-2's example
 * (Takoboto "Expression"/"Kana"/"Meaning") plus the sentence pair AnkiDroid exporters commonly send.
 * When the real log differs, change [FIELDS] (and the fixture) only.
 *
 * The note type id is not a UUIDv7 but a fixed, name+kind-derived id ([NOTE_TYPE_ID]: the first 128
 * bits of SHA-256 of `kotobaya:note_type:vocab:Takoboto Vocab`, version nibble 8). Every device mints
 * the same id, so two devices creating it offline produce one row: the second `note_type.created` is a
 * no-op by the creation rule (SYNC.md 7.2).
 */
object TakobotoVocab {
    const val NAME: String = "Takoboto Vocab"
    const val NOTE_TYPE_ID: String = "5f934315-8fa0-8d57-8e49-84a01037668a"

    /** AnkiDroid-visible fields, in Takoboto's order. The first is Anki's checksum field, the sort field too. */
    val FIELDS: List<FieldMapping> = listOf(
        FieldMapping("Expression", "expression"),
        FieldMapping("Kana", "reading"),
        FieldMapping("Meaning", "meaning"),
        FieldMapping("Sentence", "sentence"),
        FieldMapping("SentenceMeaning", "sentence_translation"),
    )

    /** Key where values of fields a client model has but we cannot map are kept (never dropped). */
    const val OVERFLOW_KEY: String = "notes"

    /** Field keys of the `note_type` row: the mapped keys plus [OVERFLOW_KEY]; C-2 holds. */
    val NOTE_TYPE_KEYS: List<String> = FIELDS.map { it.key } + OVERFLOW_KEY

    /** Card kinds, as the built-in Vocab type: built-in templates (front/back null). */
    val CARD_KINDS: List<String> = listOf("meaning", "reading")

    /**
     * Field names a client-created model (AddContentApi `addNewCustomModel`) may use, folded to lower
     * case without spaces/underscores/hyphens, and the key each maps to.
     */
    val ALIASES: Map<String, String> = mapOf(
        "expression" to "expression", "japanese" to "expression", "word" to "expression", "kanji" to "expression",
        "vocab" to "expression", "vocabulary" to "expression", "term" to "expression", "front" to "expression",
        "kana" to "reading", "reading" to "reading", "furigana" to "reading", "hiragana" to "reading",
        "meaning" to "meaning", "english" to "meaning", "definition" to "meaning", "glossary" to "meaning",
        "gloss" to "meaning", "back" to "meaning",
        "sentence" to "sentence", "example" to "sentence", "examplesentence" to "sentence",
        "sentencemeaning" to "sentence_translation", "sentencetranslation" to "sentence_translation",
        "sentenceenglish" to "sentence_translation", "translation" to "sentence_translation",
        "notes" to OVERFLOW_KEY,
    )

    /**
     * Maps a client model's field names to keys: by [ALIASES], each key at most once; a field that
     * maps nowhere gets null (its value goes to [OVERFLOW_KEY]). When nothing maps to `expression` or
     * `reading`, the first field is the expression.
     */
    fun mapClientFields(names: List<String>): List<String?> {
        val used = HashSet<String>()
        val keys = names.map { n ->
            ALIASES[fold(n)]?.takeIf { it == OVERFLOW_KEY || used.add(it) }
        }.toMutableList()
        if (keys.none { it == "expression" || it == "reading" } && keys.isNotEmpty()) keys[0] = "expression"
        return keys
    }

    private fun fold(name: String): String = name.lowercase().filterNot { it == ' ' || it == '_' || it == '-' }

    /** `note_type.created` payload for this type with [id]. */
    fun createPayload(id: String): JsonObject {
        val labels = FIELDS.associate { it.key to it.ankiName } + (OVERFLOW_KEY to "Notes")
        val fields = NOTE_TYPE_KEYS.map { key ->
            JsonObject(linkedMapOf("key" to JsonString(key), "label" to JsonString(labels.getValue(key)), "kind" to JsonString("text")))
        }
        val templates = CARD_KINDS.associateWith<String, JsonValue> {
            JsonObject(linkedMapOf("default" to JsonBool(true), "front" to JsonNull, "back" to JsonNull))
        }
        return JsonObject(
            linkedMapOf(
                "id" to JsonString(id),
                "name" to JsonString(NAME),
                "base_type" to JsonString("vocab"),
                "fields" to JsonArray(fields),
                "templates" to JsonObject(LinkedHashMap(templates)),
            ),
        )
    }
}
