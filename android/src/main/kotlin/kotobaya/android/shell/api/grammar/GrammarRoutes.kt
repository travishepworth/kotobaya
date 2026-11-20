package kotobaya.android.shell.api.grammar

import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.QueryParams
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.actions.CardMints
import kotobaya.android.shell.api.actions.exclusive
import kotobaya.android.shell.api.actions.integral
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.pageLimit
import kotobaya.android.shell.api.actions.pathId
import kotobaya.android.shell.api.actions.string
import kotobaya.android.shell.api.actions.stringValue
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.actions.uuidValue
import kotobaya.android.shell.api.confusables.Keyset
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.api.identity.timestamp
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest

private val PROPS = setOf("title", "pattern", "meaning", "explanation", "notes", "jlpt", "detection_rules", "related_ids", "links", "is_stub")

private fun Row.stamp(column: String): JsonValue = (this[column] as? JsonNumber)?.value?.toLong()?.let { JsonString(timestamp(it)) } ?: JsonNull

private fun Row.col(column: String): JsonValue = this[column] ?: JsonNull

internal fun grammarRow(t: LearningTables, id: String): Row? = t.get(LearningTable.GRAMMAR_POINT, listOf(JsonString(id)))

internal fun noGrammarPoint(): Nothing = throw ProblemException(Problems.notFound("No such grammar point."))

internal fun grammarGone(r: Row): Nothing =
    throw ProblemException(Problems.gone("The grammar point was deleted at ${(r.stamp("deleted_at") as? JsonString)?.value}."))

/**
 * `GrammarPoint` (server `GrammarApi.SELECT`). `note_id`: a live note studying the point (the smallest id when there
 * are several). `example_count` (SHELL.md 4.3, documented difference): the point's live spans whose sentence is on
 * the device (manual spans, detected spans of installed media), so it may be lower than the server's.
 */
internal fun grammarView(t: LearningTables, r: Row): JsonObject {
    val id = checkNotNull(r["id"].text()) { "grammar point without id" }
    val noteId = t.where(LearningTable.NOTE, "grammar_point_id", JsonString(id))
        .filter { it["deleted_at"].isNullish() }.mapNotNull { it["id"].text() }.minOrNull()
    val examples = t.where(LearningTable.SENTENCE_GRAMMAR_SPAN, "grammar_point_id", JsonString(id)).count { s ->
        s["deleted_at"].isNullish() && s["sentence_id"].text()?.let { t.get(LearningTable.SENTENCE, listOf(JsonString(it))) } != null
    }
    return jsonObj(
        "id" to JsonString(id),
        "language" to (r["language"] as? JsonString ?: JsonString("ja")),
        "title" to r.col("title"),
        "pattern" to r.col("pattern"),
        "meaning" to r.col("meaning"),
        "explanation" to r.col("explanation"),
        "notes" to r.col("notes"),
        "jlpt" to r.col("jlpt"),
        "detection_rules" to (r["detection_rules"] as? JsonArray ?: JsonArray(emptyList())),
        "related_ids" to (r["related_ids"] as? JsonArray ?: JsonArray(emptyList())),
        "links" to (r["links"] as? JsonArray ?: JsonArray(emptyList())),
        "is_stub" to (r["is_stub"] as? JsonBool ?: JsonBool(false)),
        "note_id" to (noteId?.let(::JsonString) ?: JsonNull),
        "example_count" to JsonNumber(examples.toDouble()),
        "created_at" to r.stamp("created_at"),
        "updated_at" to r.stamp("updated_at"),
        "deleted_at" to r.stamp("deleted_at"),
    )
}

private fun writes(call: LocalCall): LocalWrites<SqlLearningTables> = call.services.get()

private fun store(call: LocalCall): SqlDelightDeviceStore = call.services.get()

private fun includeDeleted(q: QueryParams, e: FieldErrors): Boolean = q.bool("include_deleted", false, e)

