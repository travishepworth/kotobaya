package kotobaya.sync.client.apply

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson

/**
 * One row of a learning table in **device representation** (`contracts/db/SCHEMA.md` 5.1, the form
 * of the scenario files' `expected.rows`): column name → value, with uuids and hlcs as strings,
 * instants as epoch-ms numbers, booleans as JSON booleans, and JSON / array columns (`clocks`,
 * `fields`, `tags`, `settings`, `templates`, …) as decoded JSON values (a SQLite store encodes them
 * as text with `CanonicalJson`). Every column of `Learning.sq` / `Media.sq` is present (NULL =
 * [JsonNull]).
 */
public typealias Row = Map<String, JsonValue>

/** The tables of `Learning.sq` and `Media.sq` that the apply code reads or writes, with their primary keys. */
public enum class LearningTable(
    /** SQLite table name. */
    public val sqlName: String,
    /** Primary-key columns, in key order. */
    public val keyColumns: List<String>,
) {
    /** `note_type`. */
    NOTE_TYPE("note_type", listOf("id")),

    /** `note`. */
    NOTE("note", listOf("id")),

    /** `card`. */
    CARD("card", listOf("id")),

    /** `review_log` (append-only, SYNC.md 7.9; written only through [AppendOnlyReviewLog]). */
    REVIEW_LOG("review_log", listOf("id")),

    /** `word_status`. */
    WORD_STATUS("word_status", listOf("entry_seq")),

    /** `deck`. */
    DECK("deck", listOf("id")),

    /** `deck_item`. */
    DECK_ITEM("deck_item", listOf("deck_id", "item_key")),

    /** `confusable`. */
    CONFUSABLE("confusable", listOf("note_a_id", "note_b_id", "reason")),

    /** `grammar_point`. */
    GRAMMAR_POINT("grammar_point", listOf("id")),

    /** `sentence_grammar_span`. */
    SENTENCE_GRAMMAR_SPAN("sentence_grammar_span", listOf("sentence_id", "grammar_point_id", "start_offset", "end_offset", "origin")),

    /** `settings`. */
    SETTINGS("settings", listOf("key")),

    /** `fsrs_params`. */
    FSRS_PARAMS("fsrs_params", listOf("id")),

    /** `media`. */
    MEDIA("media", listOf("id")),

    /** `media_segment` (snapshot). */
    MEDIA_SEGMENT("media_segment", listOf("id")),

    /** `sentence` (snapshot). */
    SENTENCE("sentence", listOf("id")),

    /** `occurrence` (snapshot). */
    OCCURRENCE("occurrence", listOf("segment_id", "entry_seq")),
    ;

    /** The key of [row] in [keyColumns] order. */
    public fun keyOf(row: Row): List<JsonValue> = keyColumns.map { row[it] ?: JsonNull }
}

/**
 * The device's learning tables as the apply code (T303b) sees them: typed by [LearningTable], rows in
 * the representation of [Row]. Implemented in memory by [InMemoryLearningTables] and over SQLDelight
 * by T303c. All calls happen inside the caller's `DeviceStore` transaction.
 *
 * Contract: [put] inserts or replaces the whole row with the same key; rows returned are snapshots
 * (mutating the caller's copy never changes the table); the [review_log][LearningTable.REVIEW_LOG]
 * table is written by the apply code only through [AppendOnlyReviewLog] (SYNC.md 7.9).
 */
public interface LearningTables {
    /** The row of [table] whose key equals [key] (in [LearningTable.keyColumns] order), or null. */
    public fun get(table: LearningTable, key: List<JsonValue>): Row?

    /** Inserts [row], or replaces the row with the same key. */
    public fun put(table: LearningTable, row: Row)

    /** Deletes the row with [key] (no-op when absent). */
    public fun delete(table: LearningTable, key: List<JsonValue>)

    /** Rows of [table] whose [column] equals [value] (JSON equality; [JsonNull] matches NULL). */
    public fun where(table: LearningTable, column: String, value: JsonValue): List<Row>

    /** Every row of [table]. */
    public fun all(table: LearningTable): List<Row>

    /** Deletes every row of [table] (the rebuild wipe, SYNC.md 10.3 step 1, only). */
    public fun clear(table: LearningTable)
}

/** [LearningTables] in memory (tests, the T313 simulation). Not thread-safe. */
public class InMemoryLearningTables private constructor(
    private val tables: MutableMap<LearningTable, LinkedHashMap<String, Row>>,
) : LearningTables {
    /** Empty tables (no built-ins; see [seeded]). */
    public constructor() : this(LearningTable.entries.associateWithTo(LinkedHashMap()) { LinkedHashMap() })

    override fun get(table: LearningTable, key: List<JsonValue>): Row? = rows(table)[keyText(key)]

    override fun put(table: LearningTable, row: Row) {
        rows(table)[keyText(table.keyOf(row))] = LinkedHashMap(row)
    }

    override fun delete(table: LearningTable, key: List<JsonValue>) {
        rows(table).remove(keyText(key))
    }

    override fun where(table: LearningTable, column: String, value: JsonValue): List<Row> =
        rows(table).values.filter { (it[column] ?: JsonNull) == value }

    override fun all(table: LearningTable): List<Row> = rows(table).values.toList()

    override fun clear(table: LearningTable) {
        rows(table).clear()
    }

    /** An independent copy (rows are immutable snapshots, so the maps are copied shallowly). */
    public fun copy(): InMemoryLearningTables =
        InMemoryLearningTables(tables.mapValuesTo(LinkedHashMap()) { LinkedHashMap(it.value) })

    private fun rows(table: LearningTable): LinkedHashMap<String, Row> = tables.getOrPut(table) { LinkedHashMap() }

    private fun keyText(key: List<JsonValue>): String = DefaultCanonicalJson.encode(JsonArray(key))

    /** Factories. */
    public companion object {
        /** Tables holding only the seeded built-ins of `Learning.sq` ([BuiltIns.seed]). */
        public fun seeded(): InMemoryLearningTables = InMemoryLearningTables().also { BuiltIns.seed(it) }
    }
}

