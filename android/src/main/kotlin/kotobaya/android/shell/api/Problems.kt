package kotobaya.android.shell.api

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.ClockGuardResult
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.mint.MintRefusal

/** One `errors[]` element of a problem (`openapi.yaml` `FieldError`). */
data class FieldError(val pointer: String, val code: String, val detail: String)

/**
 * An RFC 9457 problem, the same shape the server writes (`server/.../Problems.kt` `respondProblem`):
 * `type, title, status, code, detail?, instance, errors?, <extra>, request_id`. [instance] and
 * `request_id` are filled in by [toResponse].
 */
data class Problem(
    val status: Int,
    val code: String,
    val title: String,
    val detail: String? = null,
    val errors: List<FieldError> = emptyList(),
    /** Extension members (`required_scope`, `existing_id`). */
    val extra: Map<String, JsonValue> = emptyMap(),
    /** Extra response headers (`retry-after`). */
    val headers: Map<String, String> = emptyMap(),
) {
    /** The problem as a shell-generated response (`kotobaya-local: 1`, SHELL.md 3.3). */
    fun toResponse(instance: String, requestId: String): ShellResponse {
        val body = LinkedHashMap<String, JsonValue>()
        body["type"] = JsonString("urn:kotobaya:problem:$code")
        body["title"] = JsonString(title)
        body["status"] = JsonNumber(status.toDouble())
        body["code"] = JsonString(code)
        detail?.let { body["detail"] = JsonString(it) }
        body["instance"] = JsonString(instance)
        if (errors.isNotEmpty()) {
            body["errors"] = JsonArray(
                errors.map { e ->
                    JsonObject(linkedMapOf("pointer" to JsonString(e.pointer), "code" to JsonString(e.code), "detail" to JsonString(e.detail)))
                },
            )
        }
        body.putAll(extra)
        body["request_id"] = JsonString(requestId)
        val h = LinkedHashMap<String, String>()
        h[ShellHeaders.CONTENT_TYPE] = ShellHeaders.PROBLEM_JSON
        h.putAll(headers)
        h[ShellHeaders.LOCAL] = "1"
        h[ShellHeaders.REQUEST_ID] = requestId
        return ShellResponse(status, h, DefaultCanonicalJson.encode(JsonObject(body)))
    }
}

/** Thrown by handlers and [LocalCall] helpers; [LocalApi] turns it into the problem response. */
class ProblemException(val problem: Problem) : RuntimeException("${problem.code}: ${problem.detail ?: problem.title}")

/** Collects field errors like the server's `Errors` and throws them as one `422 validation_failed`. */
class FieldErrors {
    private val list = ArrayList<FieldError>()

    val isEmpty: Boolean get() = list.isEmpty()

    fun add(pointer: String, code: String, detail: String) {
        list.add(FieldError(pointer, code, detail))
    }

    fun throwIfAny() {
        if (list.isNotEmpty()) throw ProblemException(Problems.validationFailed(list.toList()))
    }
}

/** Factories for every problem the kernel produces (codes: `openapi.yaml` section 5, SHELL.md 5). */
object Problems {
    const val RETRY_AFTER_REBUILDING: String = "2"

    fun notFound(detail: String? = null) = Problem(404, "not_found", "Not found", detail)

    fun gone(detail: String? = null) = Problem(410, "gone", "Gone", detail)

    fun invalidRequest(detail: String? = null) = Problem(400, "invalid_request", "Invalid request", detail)

    fun unauthenticated(detail: String? = "This device is not paired.") =
        Problem(401, "unauthenticated", "Authentication required", detail)

    /** P-4: [scope] is `admin` / `import`, or null for operations hidden because something native replaces them. */
    fun insufficientScope(scope: String?) = Problem(
        403, "insufficient_scope", "Insufficient scope",
        if (scope != null) "The credential lacks the $scope scope." else "Not available in the app.",
        extra = if (scope != null) mapOf("required_scope" to JsonString(scope)) else emptyMap(),
    )

    fun validationFailed(errors: List<FieldError>) =
        Problem(422, "validation_failed", "Validation failed", errors.firstOrNull()?.detail, errors)

    fun validationFailed(pointer: String, code: String, detail: String) = validationFailed(listOf(FieldError(pointer, code, detail)))

    fun conflict(code: String, detail: String? = null) = Problem(409, code, "Conflict", detail)

    fun payloadTooLarge(detail: String? = null) = Problem(413, "payload_too_large", "Payload too large", detail)

    fun internal() = Problem(500, "internal", "Internal error", "Unexpected error. The request id identifies it in the device log.")

    fun shellOffline() = Problem(503, "shell_offline", "Offline", "The server cannot be reached.")

    fun shellDictMissing(detail: String? = null) = Problem(503, "shell_dict_missing", "Dictionary data not on this device", detail)

    fun shellRebuilding() = Problem(
        503, "shell_rebuilding", "Rebuilding local data", "Local data is being rebuilt.",
        headers = mapOf(ShellHeaders.RETRY_AFTER to RETRY_AFTER_REBUILDING),
    )

    fun shellUnsupported(detail: String) = Problem(415, "shell_unsupported", "Not supported in the app", detail)

    fun shellPendingOps() = Problem(409, "shell_pending_ops", "Unsynced changes", "There are changes that have not been synced.")

    /**
     * The L-3 `MintRefusal` table. [invalidPointer] maps a [MintRefusal.Invalid] op pointer to the
     * request property it came from (`""` when there is none).
     */
    fun fromMintRefusal(r: MintRefusal, invalidPointer: (MintRefusal.Invalid) -> String = { "" }): Problem = when (r) {
        MintRefusal.NotPaired -> unauthenticated()
        MintRefusal.RebuildPending -> shellRebuilding()
        is MintRefusal.ClockGuard -> when (r.guard) {
            ClockGuardResult.G1_BEFORE_FLOOR -> validationFailed("/reviewed_at", "before_floor", "The device clock is before 2020.")
            else -> validationFailed("/reviewed_at", "before_card", "The device clock is far behind a time it has already seen.")
        }
        is MintRefusal.ClockFarFromServer ->
            if ((r.skewMs ?: 0L) < 0) {
                validationFailed("/reviewed_at", "in_future", "The device clock is more than a day ahead of the server's.")
            } else {
                validationFailed("/reviewed_at", "before_card", "The device clock is more than a day behind the server's.")
            }
        is MintRefusal.TooLarge -> payloadTooLarge("The change is larger than the server accepts.")
        is MintRefusal.Invalid -> validationFailed(invalidPointer(r), "invalid_value", "The value cannot be stored.")
        is MintRefusal.ServerOnly -> internal()
    }
}
