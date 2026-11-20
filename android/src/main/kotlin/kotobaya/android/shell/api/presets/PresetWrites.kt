package kotobaya.android.shell.api.presets

import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.actions.CardMints
import kotobaya.android.shell.api.actions.exclusive
import kotobaya.android.shell.api.actions.integral
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.pathId
import kotobaya.android.shell.api.actions.string
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.actions.uuidValue
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.api.identity.presetView
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
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

private val PRESET_FIELDS = setOf(
    "name", "desired_retention", "maximum_interval_days", "learning_steps_s", "relearning_steps_s", "fuzz", "easy_days",
)

private fun writes(call: LocalCall): LocalWrites<SqlLearningTables> = call.services.get()

private fun row(t: LearningTables, id: String): Row? = t.get(LearningTable.FSRS_PARAMS, listOf(JsonString(id)))

private fun noPreset(): Nothing = throw ProblemException(Problems.notFound("No such preset."))

/** The preset options present in [o], validated by the API bounds (server `PresetApi.options`), in the server's order. */
private fun FieldErrors.options(o: JsonObject): LinkedHashMap<String, JsonValue> {
    val m = o.members
    val out = LinkedHashMap<String, JsonValue>()
    string(o, "name", 1, 100)?.let { out["name"] = JsonString(it) }
    m["desired_retention"]?.let { v ->
        val d = (v as? JsonNumber)?.value
        when {
            d == null -> add("/desired_retention", "invalid_type", "Must be a number.")
            d < 0.7 || d > 0.99 -> add("/desired_retention", "out_of_range", "Between 0.7 and 0.99.")
            else -> out["desired_retention"] = v
        }
    }
    m["maximum_interval_days"]?.let { v ->
        val n = integral(v)
        if (n == null || n !in 1..36500) add("/maximum_interval_days", "out_of_range", "An integer between 1 and 36500.") else out["maximum_interval_days"] = v
    }
    for (k in listOf("learning_steps_s", "relearning_steps_s")) {
        val v = m[k] ?: continue
        val items = (v as? JsonArray)?.items
        val ok = items != null && items.size <= 10 && items.all { s -> integral(s)?.let { it in 1..864000 } == true }
        if (!ok) add("/$k", "invalid_value", "At most 10 integer steps of 1 to 864000 seconds.") else out[k] = v
    }
    m["fuzz"]?.let { v -> if (v !is JsonBool) add("/fuzz", "invalid_type", "Must be a boolean.") else out["fuzz"] = v }
    m["easy_days"]?.let { v ->
        val items = (v as? JsonArray)?.items
        val ok = items != null && items.size == 7 && items.all { d -> (d as? JsonNumber)?.value?.let { it in 0.0..1.0 } == true }
        if (!ok) add("/easy_days", "invalid_value", "Seven numbers between 0 and 1, Monday first.") else out["easy_days"] = v
    }
    return out
}

/** A refused `fsrs_params.*` pointer (`/payload[/set]/<register>…`) as the request property (server `removePrefix("/set")`). */
private fun presetPointer(r: MintRefusal.Invalid): String {
    val p = r.pointer.removePrefix("/payload").removePrefix("/set")
    val member = p.removePrefix("/").substringBefore('/')
    return if (member in PRESET_FIELDS || member == "id") p else ""
}

/**
 * `createPreset` (SHELL.md 4.3, server `PresetApi.create`): body checks (`name` required, `PresetCreate` bounds); a
 * preset with the request's `id` in the replica (live or deleted) → `200` with it, nothing minted (L-4); else one
 * `fsrs_params.created` with the options present (the apply fills the column defaults).
 */
internal fun createPreset(call: LocalCall): ShellResponse {
    call.query()
    val body = call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, setOf("id") + PRESET_FIELDS)
    val id = body.members["id"]?.let { e.uuidValue(it, "/id") }
    if (body.members["name"] == null) e.add("/name", "required", "name is required.")
    val opts = e.options(body)
    e.throwIfAny()
    val nowMs = call.services.get<ShellIdentity>().nowMs()
    val (status, preset) = writes(call).exclusive<Pair<Int, JsonObject>> { tx ->
        val newId = id ?: CardMints.newId(nowMs)
        row(tx.learning, newId)?.let { return@exclusive WritePlan.Answer(200 to presetView(it)) }
        val payload = LinkedHashMap<String, JsonValue>()
        payload["id"] = JsonString(newId)
        payload.putAll(opts)
        WritePlan.Mint(listOf(MintRequest(OpType.FSRS_PARAMS_CREATED, JsonObject(payload))), ::presetPointer) { after, _ ->
            201 to presetView(checkNotNull(row(after.learning, newId)) { "preset $newId missing after fsrs_params.created" })
        }
    }
    return ShellResponse.json(preset, status)
}

/**
 * `updatePreset` (server `PresetApi.update`): body checks (`PresetPatch`, empty body `422`), then `404` / `410`; one
 * `fsrs_params.updated` writing the registers present. Its apply reschedules the preset's cards by the replay rule
 * (SYNC.md 9.2), the same code as the pull path. Weights are never set here.
 */
internal fun updatePreset(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("preset_id", "preset")
    val body = call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, PRESET_FIELDS)
    if (body.members.isEmpty()) e.add("", "required", "At least one property is required.")
    val opts = e.options(body)
    e.throwIfAny()
    val preset = writes(call).exclusive<JsonObject> { tx ->
        val current = row(tx.learning, id) ?: noPreset()
        if (!current["deleted_at"].isNullish()) throw ProblemException(Problems.gone("The preset was deleted."))
        val payload = jsonObj("id" to JsonString(id), "set" to JsonObject(opts))
        WritePlan.Mint(listOf(MintRequest(OpType.FSRS_PARAMS_UPDATED, payload)), ::presetPointer) { after, _ ->
            presetView(checkNotNull(row(after.learning, id)) { "preset $id vanished" })
        }
    }
    return ShellResponse.json(preset)
}

/**
 * `deletePreset` (server `PresetApi.delete`): unknown `404`; the Default preset `409 builtin_immutable`; already
 * deleted `204`, nothing minted; else one `fsrs_params.deleted` (cards keep `preset_id`, their effective preset becomes
 * the Default: replay on apply).
 */
internal fun deletePreset(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("preset_id", "preset")
    writes(call).exclusive<Unit> { tx ->
        val current = row(tx.learning, id) ?: noPreset()
        if (id == Reserved.DEFAULT_PRESET_ID) {
            throw ProblemException(Problems.conflict("builtin_immutable", "The Default preset cannot be deleted."))
        }
        if (!current["deleted_at"].isNullish()) return@exclusive WritePlan.Answer(Unit)
        WritePlan.Mint(listOf(MintRequest(OpType.FSRS_PARAMS_DELETED, jsonObj("id" to JsonString(id)))), ::presetPointer) { _, _ -> }
    }
    return ShellResponse.noContent()
}