/** A refused `grammar.*` pointer (`/payload[/set]/<register>…`) as the request property (server `removePrefix("/set")`). */
private fun grammarPointer(r: MintRefusal.Invalid): String {
    val p = r.pointer.removePrefix("/payload").removePrefix("/set")
    val member = p.removePrefix("/").substringBefore('/')
    return if (member in PROPS || member == "id") p else ""
}

/**
 * The properties shared by create and update (everything but `id` and `title`), server `GrammarApi.validateProps`,
 * into [out] in the server's order. `detection_rules`: the shape only; their syntax is the server's (SHELL.md 4.3,
 * documented difference: a rule that does not parse is stored and synced, `testGrammarRules` shows the error).
 */
private fun FieldErrors.props(body: JsonObject, out: LinkedHashMap<String, JsonValue>) {
    val m = body.members
    for ((k, max) in listOf("pattern" to 300, "meaning" to 2000, "explanation" to 100_000, "notes" to 100_000)) {
        string(body, k, 0, max)?.let { out[k] = JsonString(it) }
    }
    m["jlpt"]?.let { v ->
        val n = integral(v)
        when {
            v == JsonNull -> out["jlpt"] = JsonNull
            n != null && n in 1..5 -> out["jlpt"] = JsonNumber(n.toDouble())
            else -> add("/jlpt", "invalid_value", "Must be 1 to 5 or null.")
        }
    }
    m["is_stub"]?.let { v -> if (v is JsonBool) out["is_stub"] = v else add("/is_stub", "invalid_type", "Must be a boolean.") }
    m["detection_rules"]?.let { v ->
        val items = (v as? JsonArray)?.items
        if (items == null || items.size > 20) {
            add("/detection_rules", "invalid_value", "An array of at most 20 rules.")
            return@let
        }
        val arr = ArrayList<JsonValue>()
        items.forEachIndexed { i, r ->
            val at = "/detection_rules/$i"
            if (r !is JsonObject) {
                add(at, "invalid_type", "Must be an object.")
                return@forEachIndexed
            }
            val ruleNode = r.members["rule"]
            val rule = if (ruleNode == null) {
                add("$at/rule", "required", "rule is required.")
                null
            } else {
                stringValue(ruleNode, "$at/rule", 1, 1000)
            }
            val draft = r.members["draft"]
            if (draft != null && draft !is JsonBool) add("$at/draft", "invalid_type", "Must be a boolean.")
            r.members.keys.filter { it != "rule" && it != "draft" }.forEach { add("$at/$it", "unknown_field", "Unknown property $it.") }
            if (rule != null) {
                val o = linkedMapOf<String, JsonValue>("rule" to JsonString(rule))
                if (draft is JsonBool) o["draft"] = draft
                arr += JsonObject(o)
            }
        }
        out["detection_rules"] = JsonArray(arr)
    }
    m["related_ids"]?.let { v ->
        val items = (v as? JsonArray)?.items
        if (items == null || items.size > 50) {
            add("/related_ids", "invalid_value", "An array of at most 50 ids.")
            return@let
        }
        val ids = items.mapIndexedNotNull { i, x -> uuidValue(x, "/related_ids/$i") }
        if (ids.toSet().size != ids.size) add("/related_ids", "invalid_value", "Ids must be unique.")
        out["related_ids"] = JsonArray(ids.map(::JsonString))
    }
    m["links"]?.let { v ->
        val items = (v as? JsonArray)?.items
        if (items == null || items.size > 50) {
            add("/links", "invalid_value", "An array of at most 50 links.")
            return@let
        }
        val arr = ArrayList<JsonValue>()
        items.forEachIndexed { i, l ->
            val at = "/links/$i"
            if (l !is JsonObject) {
                add(at, "invalid_type", "Must be an object.")
                return@forEachIndexed
            }
            val title = l.members["title"]?.let { stringValue(it, "$at/title", 1, 300) } ?: run { if ("title" !in l.members) add("$at/title", "required", "title is required."); null }
            val url = l.members["url"]?.let { stringValue(it, "$at/url", 1, 2000) } ?: run { if ("url" !in l.members) add("$at/url", "required", "url is required."); null }
            l.members.keys.filter { it != "title" && it != "url" }.forEach { add("$at/$it", "unknown_field", "Unknown property $it.") }
            if (title != null && url != null) arr += jsonObj("title" to JsonString(title), "url" to JsonString(url))
        }
        out["links"] = JsonArray(arr)
    }
}

