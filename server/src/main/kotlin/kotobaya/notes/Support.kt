package kotobaya.notes

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotobaya.auth.authenticated
import kotobaya.contracts.sync.JsonObject
import kotobaya.server.ApiException
import kotobaya.server.Problems
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import kotobaya.sync.OpInputInvalid
import kotobaya.sync.OpRefused
import kotobaya.sync.OpTx
import kotobaya.sync.SyncJson
import java.util.UUID

internal val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
internal val KEY_RE = Regex("^[a-z][a-z0-9_]*$")

/** Collects the `errors[]` of a `422 validation_failed` (openapi `FieldError`). */
internal class Errors {
    private val list = mutableListOf<Map<String, Any?>>()

    fun add(pointer: String, code: String, detail: String) {
        list += mapOf("pointer" to pointer, "code" to code, "detail" to detail)
    }

    val any: Boolean get() = list.isNotEmpty()

    fun throwIfAny() {
        if (list.isEmpty()) return
        throw ApiException(
            HttpStatusCode.UnprocessableEntity, "validation_failed", "Validation failed",
            list.joinToString("; ") { "${it["pointer"]}: ${it["detail"]}" },
            mapOf("errors" to list.toList()),
        )
    }

    /** Properties outside [allowed]; names in [immutable] get the `immutable` code. */
    fun unknownKeys(o: ObjectNode, allowed: Set<String>, immutable: Set<String> = emptySet()) {
        for (k in o.fieldNames().asSequence().toList()) {
            when (k) {
                in allowed -> Unit
                in immutable -> add(ptr(k), "immutable", "$k cannot be changed.")
                else -> add(ptr(k), "unknown_field", "Unknown property $k.")
            }
        }
    }

    /** A string property; null when absent (an error when [required]) or invalid (error recorded). */
    fun string(o: JsonNode, key: String, min: Int, max: Int, required: Boolean = false): String? {
        val v = o.get(key)
        if (v == null) {
            if (required) add(ptr(key), "required", "$key is required.")
            return null
        }
        return stringValue(v, ptr(key), min, max)
    }

    fun stringValue(v: JsonNode, at: String, min: Int, max: Int): String? {
        if (!v.isTextual) {
            add(at, "invalid_type", "Must be a string.")
            return null
        }
        val text = v.asText()
        val n = text.codePointCount(0, text.length)
        if (n < min) {
            add(at, "invalid_value", if (min == 1) "Must not be empty." else "Must be at least $min characters.")
            return null
        }
        if (n > max) {
            add(at, "too_long", "At most $max characters.")
            return null
        }
        return text
    }

    fun uuid(o: JsonNode, key: String, required: Boolean = false): String? {
        val v = o.get(key)
        if (v == null) {
            if (required) add(ptr(key), "required", "$key is required.")
            return null
        }
        return uuidValue(v, ptr(key))
    }

    fun uuidValue(v: JsonNode, at: String): String? {
        if (!v.isTextual || !UUID_RE.matches(v.asText())) {
            add(at, "invalid_value", "Must be a lowercase UUID.")
            return null
        }
        return v.asText()
    }

    fun enum(o: JsonNode, key: String, values: Set<String>, required: Boolean = false): String? {
        val v = o.get(key)
        if (v == null) {
            if (required) add(ptr(key), "required", "$key is required.")
            return null
        }
        if (!v.isTextual || v.asText() !in values) {
            add(ptr(key), "invalid_value", "Must be one of ${values.joinToString(", ")}.")
            return null
        }
        return v.asText()
    }

    /** An integer in `[min, max]`. */
    fun long(v: JsonNode, at: String, min: Long, max: Long = Long.MAX_VALUE): Long? {
        if (!v.isIntegralNumber || !v.canConvertToLong()) {
            add(at, "invalid_type", "Must be an integer.")
            return null
        }
        val n = v.asLong()
        if (n < min || n > max) {
            add(at, "out_of_range", "Must be at least $min.")
            return null
        }
        return n
    }
}

internal fun escapePointer(s: String): String = s.replace("~", "~0").replace("/", "~1")

internal fun ptr(vararg parts: Any): String = parts.joinToString("") { "/" + escapePointer(it.toString()) }

/** The JSON object of the request; 415 on a foreign content type, 400 on text that is not a JSON object. */
internal suspend fun ApplicationCall.jsonBody(): ObjectNode {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw ApiException(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type", "Unsupported media type", "Send application/json.")
    }
    val node = try {
        json.readTree(receiveText())
    } catch (e: JsonProcessingException) {
        throw Problems.invalidRequest("The body is not valid JSON.")
    }
    return node as? ObjectNode ?: throw Problems.invalidRequest("The body must be a JSON object.")
}

internal fun ApplicationCall.callerTokenId(): UUID? = authenticated?.principal?.tokenId

/** `include_deleted` query flag; anything but `true`/`false` is a 422. */
internal fun ApplicationCall.includeDeleted(): Boolean = when (request.queryParameters["include_deleted"]) {
    null, "false" -> false
    "true" -> true
    else -> {
        Errors().apply { add("/include_deleted", "invalid_value", "Must be true or false.") }.throwIfAny()
        false
    }
}

/** A path id that is not a canonical UUID cannot name a row: 404. */
internal fun ApplicationCall.pathId(name: String): String =
    parameters[name]?.takeIf { UUID_RE.matches(it) } ?: throw Problems.notFound("No such $name.")

internal fun gone(what: String, deletedAt: String?) =
    ApiException(HttpStatusCode.Gone, "gone", "Deleted", "The $what was deleted at $deletedAt.")

/** What a handler decided inside its transaction; written after commit. */
internal class Reply(
    val status: HttpStatusCode,
    val body: JsonNode?,
    /** `Kotobaya-Seq`: absent when nothing was emitted (an idempotent retry). */
    val seq: Long? = null,
    val contentLocation: String? = null,
)

internal suspend fun ApplicationCall.send(r: Reply) {
    r.seq?.let { response.header("Kotobaya-Seq", it.toString()) }
    r.contentLocation?.let { response.header(HttpHeaders.ContentLocation, it) }
    if (r.body == null) respond(r.status) else respondJson(r.body, r.status)
}

/** One op transaction for a REST call; the op layer's refusals become their API errors, and roll everything back. */
internal suspend fun <T> OpApply.rest(tokenId: UUID?, mapPointer: (String) -> String = { it }, block: (OpTx) -> T): T =
    try {
        transaction(tokenId, block)
    } catch (e: OpInputInvalid) {
        throw e.toApiException(mapPointer)
    } catch (e: OpRefused) {
        throw e.toApiException()
    }

/** A request-shaped Jackson object as an op payload. */
internal fun payload(n: ObjectNode): JsonObject = SyncJson.fromJackson(n) as? JsonObject ?: error("an op payload is an object")

/** Code-point order, as the tag set of an op is sorted (common.schema.json `tags`). */
internal val CodePointOrder = Comparator<String> { a, b ->
    var i = 0
    var j = 0
    var r = 0
    while (r == 0 && i < a.length && j < b.length) {
        val x = a.codePointAt(i)
        val y = b.codePointAt(j)
        r = x.compareTo(y)
        i += Character.charCount(x)
        j += Character.charCount(y)
    }
    if (r != 0) r else (a.length - i).compareTo(b.length - j)
}
