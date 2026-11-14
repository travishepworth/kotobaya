package kotobaya.android.report

import java.time.Instant

/** One report as `POST /client-errors` takes it (C-23 `ClientErrorItem`); `client` is always `android`. */
data class ClientErrorItem(
    val appVersion: String,
    val occurredAt: String,
    val level: String,
    val kind: String,
    val message: String,
    val stack: String? = null,
    val route: String? = null,
    val requestId: String? = null,
    val httpMethod: String? = null,
    val httpPath: String? = null,
    val httpStatus: Int? = null,
    val count: Int = 1,
) {
    /** Identical reports fold into one item (C-23 client obligations). */
    internal fun key(): List<Any?> = listOf(kind, level, message, route, httpMethod, httpPath, httpStatus)
}

/** Field limits of C-23 and the pre-validation that keeps one bad item from 422ing a whole batch. */
object ClientErrorLimits {
    const val MAX_BATCH: Int = 20
    const val MAX_PENDING: Int = 100
    const val MAX_MESSAGE: Int = 2048
    const val MAX_STACK: Int = 16384
    const val MAX_ROUTE: Int = 512
    const val MAX_PATH: Int = 512
    const val MAX_VERSION: Int = 64
    const val MAX_REQUEST_ID: Int = 128
    private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    private val LEVELS = setOf("error", "warn")
    private val KINDS = setOf("exception", "api_failure", "unhandled_rejection", "crash")

    /** Cuts at the first `?` or `#` (query strings can hold search text). */
    fun stripQuery(s: String): String = s.substringBefore('?').substringBefore('#')

    /** Truncates to [max] Unicode code points, ending a truncated value with an ellipsis. */
    fun cap(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        val end = s.offsetByCodePoints(0, max - 1)
        return s.substring(0, end) + "…"
    }

    private fun printable(s: String, from: Int, to: Int) = s.isNotEmpty() && s.all { it.code in from..to }

    /**
     * The item made valid, or null when it cannot be made valid (then it is counted as dropped, never sent).
     * Truncates and strips what can be; omits optional fields that cannot be fixed.
     */
    fun normalize(i: ClientErrorItem): ClientErrorItem? {
        if (i.level !in LEVELS || i.kind !in KINDS) return null
        val version = i.appVersion.map { if (it.code in 0x20..0x7E) it else '?' }.joinToString("").take(MAX_VERSION)
        if (version.isEmpty()) return null
        if (runCatching { Instant.parse(i.occurredAt) }.isFailure) return null
        val message = cap(i.message.ifBlank { "unknown error" }, MAX_MESSAGE)
        return i.copy(
            appVersion = version,
            message = message,
            stack = i.stack?.takeIf { it.isNotEmpty() }?.let { cap(it, MAX_STACK) },
            route = i.route?.let { stripQuery(it) }?.let { cap(it, MAX_ROUTE) },
            requestId = i.requestId?.takeIf { it.length <= MAX_REQUEST_ID && printable(it, 0x21, 0x7E) },
            httpMethod = i.httpMethod?.takeIf { it in METHODS },
            httpPath = i.httpPath?.let { stripQuery(it) }?.let { cap(it, MAX_PATH) },
            httpStatus = i.httpStatus?.takeIf { it in 100..599 },
            count = i.count.coerceAtLeast(1),
        )
    }
}

/** What the server (or the network) said to one `POST /client-errors`. */
sealed interface SendResult {
    data object Accepted : SendResult
    data object TooLarge : SendResult
    data class RateLimited(val retryAfterSeconds: Long?) : SendResult

    /** A non-retryable refusal (400/415/422...): the batch will never be accepted as it is. */
    data object Rejected : SendResult

    /** Network failure, 5xx, 401/403: keep the items and try again later. */
    data object Retry : SendResult
}

/** Persistence of the queue (one string); [saveNow] is for the crash path, which must survive process death. */
interface QueueStorage {
    fun load(): String?
    fun save(text: String)
    fun saveNow(text: String) = save(text)
}

/**
 * The pending reports: at most [ClientErrorLimits.MAX_PENDING], identical ones folded into `count`, a `dropped`
 * counter for what was discarded, persisted after every change. Thread-safe. It never reports anything about
 * itself (no feedback loop): failures are only handed to [log].
 */
