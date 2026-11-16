package kotobaya.android.shell.api.identity

import kotlin.math.floor
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalRoute
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.get
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.store.bookkeeping

/**
 * The identity group's local routes (docs/ANDROID-SHELL.md section 5; SHELL.md 4.3): principal, meta, settings,
 * presets, note types, client error reports. Reads are views of the replica; `updateSettings` mints `settings.set`.
 */
val routes: List<LocalRoute> = listOf(
    LocalRoute("getPrincipal", "GET", "/api/v1/auth/session") { call -> getPrincipal(call) },
    LocalRoute("getMeta", "GET", "/api/v1/meta") { call -> getMeta(call) },
    LocalRoute("reportClientErrors", "POST", "/api/v1/client-errors") { call -> reportClientErrors(call) },
    LocalRoute("listSettings", "GET", "/api/v1/settings") { call -> listSettings(call) },
    LocalRoute("updateSettings", "PATCH", "/api/v1/settings") { call -> updateSettings(call) },
    LocalRoute("listPresets", "GET", "/api/v1/presets") { call -> listPresets(call) },
    LocalRoute("getPreset", "GET", "/api/v1/presets/{preset_id}") { call -> getPreset(call) },
    LocalRoute("listNoteTypes", "GET", "/api/v1/note-types") { call -> listNoteTypes(call) },
    LocalRoute("getNoteType", "GET", "/api/v1/note-types/{note_type_id}") { call -> getNoteType(call) },
)

private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private fun getPrincipal(call: LocalCall): ShellResponse {
    call.query()
    val identity = call.services.get<ShellIdentity>()
    val store = call.services.get<SqlDelightDeviceStore>()
    val deviceId = store.read { it.bookkeeping.deviceId } ?: throw ProblemException(Problems.unauthenticated())
    return ShellResponse.json(
        obj(
            // paired before token_id was kept: the device id stands in (a stable uuid; the SPA only displays it)
            "token_id" to JsonString(identity.tokenId() ?: deviceId),
            "kind" to JsonString("device"),
            "name" to JsonString(identity.deviceName()),
            "scopes" to JsonArray(listOf("read", "write", "review").map(::JsonString)),
            "device_id" to JsonString(deviceId),
            "expires_at" to JsonNull,
            "totp_enabled" to JsonBool(false),
        ),
    )
}

private fun getMeta(call: LocalCall): ShellResponse {
    call.query()
    val identity = call.services.get<ShellIdentity>()
    val store = call.services.get<SqlDelightDeviceStore>()
    val (pullSeq, epoch) = store.read { it.bookkeeping.pullSeq to it.bookkeeping.logEpoch }
    val l = identity.limits()
    fun n(v: Long) = JsonNumber(v.toDouble())
    return ShellResponse.json(
        obj(
            "api_version" to JsonString("v1"),
            "server_version" to JsonString(identity.serverVersion() ?: "unknown"),
            "core_version" to JsonString(identity.coreVersion()),
            "server_device_id" to JsonString(Reserved.SERVER_DEVICE_ID),
            "high_water_seq" to n(pullSeq),
            "log_epoch" to JsonString(epoch ?: NIL_UUID),
            "server_time" to JsonString(timestamp(identity.nowMs())),
            "dict_release" to (identity.dictRelease()?.let { obj("id" to n(it.id), "version" to JsonString(it.version)) } ?: JsonNull),
            "limits" to obj(
                "max_push_ops" to n(l.maxPushOps),
                "max_push_bytes" to n(l.maxPushBytes),
                "max_op_bytes" to n(l.maxOpBytes),
                "max_pull_ops" to n(l.maxPullOps),
                "max_blob_bytes" to n(l.maxBlobBytes),
                "max_json_bytes" to n(l.maxJsonBytes),
                "max_page_size" to n(l.maxPageSize),
            ),
        ),
    )
}

private fun reportClientErrors(call: LocalCall): ShellResponse {
    call.query()
    val intake = call.services.get<ClientErrorIntake>()
    intake.admitRequest()
    val bytes = call.request.body?.let { LocalApi.utf8Length(it) } ?: 0L
    // the 1 MiB limit comes before the body is parsed, as on the server
    if (bytes > ClientErrorIntake.MAX_BODY_BYTES) intake.accept(null, bytes)
    val accepted = intake.accept(call.bodyValue(), bytes)
    return ShellResponse.json(obj("accepted" to JsonNumber(accepted.toDouble())), status = 202)
}

// ---- settings -------------------------------------------------------------------------------------------------------

private fun listSettings(call: LocalCall): ShellResponse {
    call.query()
    val store = call.services.get<SqlDelightDeviceStore>()
    return ShellResponse.json(store.read { settingList(it.learning) })
}

private const val INT4_MIN = -2147483648.0
private const val INT4_MAX = 2147483647.0

