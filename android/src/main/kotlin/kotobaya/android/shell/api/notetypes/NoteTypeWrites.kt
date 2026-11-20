package kotobaya.android.shell.api.notetypes

import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.actions.CardMints
import kotobaya.android.shell.api.actions.KEY_RE
import kotobaya.android.shell.api.actions.enumValue
import kotobaya.android.shell.api.actions.escapePointer
import kotobaya.android.shell.api.actions.exclusive
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.pathId
import kotobaya.android.shell.api.actions.string
import kotobaya.android.shell.api.actions.stringValue
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.actions.uuidValue
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.api.identity.noteTypeView
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest

private val BASE_TYPES = setOf("vocab", "kanji", "phrase", "grammar", "sentence", "custom")
private val VOCAB_KEYS = listOf("expression", "reading", "meaning")
private val FIELD_KINDS = setOf("text", "audio", "image")

/** Seeded field keys and template kinds of each built-in note type (the rows `Learning.sq` seeds: core `BuiltIns`). */
private val SEEDS: Map<String, Pair<Set<String>, Set<String>>> by lazy {
    InMemoryLearningTables.seeded().all(LearningTable.NOTE_TYPE).associate { r ->
        val fields = (r["fields"] as? JsonArray)?.items.orEmpty().mapNotNull { (it as? JsonObject)?.members?.get("key").text() }.toSet()
        val kinds = (r["templates"] as? JsonObject)?.members?.keys.orEmpty()
        checkNotNull(r["id"].text()) to (fields to kinds)
    }
}

private fun writes(call: LocalCall): LocalWrites<SqlLearningTables> = call.services.get()

private fun row(t: LearningTables, id: String): Row? = t.get(LearningTable.NOTE_TYPE, listOf(JsonString(id)))

private fun noType(): Nothing = throw ProblemException(Problems.notFound("No such note type."))

private fun builtinImmutable(detail: String): Nothing = throw ProblemException(Problems.conflict("builtin_immutable", detail))

/** `fields` (server `fieldDefs`): 1..[max] `{key, label, kind}` with unique keys; the cleaned array. */
private fun FieldErrors.fieldDefs(node: JsonValue?, max: Int): JsonArray? {
    if (node == null) {
        add("/fields", "required", "fields is required.")
        return null
    }
    val items = (node as? JsonArray)?.items
    if (items == null) {
        add("/fields", "invalid_type", "Must be an array.")
        return null
    }
    if (items.isEmpty() || items.size > max) {
        add("/fields", "out_of_range", "Between 1 and $max fields.")
        return null
    }
    val out = ArrayList<JsonValue>()
    val seen = HashSet<String>()
    items.forEachIndexed { i, f ->
        val at = "/fields/$i"
        if (f !is JsonObject) {
            add(at, "invalid_type", "Must be an object.")
            return@forEachIndexed
        }
        unknownKeys(f, setOf("key", "label", "kind"))
        val key = f.members["key"]
        val labelNode = f.members["label"]
        val label = if (labelNode == null) {
            add("$at/label", "required", "label is required.")
            null
        } else {
            stringValue(labelNode, "$at/label", 1, 100)
        }
        val kind = f.members["kind"]
        var ok = label != null
        val keyText = key.text()
        if (keyText == null || !KEY_RE.matches(keyText) || keyText.length > 40) {
            add("$at/key", if (key == null) "required" else "invalid_value", "A lowercase key of at most 40 characters.")
            ok = false
        } else if (!seen.add(keyText)) {
            add("$at/key", "invalid_value", "Duplicate field key $keyText.")
            ok = false
        }
        if (kind.text() == null || kind.text() !in FIELD_KINDS) {
            add("$at/kind", if (kind == null) "required" else "invalid_value", "Must be text, audio or image.")
            ok = false
        }
        if (ok) out += jsonObj("key" to JsonString(checkNotNull(keyText)), "label" to JsonString(checkNotNull(label)), "kind" to checkNotNull(kind))
    }
    return JsonArray(out)
}

