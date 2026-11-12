package kotobaya.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

val json: ObjectMapper = jacksonObjectMapper()

val ProblemJson: ContentType = ContentType("application", "problem+json")
val JsonUtf8: ContentType = ContentType.parse("application/json; charset=utf-8")

/** RFC 3339 UTC with milliseconds and `Z`, as the API contract requires. */
fun formatInstant(i: Instant): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(i)

/** Thrown by handlers (and the auth hooks) to produce a problem+json response. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    val title: String,
    val detail: String? = null,
    val extra: Map<String, Any?> = emptyMap(),
) : RuntimeException("$code: ${detail ?: title}")

/** Factories for the codes this module produces itself; handlers may build their own [ApiException]. */
object Problems {
    fun notFound(detail: String? = null) = ApiException(HttpStatusCode.NotFound, "not_found", "Not found", detail)

    fun unavailable(detail: String? = null) =
        ApiException(HttpStatusCode.ServiceUnavailable, "unavailable", "Service unavailable", detail)

    /** Not in the API error table; used only by the spec-derived stubs. */
    fun notImplemented(operationId: String) = ApiException(
        HttpStatusCode.NotImplemented, "not_implemented", "Not implemented",
        "Operation $operationId is not implemented yet.",
    )

    fun invalidRequest(detail: String? = null) =
        ApiException(HttpStatusCode.BadRequest, "invalid_request", "Invalid request", detail)

    fun unauthenticated(detail: String? = null) =
        ApiException(HttpStatusCode.Unauthorized, "unauthenticated", "Authentication required", detail)

    fun insufficientScope(scope: String) = ApiException(
        HttpStatusCode.Forbidden, "insufficient_scope", "Insufficient scope",
        "The credential lacks the $scope scope.", mapOf("required_scope" to scope),
    )

    fun internal() = ApiException(
        HttpStatusCode.InternalServerError, "internal", "Internal error",
        "Unexpected error. The request id identifies it in the server log.",
    )
}

suspend fun ApplicationCall.respondProblem(e: ApiException) {
    val body = linkedMapOf<String, Any?>(
        "type" to "urn:kotobaya:problem:${e.code}",
        "title" to e.title,
        "status" to e.status.value,
        "code" to e.code,
    )
    e.detail?.let { body["detail"] = it }
    body["instance"] = request.local.uri.substringBefore('?')
    body.putAll(e.extra)
    body["request_id"] = requestId
    respondText(json.writeValueAsString(body), ProblemJson, e.status)
}

suspend fun ApplicationCall.respondJson(value: Any, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(json.writeValueAsString(value), JsonUtf8, status)