private val GRAMMAR_ORDER = Keyset.columns(Keyset.INT_DESC_NULLS_LAST, Keyset.TEXT_ASC, Keyset.TEXT_ASC)

private fun grammarKey(r: Row): List<String> =
    listOf((r["jlpt"] as? JsonNumber)?.value?.toLong()?.toString() ?: "", r["title"].text().orEmpty(), r["id"].text().orEmpty())

/**
 * `listGrammarPoints` (server `GrammarApi.list`): filters `q` (case-insensitive substring of `title + " " + pattern`
 * or `meaning`), `jlpt`, `is_stub`, `include_deleted`; ordered by `jlpt` descending with NULLs last, then `title`, then
 * `id`; keyset-paged.
 */
internal fun listGrammarPoints(call: LocalCall): ShellResponse {
    val q = call.query("q", "jlpt", "is_stub", "include_deleted", "limit", "cursor")
    val e = FieldErrors()
    val deleted = includeDeleted(q, e)
    val text = q.first("q")?.takeIf { v -> (v.isNotEmpty() && v.length <= 200).also { if (!it) e.add("/q", "invalid_value", "1 to 200 characters.") } }?.lowercase()
    val jlpt = q.first("jlpt")?.let { v -> v.toIntOrNull()?.takeIf { it in 1..5 } ?: run { e.add("/jlpt", "invalid_value", "Must be 1 to 5."); null } }
    val stub = q.first("is_stub")?.let { v ->
        when (v) {
            "true" -> true
            "false" -> false
            else -> { e.add("/is_stub", "invalid_value", "Must be true or false."); null }
        }
    }
    val limit = pageLimit(q.first("limit"), e)
    val hash = listOf(deleted, text, jlpt, stub).joinToString("|").hashCode().toString(16)
    val after = Keyset.decode(q.first("cursor"), 3, hash, e) { it[0].isEmpty() || Keyset.isLong(it[0]) }
    e.throwIfAny()
    val body = store(call).read { tx ->
        val t = tx.learning
        fun matches(r: Row): Boolean {
            if (!deleted && !r["deleted_at"].isNullish()) return false
            if (jlpt != null && (r["jlpt"] as? JsonNumber)?.value?.toLong() != jlpt.toLong()) return false
            if (stub != null && (r["is_stub"] as? JsonBool)?.value != stub) return false
            if (text != null) {
                val head = (r["title"].text().orEmpty() + " " + r["pattern"].text().orEmpty()).lowercase()
                if (!head.contains(text) && !r["meaning"].text().orEmpty().lowercase().contains(text)) return false
            }
            return true
        }
        val (shown, last) = Keyset.page(t.all(LearningTable.GRAMMAR_POINT).asSequence().filter(::matches), ::grammarKey, GRAMMAR_ORDER, after, limit)
        jsonObj(
            "items" to JsonArray(shown.map { grammarView(t, it) }),
            "next_cursor" to (last?.let { JsonString(Keyset.encode(it, hash)) } ?: JsonNull),
        )
    }
    return ShellResponse.json(body)
}