/** A vocab note type needs `expression`, `reading` and `meaning` (C-2): `invalid_value` at `/fields`. */
private fun FieldErrors.vocabKeys(fields: JsonArray) {
    val keys = fields.items.mapNotNull { (it as? JsonObject)?.members?.get("key").text() }.toSet()
    if (!keys.containsAll(VOCAB_KEYS)) add("/fields", "invalid_value", "A vocab note type needs the field keys expression, reading and meaning.")
}

/** `templates` (server `templateMap`): kind → `{default, front, back}`; the cleaned object. */
private fun FieldErrors.templateMap(node: JsonValue?, at: String, max: Int): JsonObject? {
    if (node == null) {
        add(at, "required", "templates is required.")
        return null
    }
    val members = (node as? JsonObject)?.members
    if (members == null) {
        add(at, "invalid_type", "Must be an object.")
        return null
    }
    if (members.isEmpty() || members.size > max) {
        add(at, "out_of_range", "At least one kind${if (max == Int.MAX_VALUE) "" else " and at most $max"}.")
        return null
    }
    val out = LinkedHashMap<String, JsonValue>()
    for ((kind, t) in members) {
        val tat = "$at/${escapePointer(kind)}"
        if (!KEY_RE.matches(kind) || kind.length > 40) {
            add(tat, "invalid_value", "A kind is a lowercase identifier of at most 40 characters.")
            continue
        }
        if (t !is JsonObject) {
            add(tat, "invalid_type", "Must be an object.")
            continue
        }
        unknownKeys(t, setOf("default", "front", "back"))
        val d = t.members["default"]
        if (d !is JsonBool) {
            add("$tat/default", if (d == null) "required" else "invalid_type", "Must be a boolean.")
            continue
        }
        val sides = listOf("front", "back").map { side ->
            when (val v = t.members[side]) {
                null -> { add("$tat/$side", "required", "$side is required (null = built-in)."); null }
                JsonNull -> JsonNull
                else -> stringValue(v, "$tat/$side", 0, 100_000)?.let(::JsonString)
            }
        }
        if (sides.all { it != null }) out[kind] = jsonObj("default" to d, "front" to checkNotNull(sides[0]), "back" to checkNotNull(sides[1]))
    }
    return JsonObject(out)
}

/** A refused `note_type.*` pointer (`/payload[/set]/<register>…`) as the request property (server `removePrefix("/set")`). */
private fun noteTypePointer(r: MintRefusal.Invalid): String {
    val p = r.pointer.removePrefix("/payload").removePrefix("/set")
    return if (p.removePrefix("/").substringBefore('/') in setOf("id", "name", "base_type", "fields", "templates", "css")) p else ""
}

/**
 * `createNoteType` (SHELL.md 4.3, server `NoteTypeApi.create`): body checks (unique field keys; a `vocab` type has
 * `expression`, `reading`, `meaning`); a type with the request's `id` in the replica → `200` with it, nothing minted
 * (L-4); else one `note_type.created` (the op-size rule may refuse it: `413`).
 */
internal fun createNoteType(call: LocalCall): ShellResponse {
    call.query()
    val body = call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, setOf("id", "name", "base_type", "fields", "templates", "css"))
    val id = body.members["id"]?.let { e.uuidValue(it, "/id") }
    val name = e.string(body, "name", 1, 100, required = true)
    val base = e.enumValue(body, "base_type", BASE_TYPES, required = true)
    val fields = e.fieldDefs(body.members["fields"], 50)
    val templates = e.templateMap(body.members["templates"], "/templates", 20)
    val css = e.string(body, "css", 0, 100_000)
    if (base == "vocab" && fields != null) e.vocabKeys(fields)
    e.throwIfAny()
    val nowMs = call.services.get<ShellIdentity>().nowMs()
    val (status, type) = writes(call).exclusive<Pair<Int, JsonObject>> { tx ->
        val newId = id ?: CardMints.newId(nowMs)
        row(tx.learning, newId)?.let { return@exclusive WritePlan.Answer(200 to noteTypeView(it)) }
        val payload = jsonObj(
            "id" to JsonString(newId),
            "name" to JsonString(checkNotNull(name)),
            "base_type" to JsonString(checkNotNull(base)),
            "fields" to checkNotNull(fields),
            "templates" to checkNotNull(templates),
            "css" to JsonString(css ?: ""),
        )
        WritePlan.Mint(listOf(MintRequest(OpType.NOTE_TYPE_CREATED, payload)), ::noteTypePointer) { after, _ ->
            201 to noteTypeView(checkNotNull(row(after.learning, newId)) { "note type $newId missing after note_type.created" })
        }
    }
    return ShellResponse.json(type, status)
}