/** The rows `Learning.sq` seeds (six built-in note types and the Default preset), as an empty replica holds them. */
public object BuiltIns {
    private fun field(key: String, label: String, kind: String = "text"): JsonValue =
        JsonObject(linkedMapOf("key" to JsonString(key), "label" to JsonString(label), "kind" to JsonString(kind)))

    private fun templates(vararg kinds: Pair<String, Boolean>): JsonValue = JsonObject(
        kinds.associateTo(LinkedHashMap()) { (k, d) ->
            k to JsonObject(linkedMapOf("default" to JsonBool(d), "front" to JsonNull, "back" to JsonNull))
        },
    )

    private class NoteTypeSeed(val id: String, val name: String, val base: String, val fields: List<JsonValue>, val templates: JsonValue)

    private val noteTypes = listOf(
        NoteTypeSeed(
            Reserved.NOTE_TYPE_VOCAB, "Vocab", "vocab",
            listOf(
                field("expression", "Expression"), field("reading", "Reading"), field("meaning", "Meaning"),
                field("sentence", "Sentence"), field("sentence_translation", "Sentence translation"),
                field("audio", "Audio", "audio"), field("sentence_audio", "Sentence audio", "audio"),
                field("image", "Image", "image"), field("notes", "Notes"),
            ),
            templates("meaning" to true, "reading" to true, "listening" to false, "production" to false, "pitch" to false),
        ),
        NoteTypeSeed(
            Reserved.NOTE_TYPE_KANJI, "Kanji", "kanji",
            listOf(field("literal", "Kanji"), field("keyword", "Keyword"), field("readings", "Readings"), field("notes", "Notes")),
            templates("keyword" to true, "writing" to false, "components" to false),
        ),
        NoteTypeSeed(
            Reserved.NOTE_TYPE_PHRASE, "Phrase", "phrase",
            listOf(
                field("expression", "Expression"), field("reading", "Reading"), field("meaning", "Meaning"),
                field("audio", "Audio", "audio"), field("notes", "Notes"),
            ),
            templates("meaning" to true, "production" to false, "listening" to false),
        ),
        NoteTypeSeed(
            Reserved.NOTE_TYPE_GRAMMAR, "Grammar", "grammar",
            listOf(field("pattern", "Pattern"), field("meaning", "Meaning"), field("notes", "Notes")),
            templates("cloze" to true, "recognition" to false, "spot" to false, "production" to false),
        ),
        NoteTypeSeed(
            Reserved.NOTE_TYPE_SENTENCE, "Sentence", "sentence",
            listOf(
                field("sentence", "Sentence"), field("reading", "Reading"), field("translation", "Translation"),
                field("audio", "Audio", "audio"), field("image", "Image", "image"), field("notes", "Notes"),
            ),
            templates("comprehension" to true, "listening" to false),
        ),
        NoteTypeSeed(
            Reserved.NOTE_TYPE_CUSTOM, "Custom", "custom",
            listOf(field("front", "Front"), field("back", "Back")),
            templates("basic" to true),
        ),
    )

    /** `fsrs_params` column defaults (`Learning.sq`), compared by JSON equality for the creation rule (SYNC.md 7.2). */
    public val PRESET_DEFAULTS: Map<String, JsonValue> = linkedMapOf(
        "desired_retention" to JsonNumber(0.9),
        "maximum_interval_days" to JsonNumber(36500.0),
        "learning_steps_s" to JsonArray(listOf(JsonNumber(60.0), JsonNumber(600.0))),
        "relearning_steps_s" to JsonArray(listOf(JsonNumber(600.0))),
        "fuzz" to JsonBool(true),
        "easy_days" to JsonArray(List(7) { JsonNumber(1.0) }),
    )

    /** Inserts the built-in rows into [tables] (replacing rows with the same ids). */
    public fun seed(tables: LearningTables) {
        for (nt in noteTypes) {
            tables.put(
                LearningTable.NOTE_TYPE,
                linkedMapOf(
                    "id" to JsonString(nt.id), "name" to JsonString(nt.name), "base_type" to JsonString(nt.base),
                    "builtin" to JsonBool(true), "fields" to JsonArray(nt.fields), "templates" to nt.templates,
                    "css" to JsonString(""), "created_at" to JsonNumber(0.0), "updated_at" to JsonNumber(0.0),
                    "deleted_at" to JsonNull, "clocks" to JsonObject(emptyMap()),
                ),
            )
        }
        tables.put(LearningTable.FSRS_PARAMS, presetRow(Reserved.DEFAULT_PRESET_ID, "Default", 0L))
    }

    /** A preset row with every column at its default. */
    internal fun presetRow(id: String, name: String, at: Long): LinkedHashMap<String, JsonValue> {
        val row = linkedMapOf<String, JsonValue>("id" to JsonString(id), "name" to JsonString(name))
        row.putAll(PRESET_DEFAULTS)
        row["fsrs_version"] = JsonNull
        row["weights"] = JsonNull
        row["weights_version"] = JsonNumber(0.0)
        row["optimized_at"] = JsonNull
        row["optimizer_stats"] = JsonNull
        row["created_at"] = JsonNumber(at.toDouble())
        row["updated_at"] = JsonNumber(at.toDouble())
        row["deleted_at"] = JsonNull
        row["clocks"] = JsonObject(emptyMap())
        return row
    }
}
