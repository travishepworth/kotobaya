package kotobaya.notes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.sync.OpType
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply

internal val BASE_TYPES = setOf("vocab", "kanji", "phrase", "grammar", "sentence", "custom")
private val VOCAB_KEYS = listOf("expression", "reading", "meaning")

/** `listNoteTypes`, `createNoteType`, `getNoteType`, `updateNoteType`, `deleteNoteType`. */
internal class NoteTypeApi(private val db: Database, private val opApply: OpApply) {
    suspend fun list(call: ApplicationCall) {
        val deleted = call.includeDeleted()
        call.respondJson(mapOf("items" to db.tx { NoteViews(it).noteTypes(deleted) }))
    }

    suspend fun get(call: ApplicationCall) {
        val id = call.pathId("note_type_id")
        val deleted = call.includeDeleted()
        val row = db.tx { NoteViews(it).noteType(id) } ?: throw Problems.notFound("No such note type.")
        if (!deleted && !row["deleted_at"].isNull) throw gone("note type", row["deleted_at"].asText())
        call.respondJson(row)
    }

    suspend fun create(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("id", "name", "base_type", "fields", "templates", "css"))
        val id = e.uuid(body, "id")
        val name = e.string(body, "name", 1, 100, required = true)
        val base = e.enum(body, "base_type", BASE_TYPES, required = true)
        val fields = e.fieldDefs(body.get("fields"), 50)
        val templates = e.templateMap(body.get("templates"), "/templates", 20, required = true)
        val css = e.string(body, "css", 0, 100_000)
        if (base == "vocab" && fields != null) e.vocabKeys(fields)
        e.throwIfAny()

        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val newId = id ?: Uuid7.generate().toString()
            val views = NoteViews(tx.dsl)
            views.noteType(newId)?.let { return@rest Reply(HttpStatusCode.OK, it) }
            val p = json.createObjectNode()
            p.put("id", newId).put("name", name).put("base_type", base)
            p.set<JsonNode>("fields", fields)
            p.set<JsonNode>("templates", templates)
            p.put("css", css ?: "")
            tx.emit(OpType.NOTE_TYPE_CREATED, payload(p))
            Reply(HttpStatusCode.Created, views.noteType(newId) ?: error("note type $newId missing after note_type.created"), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun update(call: ApplicationCall) {
        val id = call.pathId("note_type_id")
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("name", "fields", "templates", "css"))
        if (body.isEmpty) e.add("", "required", "At least one property is required.")
        val name = e.string(body, "name", 1, 100)
        val fields = body.get("fields")?.let { e.fieldDefs(it, 50) }
        val templates = body.get("templates")?.let { e.templateMap(it, "/templates", Int.MAX_VALUE, required = true) }
        val css = e.string(body, "css", 0, 100_000)

        val reply = opApply.rest(call.callerTokenId(), { it.removePrefix("/set") }) { tx ->
            val row = NoteViews(tx.dsl).noteType(id) ?: throw Problems.notFound("No such note type.")
            if (!row["deleted_at"].isNull) throw gone("note type", row["deleted_at"].asText())
            if (fields != null && row["base_type"].asText() == "vocab") e.vocabKeys(fields)
            e.throwIfAny()
            val set = json.createObjectNode()
            name?.let { set.put("name", it) }
            fields?.let { set.set<JsonNode>("fields", it) }
            templates?.let { set.set<JsonNode>("templates", it) }
            css?.let { set.put("css", it) }
            val p = json.createObjectNode().put("id", id)
            p.set<JsonNode>("set", set)
            tx.emit(OpType.NOTE_TYPE_UPDATED, payload(p))
            Reply(HttpStatusCode.OK, NoteViews(tx.dsl).noteType(id) ?: error("note type $id vanished"), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun delete(call: ApplicationCall) {
        val id = call.pathId("note_type_id")
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val row = NoteViews(tx.dsl).noteType(id) ?: throw Problems.notFound("No such note type.")
            if (row["builtin"].asBoolean()) {
                throw ApiException(HttpStatusCode.Conflict, "builtin_immutable", "Built-in cannot be changed this way", "Built-in note types cannot be deleted.")
            }
            if (!row["deleted_at"].isNull) return@rest Reply(HttpStatusCode.NoContent, null)
            if (tx.dsl.fetchExists(tx.dsl.selectOne().from("app.note").where("note_type_id = ?::uuid and deleted_at is null", id))) {
                throw ApiException(HttpStatusCode.Conflict, "in_use", "Note type in use", "Live notes use this note type; delete them first.")
            }
            tx.emit(OpType.NOTE_TYPE_DELETED, payload(json.createObjectNode().put("id", id)))
            Reply(HttpStatusCode.NoContent, null, tx.lastSeq)
        }
        call.send(reply)
    }
}

/** `fields`: 1..[max] `{key, label, kind}` with unique keys; returns the cleaned array. */
internal fun Errors.fieldDefs(node: JsonNode?, max: Int): ArrayNode? {
    if (node == null) {
        add("/fields", "required", "fields is required.")
        return null
    }
    if (!node.isArray) {
        add("/fields", "invalid_type", "Must be an array.")
        return null
    }
    if (node.isEmpty || node.size() > max) {
        add("/fields", "out_of_range", "Between 1 and $max fields.")
        return null
    }
    val out = json.createArrayNode()
    val seen = mutableSetOf<String>()
    node.forEachIndexed { i, f ->
        val at = "/fields/$i"
        if (f !is ObjectNode) {
            add(at, "invalid_type", "Must be an object.")
            return@forEachIndexed
        }
        unknownKeys(f, setOf("key", "label", "kind"))
        val key = f.get("key")
        val label = f.get("label")?.let { stringValue(it, "$at/label", 1, 100) } ?: run {
            if (f.get("label") == null) add("$at/label", "required", "label is required.")
            null
        }
        val kind = f.get("kind")
        var ok = label != null
        if (key == null || !key.isTextual || !KEY_RE.matches(key.asText()) || key.asText().length > 40) {
            add("$at/key", if (key == null) "required" else "invalid_value", "A lowercase key of at most 40 characters.")
            ok = false
        } else if (!seen.add(key.asText())) {
            add("$at/key", "invalid_value", "Duplicate field key ${key.asText()}.")
            ok = false
        }
        if (kind == null || !kind.isTextual || kind.asText() !in setOf("text", "audio", "image")) {
            add("$at/kind", if (kind == null) "required" else "invalid_value", "Must be text, audio or image.")
            ok = false
        }
        if (ok) out.add(json.createObjectNode().put("key", key?.asText()).put("label", label).put("kind", kind?.asText()))
    }
    return out
}

/** A vocab note type needs `expression`, `reading` and `meaning` (C-2): `invalid_value` at `/fields`. */
internal fun Errors.vocabKeys(fields: ArrayNode) {
    val keys = fields.map { it["key"].asText() }.toSet()
    if (!keys.containsAll(VOCAB_KEYS)) add("/fields", "invalid_value", "A vocab note type needs the field keys expression, reading and meaning.")
}

/** `templates`: a map of kind to `{default, front, back}`. */
internal fun Errors.templateMap(node: JsonNode?, at: String, max: Int, required: Boolean): ObjectNode? {
    if (node == null) {
        if (required) add(at, "required", "templates is required.")
        return null
    }
    if (node !is ObjectNode) {
        add(at, "invalid_type", "Must be an object.")
        return null
    }
    if (node.isEmpty || node.size() > max) {
        add(at, "out_of_range", "At least one kind${if (max == Int.MAX_VALUE) "" else " and at most $max"}.")
        return null
    }
    val out = json.createObjectNode()
    for ((kind, t) in node.fields().asSequence().toList()) {
        val tat = "$at/${escapePointer(kind)}"
        if (!KEY_RE.matches(kind) || kind.length > 40) {
            add(tat, "invalid_value", "A kind is a lowercase identifier of at most 40 characters.")
            continue
        }
        if (t !is ObjectNode) {
            add(tat, "invalid_type", "Must be an object.")
            continue
        }
        unknownKeys(t, setOf("default", "front", "back"))
        val d = t.get("default")
        if (d == null || !d.isBoolean) {
            add("$tat/default", if (d == null) "required" else "invalid_type", "Must be a boolean.")
            continue
        }
        val sides = listOf("front", "back").map { side ->
            val v = t.get(side)
            when {
                v == null -> { add("$tat/$side", "required", "$side is required (null = built-in)."); null }
                v.isNull -> v
                else -> stringValue(v, "$tat/$side", 0, 100_000)?.let { json.nodeFactory.textNode(it) }
            }
        }
        if (sides.all { it != null }) {
            val o = json.createObjectNode().put("default", d.asBoolean())
            o.set<JsonNode>("front", sides[0])
            o.set<JsonNode>("back", sides[1])
            out.set<JsonNode>(kind, o)
        }
    }
    return out
}
