package kotobaya.clienterrors

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotobaya.auth.authenticated
import kotobaya.server.ApiException
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.formatInstant
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.server.withCallMdc
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.ArrayDeque
import java.util.UUID

/**
 * `POST /client-errors` (`reportClientErrors`, C-23 / ADR-024): client error reports go to the structured server log
 * and nowhere else (no table, op, blob or job). Limits, check order and log format are in the operation description.
 */
class ClientErrorsModule(private val clock: () -> Instant = Instant::now) {
    private val limiter = ClientErrorLimiter(clock)
    private val log = LoggerFactory.getLogger("kotobaya.client_error")
    private val refusalLog = LoggerFactory.getLogger("kotobaya.client_error_refused")

    val handlers: Map<String, OperationHandler> = mapOf("reportClientErrors" to { report(this) })

    private suspend fun report(call: ApplicationCall) {
        val principal = (call.authenticated ?: throw Problems.unauthenticated("No credential.")).principal
        val id = principal.tokenId
        // 2. request rate limit, before the body is read.
        limiter.admitRequest(id)?.let { wait -> refuse(call, id, wait, items = 0) }
        // 3. body size.
        tooBig(call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > MAX_BODY_BYTES } == true)
        if (!call.request.contentType().match(ContentType.Application.Json)) {
            throw ApiException(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type", "Unsupported media type", "Send application/json.")
        }
        val bytes = call.receiveChannel().readRemaining(MAX_BODY_BYTES + 1).readByteArray()
        tooBig(bytes.size > MAX_BODY_BYTES)
        // 4. JSON.
        val body: JsonNode = runCatching { json.readTree(bytes) }.getOrNull()?.takeIf { !it.isMissingNode && !it.isNull }
            ?: throw Problems.invalidRequest("The body is not valid JSON.")
        // 5. item count, before any schema validation (21 items is 413, never 422).
        val rawItems = body.takeIf { it.isObject }?.get("items")
        if (rawItems != null && rawItems.isArray && rawItems.size() > MAX_ITEMS) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "payload_too_large", "Payload too large", "At most $MAX_ITEMS items per request; split the batch.")
        }
        // 6. fields.
        val errors = ArrayList<FieldError>()
        val parsed = ClientErrorValidator.validate(body, errors)
        if (errors.isNotEmpty()) {
            val first = errors.first()
            withCallMdc(call) {
                log.warn("client error report rejected: {} {}", first.pointer, first.code)
            }
            throw ApiException(
                HttpStatusCode.UnprocessableEntity, "validation_failed", "Validation failed", first.detail,
                mapOf("errors" to errors.map { mapOf("pointer" to it.pointer, "code" to it.code, "detail" to it.detail) }),
            )
        }
        // 7. item rate limit (all or nothing).
        limiter.admitItems(id, parsed.items.size)?.let { wait -> refuse(call, id, wait, items = parsed.items.size) }
        // 8. log.
        withCallMdc(call) {
            if (parsed.dropped > 0) {
                val first = parsed.items.first()
                log.warn(
                    "client dropped error reports", kv("dropped", parsed.dropped), kv("client", first.client),
                    kv("app_version", first.appVersion),
                )
            }
            for (item in parsed.items) logItem(item)
        }
        call.respondJson(mapOf("accepted" to parsed.items.size), HttpStatusCode.Accepted)
    }

    private fun tooBig(over: Boolean) {
        if (over) throw ApiException(HttpStatusCode.PayloadTooLarge, "payload_too_large", "Payload too large", "The body is larger than $MAX_BODY_BYTES bytes; split the batch.")
    }

    private fun logItem(i: ClientErrorItem) {
        val args = ArrayList<Any?>()
        args += kv("client", i.client)
        args += kv("app_version", i.appVersion)
        args += kv("kind", i.kind)
        args += kv("occurred_at", formatInstant(i.occurredAt))
        i.route?.let { args += kv("route", escapeLogValue(cutQuery(it))) }
        i.requestId?.let { args += kv("client_request_id", escapeLogValue(it)) }
        i.httpMethod?.let { args += kv("http_method", it) }
        i.httpPath?.let { args += kv("http_path", escapeLogValue(cutQuery(it))) }
        i.httpStatus?.let { args += kv("http_status", it) }
        args += kv("count", i.count)
        i.stack?.let { args += kv("stack", escapeLogValue(it)) }
        // The log message is the item's (escaped) message; the "{}" pattern keeps braces in it literal.
        val message = escapeLogValue(i.message)
        if (i.level == "error") log.error("{}", message, *args.toTypedArray()) else log.warn("{}", message, *args.toTypedArray())
    }

    private fun refuse(call: ApplicationCall, id: UUID, waitSeconds: Long, items: Int): Nothing {
        val name = call.authenticated?.principal?.name
        limiter.noteRefusal(id, items)?.let { (requests, refusedItems) ->
            withCallMdc(call) {
                refusalLog.warn(
                    "client error reports refused: rate limited", kv("token_name", name),
                    kv("refused_requests", requests), kv("refused_items", refusedItems),
                )
            }
        }
        call.response.header(HttpHeaders.RetryAfter, waitSeconds.toString())
        throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many error reports", "Try again in $waitSeconds seconds.")
    }

    companion object {
        const val MAX_BODY_BYTES = 1_048_576L
        const val MAX_ITEMS = 20
        const val MAX_REQUESTS_PER_MINUTE = 12
        const val MAX_ITEMS_PER_MINUTE = 120
    }
}