/** The validity test of one well-known key (SCHEMA.md 6.1): `null` accepts anything; JSON null is checked by the caller. */
private val WELL_KNOWN: Map<String, (JsonValue) -> Boolean> = run {
    fun int(min: Double = INT4_MIN, max: Double = INT4_MAX): (JsonValue) -> Boolean = { v ->
        val d = (v as? JsonNumber)?.value
        d != null && !d.isNaN() && !d.isInfinite() && d == floor(d) && d >= min && d <= max
    }
    val bool: (JsonValue) -> Boolean = { it is JsonBool }
    mapOf(
        "scheduler.timezone" to { v: JsonValue -> v is JsonString },
        "scheduler.day_rollover_hour" to int(0.0, 23.0),
        "scheduler.new_per_day" to int(),
        "scheduler.reviews_per_day" to int(),
        "scheduler.reviews_unlimited" to bool,
        "scheduler.new_per_session" to int(),
        "scheduler.grading_buttons" to { v: JsonValue -> (v as? JsonNumber)?.value.let { it == 4.0 || it == 5.0 } },
        "scheduler.leech_threshold" to int(),
        "scheduler.bury_siblings" to bool,
        "scheduler.kanji_before_vocab" to bool,
        "scheduler.mined_priority" to int(),
        "audio.prefetch_days" to int(),
        "cards.reading_enabled" to bool, // ADR-035, SCHEMA.md 6.4
    )
}

private val SETTING_KEY_RE = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$")

private fun pointer(vararg parts: String) = parts.joinToString("") { "/" + it.replace("~", "~0").replace("/", "~1") }

/** `updateSettings`: one `settings.set` per present key, ascending key order, all or none (absent keys are unchanged). */
private fun updateSettings(call: LocalCall): ShellResponse {
    val body = call.bodyObject()
    val e = FieldErrors()
    body.members.keys.forEach { if (it != "values") e.add("/$it", "unknown_field", "Unknown property $it.") }
    val values = body.members["values"]
    val entries = ArrayList<Pair<String, JsonValue>>()
    when {
        values == null -> e.add("/values", "required", "values is required.")
        values !is JsonObject -> e.add("/values", "invalid_type", "Must be an object.")
        values.members.isEmpty() -> e.add("/values", "invalid_value", "At least one setting is required.")
        else -> for ((k, v) in values.members) {
            val at = pointer("values", k)
            if (k.length > 200 || !SETTING_KEY_RE.matches(k)) {
                e.add(at, "invalid_value", "Keys are dotted lowercase identifiers of at most 200 characters.")
            } else if (v != JsonNull && WELL_KNOWN[k]?.invoke(v) == false) {
                e.add(at, "invalid_type", "Not a valid value for $k.")
            } else {
                entries += k to v
            }
        }
    }
    e.throwIfAny()
    entries.sortBy { it.first } // ASCII keys: code-point order
    val writes = call.services.get<LocalWrites<SqlLearningTables>>()
    return writes.write { _ ->
        WritePlan.Mint<SqlLearningTables, ShellResponse>(
            requests = entries.map { (k, v) -> MintRequest(OpType.SETTINGS_SET, obj("key" to JsonString(k), "value" to v)) },
            invalidPointer = { r: MintRefusal.Invalid -> entries.getOrNull(r.index)?.let { pointer("values", it.first) } ?: "/values" },
        ) { tx, _ -> ShellResponse.json(settingList(tx.learning)) }
    }
}

// ---- presets and note types ----------------------------------------------------------------------------------------

private fun includeDeleted(call: LocalCall): Boolean {
    val errors = FieldErrors()
    val v = call.query("include_deleted").bool("include_deleted", false, errors)
    errors.throwIfAny()
    return v
}

private fun pathId(call: LocalCall, name: String): String =
    call.path(name).takeIf { UUID_RE.matches(it) } ?: throw ProblemException(Problems.notFound("No such $name."))

private fun listPresets(call: LocalCall): ShellResponse {
    val deleted = includeDeleted(call)
    val store = call.services.get<SqlDelightDeviceStore>()
    val items = store.read { presetRows(it.learning) }.filter { deleted || !it.isDeleted() }.map(::presetView)
    return ShellResponse.json(obj("items" to JsonArray(items)))
}

private fun getPreset(call: LocalCall): ShellResponse {
    val id = pathId(call, "preset_id")
    val deleted = includeDeleted(call)
    val store = call.services.get<SqlDelightDeviceStore>()
    val row = store.read { it.learning.get(LearningTable.FSRS_PARAMS, listOf(JsonString(id))) }
        ?: throw ProblemException(Problems.notFound("No such preset."))
    if (!deleted && row.isDeleted()) throw ProblemException(Problems.gone("The preset was deleted."))
    return ShellResponse.json(presetView(row))
}

private fun listNoteTypes(call: LocalCall): ShellResponse {
    val deleted = includeDeleted(call)
    val store = call.services.get<SqlDelightDeviceStore>()
    val items = store.read { noteTypeRows(it.learning) }.filter { deleted || !it.isDeleted() }.map(::noteTypeView)
    return ShellResponse.json(obj("items" to JsonArray(items)))
}

private fun getNoteType(call: LocalCall): ShellResponse {
    val id = pathId(call, "note_type_id")
    val deleted = includeDeleted(call)
    val store = call.services.get<SqlDelightDeviceStore>()
    val row = store.read { it.learning.get(LearningTable.NOTE_TYPE, listOf(JsonString(id))) }
        ?: throw ProblemException(Problems.notFound("No such note type."))
    if (!deleted && row.isDeleted()) throw ProblemException(Problems.gone("The note type was deleted."))
    return ShellResponse.json(noteTypeView(row))
}
