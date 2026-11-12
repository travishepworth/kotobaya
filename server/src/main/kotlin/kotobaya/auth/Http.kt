package kotobaya.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import kotobaya.server.ApiException
import kotobaya.server.Problems
import kotobaya.server.json
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal fun validationFailed(pointer: String, code: String, detail: String) = ApiException(
    HttpStatusCode.UnprocessableEntity, "validation_failed", "Validation failed", detail,
    mapOf("errors" to listOf(mapOf("pointer" to pointer, "code" to code, "detail" to detail))),
)

internal fun conflict(code: String, detail: String) = ApiException(HttpStatusCode.Conflict, code, "Conflict", detail)

/** Reads a JSON body: 415 unless `application/json`, 400 unless valid JSON, 422 unless an object. */
internal suspend fun ApplicationCall.jsonObject(): ObjectNode {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw ApiException(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type", "Unsupported media type", "Send application/json.")
    }
    val body: JsonNode = runCatching { json.readTree(receiveText()) }.getOrNull() ?: throw Problems.invalidRequest("The body is not valid JSON.")
    if (body !is ObjectNode) throw validationFailed("", "invalid_type", "The body must be a JSON object.")
    return body
}

internal fun ObjectNode.rejectUnknown(allowed: Set<String>) {
    fieldNames().asSequence().firstOrNull { it !in allowed }?.let { throw validationFailed("/$it", "unknown_field", "Unknown property $it.") }
}

/** Unknown query parameters are `422` (openapi §1). */
internal fun ApplicationCall.rejectUnknownQuery(allowed: Set<String>) {
    request.queryParameters.names().firstOrNull { it !in allowed }?.let {
        throw validationFailed("/$it", "unknown_field", "Unknown query parameter $it.")
    }
}

private val UUID_TEXT = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** Canonical lowercase UUID text only (uppercase is rejected, openapi §1). */
internal fun parseUuid(s: String?): UUID? = s?.takeIf { UUID_TEXT.matches(it) }?.let(UUID::fromString)

/** A required string of 1..[max] characters. */
internal fun ObjectNode.requiredName(field: String, max: Int = 100): String {
    val n = get(field) ?: throw validationFailed("/$field", "required", "$field is required.")
    return checkName(field, n, max)
}

internal fun checkName(field: String, n: JsonNode, max: Int = 100): String {
    if (!n.isTextual) throw validationFailed("/$field", "invalid_type", "$field must be a string.")
    val s = n.asText()
    if (s.isEmpty() || s.codePointCount(0, s.length) > max) throw validationFailed("/$field", "out_of_range", "$field must be 1 to $max characters.")
    return s
}

/** `push_topic`: null, or an absolute http(s) URL of at most 2000 characters. */
internal fun checkPushTopic(n: JsonNode): String? {
    if (n.isNull) return null
    if (!n.isTextual) throw validationFailed("/push_topic", "invalid_type", "push_topic must be a string or null.")
    val s = n.asText()
    if (s.length > 2000) throw validationFailed("/push_topic", "out_of_range", "push_topic must be at most 2000 characters.")
    val uri = runCatching { java.net.URI(s) }.getOrNull()
    if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
        throw validationFailed("/push_topic", "invalid_value", "push_topic must be an http or https URL.")
    }
    return s
}

internal fun utc(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)