/** `getGrammarPoint`: unknown `404`; deleted `410` unless `include_deleted=true`. */
internal fun getGrammarPoint(call: LocalCall): ShellResponse {
    val q = call.query("include_deleted")
    val id = call.pathId("grammar_point_id", "grammar point")
    val e = FieldErrors()
    val deleted = includeDeleted(q, e)
    e.throwIfAny()
    val view = store(call).read { tx ->
        val r = grammarRow(tx.learning, id) ?: noGrammarPoint()
        if (!deleted && !r["deleted_at"].isNullish()) grammarGone(r)
        grammarView(tx.learning, r)
    }
    return ShellResponse.json(view)
}

/**
 * `createGrammarPoint` (server `GrammarApi.create`): body checks (`title` required); an existing `id` in the replica
 * (live or deleted) → `200` with it, nothing minted (L-4); else one `grammar.created` with the properties present.
 */
internal fun createGrammarPoint(call: LocalCall): ShellResponse {
    call.query()
    val body = call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, PROPS + "id")
    val id = body.members["id"]?.let { e.uuidValue(it, "/id") }
    val p = LinkedHashMap<String, JsonValue>()
    e.string(body, "title", 1, 300, required = true)?.let { p["title"] = JsonString(it) }
    e.props(body, p)
    e.throwIfAny()
    val nowMs = call.services.get<ShellIdentity>().nowMs()
    val (status, point) = writes(call).exclusive<Pair<Int, JsonObject>> { tx ->
        val newId = id ?: CardMints.newId(nowMs)
        grammarRow(tx.learning, newId)?.let { return@exclusive WritePlan.Answer(200 to grammarView(tx.learning, it)) }
        val payload = LinkedHashMap<String, JsonValue>()
        payload["id"] = JsonString(newId)
        payload.putAll(p)
        WritePlan.Mint(listOf(MintRequest(OpType.GRAMMAR_CREATED, JsonObject(payload))), ::grammarPointer) { after, _ ->
            201 to grammarView(after.learning, checkNotNull(grammarRow(after.learning, newId)) { "grammar point $newId missing after grammar.created" })
        }
    }
    return ShellResponse.json(point, status)
}

/**
 * `updateGrammarPoint` (server `GrammarApi.update`): body checks (empty body `422`), then `404` / `410`; one
 * `grammar.updated` writing the registers present.
 */
internal fun updateGrammarPoint(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("grammar_point_id", "grammar point")
    val body = call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, PROPS)
    if (body.members.isEmpty()) e.add("", "required", "At least one property is required.")
    val set = LinkedHashMap<String, JsonValue>()
    e.string(body, "title", 1, 300)?.let { set["title"] = JsonString(it) }
    e.props(body, set)
    e.throwIfAny()
    val point = writes(call).exclusive<JsonObject> { tx ->
        val r = grammarRow(tx.learning, id) ?: noGrammarPoint()
        if (!r["deleted_at"].isNullish()) grammarGone(r)
        val payload = jsonObj("id" to JsonString(id), "set" to JsonObject(set))
        WritePlan.Mint(listOf(MintRequest(OpType.GRAMMAR_UPDATED, payload)), ::grammarPointer) { after, _ ->
            grammarView(after.learning, checkNotNull(grammarRow(after.learning, id)) { "grammar point $id vanished" })
        }
    }
    return ShellResponse.json(point)
}

/** `deleteGrammarPoint` (server `GrammarApi.delete`): unknown `404`; already deleted `204`, nothing minted; else one `grammar.deleted`. */
internal fun deleteGrammarPoint(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("grammar_point_id", "grammar point")
    writes(call).exclusive<Unit> { tx ->
        val r = grammarRow(tx.learning, id) ?: noGrammarPoint()
        if (!r["deleted_at"].isNullish()) return@exclusive WritePlan.Answer(Unit)
        WritePlan.Mint(listOf(MintRequest(OpType.GRAMMAR_DELETED, jsonObj("id" to JsonString(id)))), ::grammarPointer) { _, _ -> }
    }
    return ShellResponse.noContent()
}
