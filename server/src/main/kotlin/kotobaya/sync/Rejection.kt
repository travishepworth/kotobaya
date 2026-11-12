package kotobaya.sync

import io.ktor.http.HttpStatusCode
import kotobaya.server.ApiException

/** The rejection codes of SYNC.md 5.3 (an open set; these are the ones this server produces). */
object RejectCode {
    const val SCHEMA_INVALID = "schema_invalid"
    const val OP_TOO_LARGE = "op_too_large"
    const val UNSUPPORTED_OP = "unsupported_op"
    const val DEVICE_MISMATCH = "device_mismatch"
    const val INSUFFICIENT_SCOPE = "insufficient_scope"
    const val SERVER_ONLY = "server_only"
    const val UNKNOWN_REFERENCE = "unknown_reference"
    const val CLOCK_AHEAD = "clock_ahead"
    const val CLOCK_BEHIND = "clock_behind"
    const val INVALID_VALUE = "invalid_value"
    const val INVALID_TARGET = "invalid_target"
    const val IMMUTABLE = "immutable"
}

/** A per-op rejection: `PushResult.error` (`code`, English `message`). */
data class Rejection(
    val code: String,
    val message: String,
    /** For `invalid_target`: the target's kind (a deck's `type`), so REST can pick the right 409. Not sent to clients. */
    val targetKind: String? = null,
)

/**
 * An op the server was asked to mint ([OpTx.emit]) fails its own schema, nests deeper than
 * `MAX_OP_DEPTH`, or exceeds `max_op_bytes` (SYNC.md 2.1, 4). Nothing was minted; the transaction
 * should be rolled back by letting this propagate. REST handlers answer it with [toApiException]
 * (FOLLOWUPS C-5 default: `422 validation_failed`, error `invalid_value`).
 *
 * [pointer] is a JSON Pointer into the op **payload** (`/tags/0`), `""` for the whole op.
 */
class OpInputInvalid(val pointer: String, val detail: String, val code: String = "invalid_value") :
    IllegalArgumentException("$code at '$pointer': $detail") {
    /** 422 `validation_failed` with one error; [mapPointer] translates a payload pointer into a request-body pointer. */
    fun toApiException(mapPointer: (String) -> String = { it }): ApiException = ApiException(
        HttpStatusCode.UnprocessableEntity, "validation_failed", "Validation failed", detail,
        mapOf("errors" to listOf(mapOf("pointer" to mapPointer(pointer), "code" to code, "detail" to detail))),
    )
}

/**
 * An op the server was asked to mint breaks a rule of SYNC.md 5.3 / OPS.md that a pushed op would be
 * rejected for (`unknown_reference`, `invalid_value`, `invalid_target`, `immutable`). REST handlers
 * normally check these first and answer with their own status; [toApiException] is the fallback.
 */
class OpRefused(val rejection: Rejection) : IllegalStateException("${rejection.code}: ${rejection.message}") {
    fun toApiException(): ApiException = when (rejection.code) {
        RejectCode.UNKNOWN_REFERENCE -> ApiException(HttpStatusCode.NotFound, "not_found", "Not found", rejection.message)
        RejectCode.IMMUTABLE -> ApiException(HttpStatusCode.Conflict, "builtin_immutable", "Conflict", rejection.message)
        // openapi: items of a smart deck are `not_applicable`; media/list decks are `deck_read_only`.
        RejectCode.INVALID_TARGET ->
            if (rejection.targetKind == "smart") {
                ApiException(HttpStatusCode.Conflict, "not_applicable", "Conflict", rejection.message)
            } else {
                ApiException(HttpStatusCode.Conflict, "deck_read_only", "Conflict", rejection.message)
            }
        else -> OpInputInvalid("", rejection.message).toApiException()
    }
}
