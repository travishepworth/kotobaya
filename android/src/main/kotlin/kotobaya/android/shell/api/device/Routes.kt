package kotobaya.android.shell.api.device

import kotobaya.android.net.ApiError
import kotobaya.android.pairing.PairResult
import kotobaya.android.pairing.PairingTarget
import kotobaya.android.pairing.PairingUri
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalRoute
import kotobaya.android.shell.api.Problem
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellHeaders
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.get
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString

/**
 * The device group's local routes (docs/ANDROID-SHELL.md section 5; SHELL.md 9): shell info, pairing, sync status and
 * sync now; plus the settings routes of `DeviceSettingsRoutes.kt` (dictionary install, storage, notifications, push, AnkiConnect).
 */
val routes: List<LocalRoute> = listOf(
    LocalRoute("getShellInfo", "GET", "/shell/v1/info") { call -> getShellInfo(call) },
    LocalRoute("pairShell", "POST", "/shell/v1/pair") { call -> pairShell(call) },
    LocalRoute("unpairShell", "POST", "/shell/v1/unpair") { call -> unpairShell(call) },
    LocalRoute("getSyncStatus", "GET", "/shell/v1/sync") { call -> getSyncStatus(call) },
    LocalRoute("syncNow", "POST", "/shell/v1/sync") { call -> syncNow(call) },
) + settingsRoutes + displayRoutes

/** The registered [DeviceOps] (tests), else the production one over the app's services. */
private fun ops(call: LocalCall): DeviceOps =
    runCatching { call.services.get<DeviceOps>() }.getOrElse { CoreDeviceOps.from(call.services) }

private fun getShellInfo(call: LocalCall): ShellResponse {
    call.query()
    return ShellResponse.json(ops(call).info().toJson())
}

private fun getSyncStatus(call: LocalCall): ShellResponse {
    call.query()
    return ShellResponse.json(ops(call).syncStatus().toJson())
}

private fun syncNow(call: LocalCall): ShellResponse {
    call.query()
    val ops = ops(call)
    if (!ops.syncStatus().online) throw ProblemException(Problems.shellOffline())
    ops.startSync()
    // `202` with the status at this moment; a cycle that has not shown yet is reported as running
    return ShellResponse.json(ops.syncStatus().toJson(forceSyncing = true), status = 202)
}

private fun unpairShell(call: LocalCall): ShellResponse {
    call.query()
    val force = when (val v = call.bodyValue()) {
        null -> false
        is JsonObject -> {
            v.members.keys.firstOrNull { it != "force" }?.let {
                throw ProblemException(Problems.validationFailed("/$it", "unknown_field", "Unknown property $it."))
            }
            when (val f = v.members["force"]) {
                null -> false
                is JsonBool -> f.value
                else -> throw ProblemException(Problems.validationFailed("/force", "invalid_type", "Must be true or false."))
            }
        }
        else -> throw ProblemException(Problems.validationFailed("", "invalid_type", "The body must be a JSON object."))
    }
    val ops = ops(call)
    if (!force && ops.syncStatus().view.status.pendingCount > 0) throw ProblemException(Problems.shellPendingOps())
    ops.unpair()
    return ShellResponse.json(ops.info().toJson())
}

private val PAIR_FIELDS = setOf("uri", "server_url", "code", "name")
private val SERVER_URL_RE = Regex("^https?://[^\\s/?#]+(/[^\\s?#]*)?$")
private val CODE_RE = Regex("^[0-9A-HJKMNP-TV-Z]{8}$")
private val CODE_INPUT_RE = Regex("^[0-9A-Za-z -]+$")

private suspend fun pairShell(call: LocalCall): ShellResponse {
    val ops = ops(call)
    call.bodyObject() // not JSON / not an object: 400 / 422 before anything else
    // 1. paired and not auth_required: nothing changes
    val before = ops.info()
    if (before.paired && !before.authRequired) {
        throw ProblemException(
            Problem(
                409, "already_exists", "Already exists", "This device is already paired. Unpair first.",
                extra = before.deviceId?.let { mapOf("existing_id" to JsonString(it)) } ?: emptyMap(),
            ),
        )
    }
    // 2. the body
    val (target, name) = validatePairBody(call.body(PAIR_FIELDS))
    // 3. offline
    if (!ops.syncStatus().online) throw ProblemException(Problems.shellOffline())
    // 4. the server's problem, 5. the new info
    when (val r = ops.pair(target, name)) {
        is PairResult.Failed -> throw ProblemException(problemOf(r.error))
        is PairResult.Paired -> Unit
    }
    ops.startSync()
    return ShellResponse.json(ops.info().toJson())
}

