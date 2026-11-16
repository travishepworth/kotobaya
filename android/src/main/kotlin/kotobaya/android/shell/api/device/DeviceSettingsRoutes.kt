package kotobaya.android.shell.api.device

import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalRoute
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.get
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString

/** The device group's settings routes (SHELL.md 9): dictionary install, storage, notifications, push, AnkiConnect. */
internal val settingsRoutes: List<LocalRoute> = listOf(
    LocalRoute("getDictInstall", "GET", "/shell/v1/dict") { call -> getDictInstall(call) },
    LocalRoute("startDictInstall", "POST", "/shell/v1/dict/install") { call -> startDictInstall(call) },
    LocalRoute("getStorage", "GET", "/shell/v1/storage") { call -> getStorage(call) },
    LocalRoute("updateStorage", "PATCH", "/shell/v1/storage") { call -> updateStorage(call) },
    LocalRoute("getNotificationState", "GET", "/shell/v1/notifications") { call -> getNotificationState(call) },
    LocalRoute("updateNotificationPrefs", "PATCH", "/shell/v1/notifications") { call -> updateNotificationPrefs(call) },
    LocalRoute("requestNotificationPermission", "POST", "/shell/v1/notifications/permission") { call -> requestNotificationPermission(call) },
    LocalRoute("getPushState", "GET", "/shell/v1/push") { call -> getPushState(call) },
    LocalRoute("updatePushState", "PATCH", "/shell/v1/push") { call -> updatePushState(call) },
    LocalRoute("getAnkiConnectState", "GET", "/shell/v1/ankiconnect") { call -> getAnkiConnectState(call) },
    LocalRoute("updateAnkiConnectState", "PATCH", "/shell/v1/ankiconnect") { call -> updateAnkiConnectState(call) },
)

// ------------------------------------------------------------------------------------------- body helpers

private fun JsonObject.int(key: String, min: Int, max: Int, errors: FieldErrors): Int? {
    val v = members[key] ?: return null
    val d = (v as? JsonNumber)?.value
    if (d == null || d != Math.floor(d) || d.isInfinite()) {
        errors.add("/$key", "invalid_type", "Must be an integer.")
        return null
    }
    if (d < min || d > max) {
        errors.add("/$key", "out_of_range", "Must be $min to $max.")
        return null
    }
    return d.toInt()
}

private fun JsonObject.bool(key: String, errors: FieldErrors): Boolean? {
    val v = members[key] ?: return null
    if (v !is JsonBool) {
        errors.add("/$key", "invalid_type", "Must be true or false.")
        return null
    }
    return v.value
}

/** At least one property, else `422` at `""` with `required`. */
private fun JsonObject.requireAny() {
    if (members.isEmpty()) throw ProblemException(Problems.validationFailed("", "required", "Send at least one property."))
}

// ------------------------------------------------------------------------------------------- dictionary

private fun getDictInstall(call: LocalCall): ShellResponse {
    call.query()
    return ShellResponse.json(call.services.get<DictOps>().state().toJson())
}

private fun startDictInstall(call: LocalCall): ShellResponse {
    call.query()
    var scope: String? = null
    when (val v = call.bodyValue()) {
        null -> Unit
        is JsonObject -> {
            v.members.keys.firstOrNull { it != "scope" }?.let {
                throw ProblemException(Problems.validationFailed("/$it", "unknown_field", "Unknown property $it."))
            }
            when (val s = v.members["scope"]) {
                null -> Unit
                is JsonString ->
                    if (s.value == "top60k" || s.value == "all") {
                        scope = s.value
                    } else {
                        throw ProblemException(Problems.validationFailed("/scope", "invalid_value", "Must be top60k or all."))
                    }
                else -> throw ProblemException(Problems.validationFailed("/scope", "invalid_type", "Must be a string."))
            }
        }
        else -> throw ProblemException(Problems.validationFailed("", "invalid_type", "The body must be a JSON object."))
    }
    val ops = call.services.get<DictOps>()
    if (!ops.isOnline()) throw ProblemException(Problems.shellOffline())
    ops.start(scope)
    return ShellResponse.json(ops.state().toJson(), status = 202)
}

// ------------------------------------------------------------------------------------------- storage

private val STORAGE_FIELDS = setOf("prefetch_days", "budget_mb", "allow_metered", "clear_cache")

private fun getStorage(call: LocalCall): ShellResponse {
    call.query()
    return ShellResponse.json(call.services.get<StorageOps>().state().toJson())
}

