package kotobaya.android.shell.api.identity

import java.time.OffsetDateTime
import java.util.ArrayDeque
import kotobaya.android.report.ClientErrorItem
import kotobaya.android.shell.api.FieldError
import kotobaya.android.shell.api.Problem
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellHeaders
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotlin.math.floor

/** Where accepted reports go: the app's `ClientErrorQueue` (flushed to the server when online). */
fun interface ClientErrorSink {
    fun add(items: List<ClientErrorItem>)
}

/**
 * `reportClientErrors` as the server runs it (openapi `reportClientErrors`, C-23): the same check order, limits and
 * `errors[]`, then the items are queued instead of logged. The rate windows are in memory, per process, like the
 * server's per credential (there is one credential here). `client` is validated but not kept: the app's queue
 * reports everything as `android`.
 */
class ClientErrorIntake(private val sink: ClientErrorSink, private val nowMs: () -> Long) {
    private val requests = ArrayDeque<Long>()
    private val items = ArrayDeque<Pair<Long, Int>>()
    private var itemSum = 0

    private fun trim(now: Long) {
        while (requests.isNotEmpty() && requests.first <= now - WINDOW_MS) requests.removeFirst()
        while (items.isNotEmpty() && items.first.first <= now - WINDOW_MS) itemSum -= items.removeFirst().second
    }

    private fun secondsUntil(at: Long, now: Long): Long = maxOf(1L, (at - now + 999) / 1000)

    private fun rateLimited(seconds: Long) = ProblemException(
        Problem(
            429, "rate_limited", "Too many error reports", "Try again in $seconds seconds.",
            headers = mapOf(ShellHeaders.RETRY_AFTER to seconds.toString()),
        ),
    )

    /** Step 2: counts this request, or throws `429`. Before the body is read. */
    @Synchronized
    fun admitRequest() {
        val now = nowMs()
        trim(now)
        if (requests.size >= MAX_REQUESTS_PER_MINUTE) throw rateLimited(secondsUntil(requests.first + WINDOW_MS, now))
        requests.addLast(now)
    }

    @Synchronized
    private fun admitItems(n: Int) {
        val now = nowMs()
        trim(now)
        if (itemSum + n > MAX_ITEMS_PER_MINUTE) {
            var sum = itemSum
            for ((t, c) in items) {
                sum -= c
                if (sum + n <= MAX_ITEMS_PER_MINUTE) throw rateLimited(secondsUntil(t + WINDOW_MS, now))
            }
            throw rateLimited(1)
        }
        items.addLast(now to n)
        itemSum += n
    }

    /** Steps 3 to 8 for a body already read: [bodyBytes] is its UTF-8 size. Returns the number accepted. */
    fun accept(body: JsonValue?, bodyBytes: Long): Int {
        if (bodyBytes > MAX_BODY_BYTES) throw tooBig("The body is larger than $MAX_BODY_BYTES bytes; split the batch.")
        if (body == null || body == JsonNull) throw ProblemException(Problems.invalidRequest("The body is not valid JSON."))
        val raw = (body as? JsonObject)?.members?.get("items")
        if (raw is JsonArray && raw.items.size > MAX_ITEMS) throw tooBig("At most $MAX_ITEMS items per request; split the batch.")
        val errors = ArrayList<FieldError>()
        val parsed = validate(body, errors)
        if (errors.isNotEmpty()) {
            throw ProblemException(Problems.validationFailed(errors))
        }
        admitItems(parsed.size)
        sink.add(parsed)
        return parsed.size
    }

    private fun tooBig(detail: String) = ProblemException(Problems.payloadTooLarge(detail))

    // ---------------------------------------------------------------------------------------------

    private fun validate(body: JsonValue, errors: MutableList<FieldError>): List<ClientErrorItem> {
        if (body !is JsonObject) {
            errors += FieldError("", "invalid_type", "The body must be a JSON object.")
            return emptyList()
        }
        body.members.keys.forEach { if (it != "items" && it != "dropped") errors += FieldError("/$it", "unknown_field", "Unknown property $it.") }
        body.members["dropped"]?.let { integer(it, "/dropped", 0, Int.MAX_VALUE.toLong(), errors) }
        val out = ArrayList<ClientErrorItem>()
        val raw = body.members["items"]
        when {
            raw == null -> errors += FieldError("/items", "required", "items is required.")
            raw !is JsonArray -> errors += FieldError("/items", "invalid_type", "items must be an array.")
            raw.items.isEmpty() -> errors += FieldError("/items", "out_of_range", "items must have 1 to 20 elements.")
            else -> raw.items.forEachIndexed { i, n -> item(n, "/items/$i", errors)?.let { out += it } }
        }
        return out
    }