/** Escapes every C0/C1 control character, U+2028 and U+2029 so one item is one log line. */
internal fun escapeLogValue(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        when {
            c == '\r' -> sb.append("\\r")
            c == '\n' -> sb.append("\\n")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 || c.code in 0x80..0x9F || c == ' ' || c == ' ' -> sb.append("\\u").append("%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private fun cutQuery(s: String): String = s.substringBefore('?').substringBefore('#')

/** In-memory rolling 60 s windows per credential: 12 requests and 120 accepted items. */
internal class ClientErrorLimiter(private val clock: () -> Instant) {
    private class State {
        val requests = ArrayDeque<Instant>()
        val items = ArrayDeque<Pair<Instant, Int>>()
        var itemSum = 0
        var lastRefusalLog: Instant? = null
        var refusedRequests = 0
        var refusedItems = 0
    }

    private val states = HashMap<UUID, State>()
    private val window = Duration.ofSeconds(60)

    private fun state(id: UUID, now: Instant): State {
        if (states.size > 1_000) states.values.removeIf { it.requests.isEmpty() && it.items.isEmpty() && it.refusedRequests == 0 }
        val s = states.getOrPut(id) { State() }
        while (s.requests.isNotEmpty() && !s.requests.first.isAfter(now.minus(window))) s.requests.removeFirst()
        while (s.items.isNotEmpty() && !s.items.first.first.isAfter(now.minus(window))) s.itemSum -= s.items.removeFirst().second
        return s
    }

    private fun secondsUntil(at: Instant, now: Instant): Long = maxOf(1L, (Duration.between(now, at).toMillis() + 999) / 1000)

    /** Counts this request; returns the seconds to wait when it is over the limit (not counted), else null. */
    @Synchronized
    fun admitRequest(id: UUID): Long? {
        val now = clock()
        val s = state(id, now)
        if (s.requests.size >= ClientErrorsModule.MAX_REQUESTS_PER_MINUTE) {
            return secondsUntil(s.requests.first.plus(window), now)
        }
        s.requests.addLast(now)
        return null
    }

    /** Records [n] accepted items; returns the seconds to wait when they would exceed the limit (nothing recorded). */
    @Synchronized
    fun admitItems(id: UUID, n: Int): Long? {
        val now = clock()
        val s = state(id, now)
        if (s.itemSum + n > ClientErrorsModule.MAX_ITEMS_PER_MINUTE) {
            var sum = s.itemSum
            for ((t, c) in s.items) {
                sum -= c
                if (sum + n <= ClientErrorsModule.MAX_ITEMS_PER_MINUTE) return secondsUntil(t.plus(window), now)
            }
            return 1
        }
        s.items.addLast(now to n)
        s.itemSum += n
        return null
    }

    /** Accumulates a refusal; returns (requests, items) to log when no refusal line was logged in the last 60 s. */
    @Synchronized
    fun noteRefusal(id: UUID, items: Int): Pair<Int, Int>? {
        val now = clock()
        val s = states.getOrPut(id) { State() }
        s.refusedRequests++
        s.refusedItems += items
        val last = s.lastRefusalLog
        if (last != null && last.isAfter(now.minus(window))) return null
        val out = s.refusedRequests to s.refusedItems
        s.refusedRequests = 0
        s.refusedItems = 0
        s.lastRefusalLog = now
        return out
    }
}

internal class FieldError(val pointer: String, val code: String, val detail: String)

internal class ClientErrorItem(
    val client: String, val appVersion: String, val occurredAt: Instant, val level: String, val kind: String,
    val message: String, val stack: String?, val route: String?, val requestId: String?, val httpMethod: String?,
    val httpPath: String?, val httpStatus: Int?, val count: Long,
)

internal class ClientErrorBatch(val items: List<ClientErrorItem>, val dropped: Long)

/** Validates a `ClientErrorReport` body against the schema, collecting every problem. */
internal object ClientErrorValidator {
    private val MAX_INSTANT = Instant.parse("9999-12-31T23:59:59.999Z")
    private val VERSION = Regex("^[\\u0020-\\u007E]+$")
    private val REQUEST_ID = Regex("^[\\u0021-\\u007E]+$")
    private val CLIENTS = setOf("web", "android")
    private val LEVELS = setOf("error", "warn")
    private val KINDS = setOf("exception", "api_failure", "unhandled_rejection", "crash")
    private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    private val ITEM_FIELDS = setOf(
        "client", "app_version", "occurred_at", "level", "kind", "message", "stack", "route", "request_id",
        "http_method", "http_path", "http_status", "count",
    )

    fun validate(body: JsonNode, errors: MutableList<FieldError>): ClientErrorBatch {
        if (!body.isObject) {
            errors += FieldError("", "invalid_type", "The body must be a JSON object.")
            return ClientErrorBatch(emptyList(), 0)
        }
        body.fieldNames().forEach { if (it != "items" && it != "dropped") errors += FieldError("/$it", "unknown_field", "Unknown property $it.") }
        var dropped = 0L
        body.get("dropped")?.let { d ->
            integer(d, "/dropped", 0, Int.MAX_VALUE.toLong(), errors)?.let { dropped = it }
        }
        val items = ArrayList<ClientErrorItem>()
        val raw = body.get("items")
        when {
            raw == null -> errors += FieldError("/items", "required", "items is required.")
            !raw.isArray -> errors += FieldError("/items", "invalid_type", "items must be an array.")
            raw.size() == 0 -> errors += FieldError("/items", "out_of_range", "items must have 1 to 20 elements.")
            else -> raw.forEachIndexed { i, n -> item(n, "/items/$i", errors)?.let { items += it } }
        }
        return ClientErrorBatch(items, dropped)
    }

    private fun item(n: JsonNode, p: String, errors: MutableList<FieldError>): ClientErrorItem? {
        if (!n.isObject) {
            errors += FieldError(p, "invalid_type", "An item must be an object.")
            return null
        }
        val before = errors.size
        n.fieldNames().forEach { if (it !in ITEM_FIELDS) errors += FieldError("$p/$it", "unknown_field", "Unknown property $it.") }
        val client = enum(n, p, "client", CLIENTS, true, errors)
        val appVersion = string(n, p, "app_version", 1, 64, true, errors)?.also {
            if (!VERSION.matches(it)) errors += FieldError("$p/app_version", "invalid_value", "app_version must be printable ASCII.")
        }
        val occurred = string(n, p, "occurred_at", 0, Int.MAX_VALUE, true, errors)?.let { instant(it, "$p/occurred_at", errors) }
        val level = enum(n, p, "level", LEVELS, true, errors)
        val kind = enum(n, p, "kind", KINDS, true, errors)
        val message = string(n, p, "message", 1, 2048, true, errors)
        val stack = string(n, p, "stack", 0, 16384, false, errors)
        val route = string(n, p, "route", 0, 512, false, errors)
        val requestId = string(n, p, "request_id", 1, 128, false, errors)?.also {
            if (!REQUEST_ID.matches(it)) errors += FieldError("$p/request_id", "invalid_value", "request_id must be printable ASCII without spaces.")
        }
        val method = enum(n, p, "http_method", METHODS, false, errors)
        val path = string(n, p, "http_path", 0, 512, false, errors)
        val status = n.get("http_status")?.let { integer(it, "$p/http_status", 100, 599, errors) }
        val count = n.get("count")?.let { integer(it, "$p/count", 1, Int.MAX_VALUE.toLong(), errors) } ?: 1L
        if (errors.size > before || client == null || appVersion == null || occurred == null || level == null || kind == null || message == null) return null
        return ClientErrorItem(client, appVersion, occurred, level, kind, message, stack, route, requestId, method, path, status?.toInt(), count)
    }

    private fun enum(n: JsonNode, p: String, name: String, allowed: Set<String>, required: Boolean, errors: MutableList<FieldError>): String? {
        val v = n.get(name)
        if (v == null) {
            if (required) errors += FieldError("$p/$name", "required", "$name is required.")
            return null
        }
        if (!v.isTextual) {
            errors += FieldError("$p/$name", "invalid_type", "$name must be a string.")
            return null
        }
        if (v.asText() !in allowed) {
            errors += FieldError("$p/$name", "invalid_value", "$name must be one of ${allowed.joinToString()}.")
            return null
        }
        return v.asText()
    }

    private fun string(n: JsonNode, p: String, name: String, min: Int, max: Int, required: Boolean, errors: MutableList<FieldError>): String? {
        val v = n.get(name)
        if (v == null) {
            if (required) errors += FieldError("$p/$name", "required", "$name is required.")
            return null
        }
        if (!v.isTextual) {
            errors += FieldError("$p/$name", "invalid_type", "$name must be a string.")
            return null
        }
        val s = v.asText()
        val len = s.codePointCount(0, s.length)
        if (len < min) {
            errors += FieldError("$p/$name", "out_of_range", "$name must have at least $min characters.")
            return null
        }
        if (len > max) {
            errors += FieldError("$p/$name", "too_long", "$name must have at most $max characters.")
            return null
        }
        return s
    }

    private fun integer(v: JsonNode, pointer: String, min: Long, max: Long, errors: MutableList<FieldError>): Long? {
        if (!v.isIntegralNumber) {
            errors += FieldError(pointer, "invalid_type", "Must be an integer.")
            return null
        }
        if (!v.canConvertToLong() || v.asLong() < min || v.asLong() > max) {
            errors += FieldError(pointer, "out_of_range", "Must be between $min and $max.")
            return null
        }
        return v.asLong()
    }

    private fun instant(s: String, pointer: String, errors: MutableList<FieldError>): Instant? {
        val parsed = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
        if (parsed == null) {
            errors += FieldError(pointer, "invalid_value", "Must be an RFC 3339 instant.")
            return null
        }
        if (parsed.isAfter(MAX_INSTANT)) {
            errors += FieldError(pointer, "out_of_range", "Must not be after 9999-12-31T23:59:59.999Z.")
            return null
        }
        return parsed
    }
}