class ClientErrorQueue(
    private val storage: QueueStorage,
    private val serialize: (List<ClientErrorItem>, Int) -> String,
    private val parse: (String) -> Pair<List<ClientErrorItem>, Int>?,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val pending = ArrayList<ClientErrorItem>()
    private var dropped = 0

    init {
        val saved = runCatching { storage.load()?.let(parse) }.getOrNull()
        if (saved != null) {
            pending.addAll(saved.first.take(ClientErrorLimits.MAX_PENDING))
            dropped = saved.second
        }
    }

    val size: Int get() = synchronized(lock) { pending.size }
    val droppedCount: Int get() = synchronized(lock) { dropped }
    fun snapshot(): List<ClientErrorItem> = synchronized(lock) { pending.toList() }

    /** Queues [item] (validated first); [durable] writes synchronously (the crash handler). */
    fun add(item: ClientErrorItem, durable: Boolean = false) {
        synchronized(lock) {
            val n = ClientErrorLimits.normalize(item)
            if (n == null) {
                bumpDropped(1)
            } else {
                val at = pending.indexOfFirst { it.key() == n.key() }
                if (at >= 0) {
                    val old = pending[at]
                    pending[at] = old.copy(count = (old.count.toLong() + n.count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                } else {
                    if (pending.size >= ClientErrorLimits.MAX_PENDING) {
                        pending.removeAt(0) // keep the newest
                        bumpDropped(1)
                    }
                    pending.add(n)
                }
            }
            persist(durable)
        }
    }

    private fun bumpDropped(by: Int) {
        dropped = (dropped.toLong() + by).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun persist(durable: Boolean) {
        val text = serialize(pending, dropped)
        runCatching { if (durable) storage.saveNow(text) else storage.save(text) }
            .onFailure { log("could not persist the report queue: ${it.javaClass.simpleName}") }
    }

    /** Result of a [flush]. */
    data class Flushed(val sent: Int, val backoffSeconds: Long?)

    /**
     * Sends everything pending in batches of at most 20 through [send] (`items`, `dropped` -> result).
     * 413 halves the batch (one item that is still too large is dropped); 429 stops and returns the wait; a
     * non-retryable refusal drops the batch (counted in `dropped`); a retryable failure stops and keeps the items.
     */
    suspend fun flush(send: suspend (List<ClientErrorItem>, Int) -> SendResult): Flushed {
        var sent = 0
        while (true) {
            val batch: List<ClientErrorItem>
            val dropNow: Int
            synchronized(lock) {
                batch = pending.take(ClientErrorLimits.MAX_BATCH)
                dropNow = dropped
            }
            if (batch.isEmpty()) return Flushed(sent, null)
            val outcome = sendSplit(batch, dropNow, send)
            sent += outcome.sent
            if (outcome.stop) return Flushed(sent, outcome.backoff)
        }
    }

    private class Step(val sent: Int, val stop: Boolean, val backoff: Long? = null)

    private suspend fun sendSplit(
        batch: List<ClientErrorItem>,
        dropNow: Int,
        send: suspend (List<ClientErrorItem>, Int) -> SendResult,
    ): Step = when (val r = send(batch, dropNow)) {
        SendResult.Accepted -> {
            remove(batch, accepted = dropNow)
            Step(batch.size, false)
        }
        SendResult.TooLarge -> if (batch.size == 1) {
            remove(batch, accepted = 0, drop = 1)
            Step(0, false)
        } else {
            val half = batch.size / 2
            val a = sendSplit(batch.subList(0, half), dropNow, send)
            if (a.stop) {
                a
            } else {
                val b = sendSplit(batch.subList(half, batch.size), if (a.sent > 0) 0 else dropNow, send)
                Step(a.sent + b.sent, b.stop, b.backoff)
            }
        }
        is SendResult.RateLimited -> Step(0, true, (r.retryAfterSeconds ?: 60L).coerceIn(1L, 3600L))
        SendResult.Rejected -> {
            remove(batch, accepted = 0, drop = batch.size)
            Step(0, false)
        }
        SendResult.Retry -> Step(0, true)
    }

    private fun remove(batch: List<ClientErrorItem>, accepted: Int, drop: Int = 0) {
        synchronized(lock) {
            // an item may have been folded into while in flight: remove what was sent, keep the extra count
            for (b in batch) {
                val at = pending.indexOfFirst { it.key() == b.key() }
                if (at < 0) continue
                val cur = pending[at]
                if (cur.count > b.count) pending[at] = cur.copy(count = cur.count - b.count) else pending.removeAt(at)
            }
            dropped = (dropped - accepted).coerceAtLeast(0)
            bumpDropped(drop)
            persist(durable = false)
        }
    }
}