private fun validatePairBody(body: JsonObject): Pair<PairingTarget, String?> {
    val errors = FieldErrors()
    fun str(key: String, min: Int, max: Int): String? {
        val v = body.members[key] ?: return null
        val s = (v as? JsonString)?.value
        if (s == null) {
            errors.add("/$key", "invalid_type", "Must be a string.")
        } else if (s.length < min || s.length > max) {
            errors.add("/$key", "invalid_value", "Must be $min to $max characters.")
        } else {
            return s
        }
        return null
    }
    val uri = str("uri", 1, 2100)
    val serverUrl = str("server_url", 1, 2000)
    val code = str("code", 8, 16)
    val name = str("name", 1, 100)
    errors.throwIfAny()
    var target: PairingTarget? = null
    if (uri != null) {
        if (serverUrl != null || code != null) {
            errors.add("/uri", "conflicting_fields", "Send either uri, or server_url with code.")
        } else {
            target = PairingUri.parse(uri)
            if (target == null || !CODE_RE.matches(target.code)) {
                errors.add("/uri", "invalid_value", "Not a valid kotobaya://pair URI.")
                target = null
            }
        }
    } else if (serverUrl == null && code == null) {
        errors.add("/uri", "required", "Send either uri, or server_url with code.")
    } else {
        if (serverUrl == null) errors.add("/server_url", "required", "Required with code.")
        if (code == null) errors.add("/code", "required", "Required with server_url.")
        if (serverUrl != null && code != null) {
            val base = SERVER_URL_RE.takeIf { it.matches(serverUrl) }?.let { PairingUri.normalizeServerUrl(serverUrl) }
            val normalized = if (CODE_INPUT_RE.matches(code)) PairingUri.normalizeCode(code) else ""
            if (base == null) errors.add("/server_url", "invalid_value", "Must be an absolute http or https URL.")
            if (!CODE_RE.matches(normalized)) errors.add("/code", "invalid_value", "Must be 8 characters of 0-9 and A-Z without I, L, O, U.")
            if (base != null && CODE_RE.matches(normalized)) target = PairingTarget(base, normalized)
        }
    }
    errors.throwIfAny()
    return (target ?: throw ProblemException(Problems.internal())) to name
}

/** The server's pairing problems as the server writes them; transport failures as `shell_offline`. */
internal fun problemOf(e: ApiError): Problem = when (e) {
    ApiError.Offline, is ApiError.Unreachable -> Problems.shellOffline()
    ApiError.InvalidPairingCode ->
        Problem(401, "invalid_pairing_code", "Invalid pairing code", "The pairing code is unknown, expired or already used.")
    is ApiError.Validation ->
        Problems.validationFailed(listOfNotNull(e.pointer?.let { kotobaya.android.shell.api.FieldError(it, e.code ?: "invalid_value", "The server refused the pairing: ${e.code}.") }))
            .copy(detail = "The server refused the pairing.")
    is ApiError.RateLimited -> Problem(
        429, "rate_limited", "Too many requests", "Too many pairing attempts.",
        headers = e.retryAfterSeconds?.let { mapOf(ShellHeaders.RETRY_AFTER to it.toString()) } ?: emptyMap(),
    )
    is ApiError.Unauthorized -> Problem(401, e.code ?: "unauthenticated", "Authentication required", "The server refused the request.")
    is ApiError.Forbidden -> Problem(403, e.code ?: "forbidden", "Forbidden", "The server refused the request.")
    is ApiError.Server -> Problem(e.status, "internal", "Server error", "The server answered ${e.status}.")
    is ApiError.Other -> Problem(e.status, e.code ?: "internal", "Server error", "The server answered ${e.status}.")
    is ApiError.BadBody -> Problem(502, "internal", "Bad response", "The server's answer could not be read.")
}
