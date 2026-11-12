package kotobaya.notes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.cards.TemplateInfo
import kotobaya.server.json
import org.jooq.DSLContext

/** `timestamptz` column as RFC 3339 UTC with milliseconds (the API `Timestamp`). */
private fun ts(col: String) = "to_char($col at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')"

private const val NOTE_TYPE_JSON = "select jsonb_build_object(" +
    "'id', t.id, 'name', t.name, 'base_type', t.base_type, 'builtin', t.builtin, 'fields', t.fields, " +
    "'templates', t.templates, 'css', t.css, " +
    "'created_at', to_char(t.created_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'), " +
    "'updated_at', to_char(t.updated_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'), " +
    "'deleted_at', to_char(t.deleted_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'))::text from app.note_type t"

private val NOTE_EXPR = "jsonb_build_object(" +
    "'id', n.id, 'note_type_id', n.note_type_id, 'type', n.type, 'language', n.language, 'entry_seq', n.entry_seq, " +
    "'kanji_literal', n.kanji_literal, 'grammar_point_id', n.grammar_point_id, 'fields', n.fields, 'tags', to_jsonb(n.tags), " +
    "'mnemonic', n.mnemonic, 'source_sentence', n.source_sentence, 'source_sentence_id', n.source_sentence_id, " +
    "'source_media_id', n.source_media_id, 'origin', n.origin, 'created_by_device_id', n.created_by_device_id, " +
    "'created_at', ${ts("n.created_at")}, 'updated_at', ${ts("n.updated_at")}, 'deleted_at', ${ts("n.deleted_at")}, " +
    "'merged_into_id', n.merged_into_id)"

private val NOTE_JSON = "select $NOTE_EXPR::text from app.note n"

private val CARD_JSON = "select jsonb_build_object(" +
    "'id', c.id, 'note_id', c.note_id, 'kind', c.kind, 'preset_id', c.preset_id, 'suspended', c.suspended, " +
    "'buried_until', ${ts("c.buried_until")}, 'flags', c.flags, 'state', c.state, 'step', c.step, 'stability', c.stability, " +
    "'difficulty', c.difficulty, 'due_at', ${ts("c.due_at")}, 'interval_days', c.interval_days, " +
    "'last_review_at', ${ts("c.last_review_at")}, 'reps', c.reps, 'lapses', c.lapses, " +
    "'created_at', ${ts("c.created_at")}, 'updated_at', ${ts("c.updated_at")}, 'deleted_at', ${ts("c.deleted_at")}, " +
    "'merged_into_id', c.merged_into_id)::text from app.card c"

/** Read side of the notes API: rows as the API's JSON. Reads only; every write is an op. */
internal class NoteViews(private val dsl: DSLContext) {
    private fun parse(text: String): ObjectNode = json.readTree(text) as ObjectNode

    fun noteType(id: String): ObjectNode? =
        dsl.fetchOptional("$NOTE_TYPE_JSON where t.id = ?::uuid", id).map { parse(it.get(0, String::class.java)) }.orElse(null)

    fun noteTypes(includeDeleted: Boolean): List<ObjectNode> =
        dsl.fetch(
            "$NOTE_TYPE_JSON ${if (includeDeleted) "" else "where t.deleted_at is null"} order by t.builtin desc, t.created_at, t.id",
        ).map { parse(it.get(0, String::class.java)) }

    /** The note without its cards (`cards` is `[]`). */
    fun noteRow(id: String): ObjectNode? =
        dsl.fetchOptional("$NOTE_JSON where n.id = ?::uuid", id).map { parse(it.get(0, String::class.java)).withCards(emptyList()) }.orElse(null)

    /** A note with its live cards. */
    fun note(id: String): ObjectNode? = noteRow(id)?.let { withLiveCards(listOf(it)).single() }

    /** Fills the `cards` of each note with its live cards ordered by kind. */
    fun withLiveCards(notes: List<ObjectNode>): List<ObjectNode> {
        if (notes.isEmpty()) return notes
        val threshold = leechThreshold()
        val byNote = dsl.fetch(
            "$CARD_JSON where c.note_id = any(?::uuid[]) and c.deleted_at is null order by c.kind, c.id",
            notes.map { it["id"].asText() }.toTypedArray(),
        ).map { parse(it.get(0, String::class.java)) }.groupBy { it["note_id"].asText() }
        for (n in notes) {
            val cards = byNote[n["id"].asText()].orEmpty()
            cards.forEach { it.put("leech", it["lapses"].asInt() >= threshold) }
            n.withCards(cards)
        }
        return notes
    }

    fun liveCardKinds(noteId: String): Set<String> =
        dsl.fetch("select kind from app.card where note_id = ?::uuid and deleted_at is null", noteId).map { it.get(0, String::class.java) }.toSet()

    /** The live note holding a reference (`entry_seq`, `kanji_literal`, `grammar_point_id`), if any. */
    fun liveHolder(column: String, value: Any): String? {
        require(column in setOf("entry_seq", "kanji_literal", "grammar_point_id"))
        val cast = if (column == "grammar_point_id") "?::uuid" else "?"
        return dsl.fetchOptional("select id::text from app.note where $column = $cast and deleted_at is null", value)
            .map { it.get(0, String::class.java) }.orElse(null)
    }

    fun leechThreshold(): Int {
        val v = dsl.fetchOptional("select value::text from app.settings where key = 'scheduler.leech_threshold'")
            .map { it.get(0, String::class.java) }.orElse(null)
        return v?.toIntOrNull() ?: DEFAULT_LEECH_THRESHOLD
    }

    companion object {
        /** SCHEMA.md settings table: `scheduler.leech_threshold` defaults to 8. */
        const val DEFAULT_LEECH_THRESHOLD = 8

        /** Runs a list query that selects the note JSON text in column 0. */
        fun parseNotes(rows: List<String>): List<ObjectNode> = rows.map { (json.readTree(it) as ObjectNode).withCards(emptyList()) }

        /** Note JSON text, `created_at::text` (cursor key) and the id, for list queries. */
        val NOTE_SELECT = "select $NOTE_EXPR::text, n.created_at::text, n.id::text from app.note n"
    }
}

private fun ObjectNode.withCards(cards: List<ObjectNode>): ObjectNode {
    val arr: ArrayNode = json.createArrayNode()
    cards.forEach { arr.add(it) }
    set<JsonNode>("cards", arr)
    return this
}

/** The core's view of a note type row (templates sorted by kind: jsonb does not keep insertion order). */
internal fun ObjectNode.toNoteTypeInfo(): NoteTypeInfo = NoteTypeInfo(
    baseType = NoteBaseType.entries.first { it.wire == this["base_type"].asText() },
    fieldKeys = this["fields"].map { it["key"].asText() },
    templates = this["templates"].fields().asSequence().sortedBy { it.key }.map { (k, v) ->
        TemplateInfo(k, v["default"].asBoolean(false), v["front"]?.takeUnless { it.isNull }?.asText(), v["back"]?.takeUnless { it.isNull }?.asText())
    }.toList(),
    css = this["css"].asText(),
)