/**
 * `updateNoteType` (server `NoteTypeApi.update` and the op rules it pushes through): body shape checks, `404` / `410`,
 * then the vocab keys (`422` at `/fields`) and the built-in rule (a built-in type cannot lose a seeded field key or
 * template: `409 builtin_immutable`), all before minting one `note_type.updated` with the registers present.
 */
internal fun updateNoteType(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("note_type_id", "note type")
    val body = call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, setOf("name", "fields", "templates", "css"))
    if (body.members.isEmpty()) e.add("", "required", "At least one property is required.")
    val name = e.string(body, "name", 1, 100)
    val fields = body.members["fields"]?.let { e.fieldDefs(it, 50) }
    val templates = body.members["templates"]?.let { e.templateMap(it, "/templates", Int.MAX_VALUE) }
    val css = e.string(body, "css", 0, 100_000)
    val type = writes(call).exclusive<JsonObject> { tx ->
        val current = row(tx.learning, id) ?: noType()
        if (!current["deleted_at"].isNullish()) throw ProblemException(Problems.gone("The note type was deleted."))
        if (fields != null && current["base_type"].text() == "vocab") e.vocabKeys(fields)
        e.throwIfAny()
        SEEDS[id]?.let { (seededFields, seededKinds) ->
            val keys = fields?.items?.mapNotNull { (it as? JsonObject)?.members?.get("key").text() }?.toSet()
            if (keys != null && !keys.containsAll(seededFields)) builtinImmutable("A built-in note type's seeded field keys cannot be removed.")
            // the API's template shape has no null value; the op rule is kept for completeness
            if (templates?.members?.any { (k, v) -> v == JsonNull && k in seededKinds } == true) {
                builtinImmutable("A built-in note type's seeded templates cannot be removed.")
            }
        }
        val set = LinkedHashMap<String, JsonValue>()
        name?.let { set["name"] = JsonString(it) }
        fields?.let { set["fields"] = it }
        templates?.let { set["templates"] = it }
        css?.let { set["css"] = JsonString(it) }
        val payload = jsonObj("id" to JsonString(id), "set" to JsonObject(set))
        WritePlan.Mint(listOf(MintRequest(OpType.NOTE_TYPE_UPDATED, payload)), ::noteTypePointer) { after, _ ->
            noteTypeView(checkNotNull(row(after.learning, id)) { "note type $id vanished" })
        }
    }
    return ShellResponse.json(type)
}

/**
 * `deleteNoteType` (server `NoteTypeApi.delete`): unknown `404`; built-in `409 builtin_immutable`; already deleted
 * `204`, nothing minted; a live note of the type **in the replica** `409 in_use`; else one `note_type.deleted`.
 */
internal fun deleteNoteType(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("note_type_id", "note type")
    writes(call).exclusive<Unit> { tx ->
        val t = tx.learning
        val current = row(t, id) ?: noType()
        if (current["builtin"] == JsonBool(true)) builtinImmutable("Built-in note types cannot be deleted.")
        if (!current["deleted_at"].isNullish()) return@exclusive WritePlan.Answer(Unit)
        if (t.where(LearningTable.NOTE, "note_type_id", JsonString(id)).any { it["deleted_at"].isNullish() }) {
            throw ProblemException(Problems.conflict("in_use", "Live notes use this note type; delete them first."))
        }
        WritePlan.Mint(listOf(MintRequest(OpType.NOTE_TYPE_DELETED, jsonObj("id" to JsonString(id)))), ::noteTypePointer) { _, _ -> }
    }
    return ShellResponse.noContent()
}