private fun updateStorage(call: LocalCall): ShellResponse {
    call.query()
    val body = call.body(STORAGE_FIELDS)
    body.requireAny()
    val errors = FieldErrors()
    val days = body.int("prefetch_days", 0, 30, errors)
    val budget = body.int("budget_mb", 0, 100000, errors)
    val metered = body.bool("allow_metered", errors)
    val clear = body.bool("clear_cache", errors)
    errors.throwIfAny()
    val ops = call.services.get<StorageOps>()
    ops.update(days, budget, metered, clear == true)
    return ShellResponse.json(ops.state().toJson())
}

// ------------------------------------------------------------------------------------------- notifications

private val NOTIFY_FIELDS = setOf("nudges_enabled", "nudge_threshold", "evening_hour")

private fun getNotificationState(call: LocalCall): ShellResponse {
    call.query()
    return ShellResponse.json(call.services.get<NotifyOps>().state().toJson())
}

private fun updateNotificationPrefs(call: LocalCall): ShellResponse {
    call.query()
    val body = call.body(NOTIFY_FIELDS)
    body.requireAny()
    val errors = FieldErrors()
    val nudges = body.bool("nudges_enabled", errors)
    val threshold = body.int("nudge_threshold", 0, 100000, errors)
    val setEvening = "evening_hour" in body.members
    val evening = if (body.members["evening_hour"] == JsonNull) null else body.int("evening_hour", 0, 23, errors)
    errors.throwIfAny()
    val ops = call.services.get<NotifyOps>()
    ops.update(nudges, threshold, setEvening, evening)
    return ShellResponse.json(ops.state().toJson())
}

private suspend fun requestNotificationPermission(call: LocalCall): ShellResponse {
    call.query()
    return ShellResponse.json(call.services.get<NotifyOps>().requestPermission().toJson())
}

// ------------------------------------------------------------------------------------------- push

private fun getPushState(call: LocalCall): ShellResponse {
    call.query()
    return ShellResponse.json(call.services.get<PushOps>().state().toJson())
}

private fun updatePushState(call: LocalCall): ShellResponse {
    call.query()
    val body = call.body(setOf("enabled"))
    val errors = FieldErrors()
    val enabled = body.bool("enabled", errors)
    if (enabled == null && errors.isEmpty) errors.add("/enabled", "required", "Required.")
    errors.throwIfAny()
    return ShellResponse.json(call.services.get<PushOps>().update(enabled == true).toJson())
}

// ------------------------------------------------------------------------------------------- AnkiConnect

private val ANKI_FIELDS = setOf("enabled", "port", "api_key", "origins")
private val ORIGIN_RE = Regex("^\\S+$")
private val CONTROL_RE = Regex("[\\u0000-\\u001f]")

private fun getAnkiConnectState(call: LocalCall): ShellResponse {
    call.query()
    return ShellResponse.json(call.services.get<AnkiConnectOps>().state().toJson())
}

private suspend fun updateAnkiConnectState(call: LocalCall): ShellResponse {
    call.query()
    val body = call.body(ANKI_FIELDS)
    body.requireAny()
    val errors = FieldErrors()
    val enabled = body.bool("enabled", errors)
    val port = body.int("port", 1024, 65535, errors)
    var apiKey: String? = null
    body.members["api_key"]?.let { v ->
        val s = (v as? JsonString)?.value
        when {
            s == null -> errors.add("/api_key", "invalid_type", "Must be a string.")
            s.length > 200 -> errors.add("/api_key", "invalid_value", "At most 200 characters.")
            CONTROL_RE.containsMatchIn(s) -> errors.add("/api_key", "invalid_value", "No control characters.")
            else -> apiKey = s
        }
    }
    var origins: List<String>? = null
    body.members["origins"]?.let { v ->
        val items = (v as? JsonArray)?.items
        if (items == null) {
            errors.add("/origins", "invalid_type", "Must be an array of strings.")
            return@let
        }
        val out = ArrayList<String>()
        var ok = true
        items.forEachIndexed { i, item ->
            val s = (item as? JsonString)?.value
            when {
                s == null -> { errors.add("/origins/$i", "invalid_type", "Must be a string."); ok = false }
                s.isEmpty() || s.length > 200 || !ORIGIN_RE.matches(s) -> {
                    errors.add("/origins/$i", "invalid_value", "1 to 200 characters, no whitespace."); ok = false
                }
                else -> out += s
            }
        }
        if (ok && items.size > 20) { errors.add("/origins", "too_many", "At most 20 origins."); ok = false }
        if (ok && out.toSet().size != out.size) { errors.add("/origins", "not_unique", "Origins must be unique."); ok = false }
        if (ok) origins = out
    }
    errors.throwIfAny()
    return ShellResponse.json(call.services.get<AnkiConnectOps>().update(enabled, port, apiKey, origins).toJson())
}