    private fun item(n: JsonValue, p: String, errors: MutableList<FieldError>): ClientErrorItem? {
        if (n !is JsonObject) {
            errors += FieldError(p, "invalid_type", "An item must be an object.")
            return null
        }
        val before = errors.size
        val m = n.members
        m.keys.forEach { if (it !in ITEM_FIELDS) errors += FieldError("$p/$it", "unknown_field", "Unknown property $it.") }
        enum(m, p, "client", CLIENTS, true, errors)
        val appVersion = string(m, p, "app_version", 1, 64, true, errors)?.also {
            if (!VERSION.matches(it)) errors += FieldError("$p/app_version", "invalid_value", "app_version must be printable ASCII.")
        }
        val occurred = string(m, p, "occurred_at", 0, Int.MAX_VALUE, true, errors)?.let { instant(it, "$p/occurred_at", errors) }
        val level = enum(m, p, "level", LEVELS, true, errors)
        val kind = enum(m, p, "kind", KINDS, true, errors)
        val message = string(m, p, "message", 1, 2048, true, errors)
        val stack = string(m, p, "stack", 0, 16384, false, errors)
        val route = string(m, p, "route", 0, 512, false, errors)
        val requestId = string(m, p, "request_id", 1, 128, false, errors)?.also {
            if (!REQUEST_ID.matches(it)) errors += FieldError("$p/request_id", "invalid_value", "request_id must be printable ASCII without spaces.")
        }
        val method = enum(m, p, "http_method", METHODS, false, errors)
        val path = string(m, p, "http_path", 0, 512, false, errors)
        val status = m["http_status"]?.let { integer(it, "$p/http_status", 100, 599, errors) }
        val count = m["count"]?.let { integer(it, "$p/count", 1, Int.MAX_VALUE.toLong(), errors) } ?: 1L
        if (errors.size > before || appVersion == null || occurred == null || level == null || kind == null || message == null) return null
        return ClientErrorItem(
            appVersion = appVersion, occurredAt = occurred, level = level, kind = kind, message = message, stack = stack,
            route = route, requestId = requestId, httpMethod = method, httpPath = path, httpStatus = status?.toInt(), count = count.toInt(),
        )
    }

    private fun enum(m: Map<String, JsonValue>, p: String, name: String, allowed: Set<String>, required: Boolean, errors: MutableList<FieldError>): String? {
        val v = m[name]
        if (v == null) {
            if (required) errors += FieldError("$p/$name", "required", "$name is required.")
            return null
        }
        if (v !is JsonString) {
            errors += FieldError("$p/$name", "invalid_type", "$name must be a string.")
            return null
        }
        if (v.value !in allowed) {
            errors += FieldError("$p/$name", "invalid_value", "$name must be one of ${allowed.joinToString()}.")
            return null
        }
        return v.value
    }

    private fun string(m: Map<String, JsonValue>, p: String, name: String, min: Int, max: Int, required: Boolean, errors: MutableList<FieldError>): String? {
        val v = m[name]
        if (v == null) {
            if (required) errors += FieldError("$p/$name", "required", "$name is required.")
            return null
        }
        if (v !is JsonString) {
            errors += FieldError("$p/$name", "invalid_type", "$name must be a string.")
            return null
        }
        val s = v.value
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

    private fun integer(v: JsonValue, pointer: String, min: Long, max: Long, errors: MutableList<FieldError>): Long? {
        val d = (v as? JsonNumber)?.value
        if (d == null || d.isNaN() || d.isInfinite() || d != floor(d)) {
            errors += FieldError(pointer, "invalid_type", "Must be an integer.")
            return null
        }
        if (d < min || d > max) {
            errors += FieldError(pointer, "out_of_range", "Must be between $min and $max.")
            return null
        }
        return d.toLong()
    }

    /** The instant as the queue wants it (UTC `Instant.toString()`), or null with an error. */
    private fun instant(s: String, pointer: String, errors: MutableList<FieldError>): String? {
        val parsed = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
        if (parsed == null) {
            errors += FieldError(pointer, "invalid_value", "Must be an RFC 3339 instant.")
            return null
        }
        if (parsed.isAfter(MAX_INSTANT)) {
            errors += FieldError(pointer, "out_of_range", "Must not be after 9999-12-31T23:59:59.999Z.")
            return null
        }
        return parsed.toString()
    }

    companion object {
        const val MAX_BODY_BYTES: Long = 1_048_576L
        const val MAX_ITEMS: Int = 20
        const val MAX_REQUESTS_PER_MINUTE: Int = 12
        const val MAX_ITEMS_PER_MINUTE: Int = 120
        private const val WINDOW_MS = 60_000L

        private val MAX_INSTANT = java.time.Instant.parse("9999-12-31T23:59:59.999Z")
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
    }
}
