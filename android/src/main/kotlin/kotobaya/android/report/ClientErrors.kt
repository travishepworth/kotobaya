package kotobaya.android.report

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import org.json.JSONArray
import org.json.JSONObject

/** Logcat tag of everything in this package (`adb logcat -s KotobayaReport`). */
const val REPORT_TAG: String = "KotobayaReport"

/** Privacy by construction: exception text is scrubbed, stacks carry class names and frames only (C-23). */
object ErrorText {
    private val QUOTED = Regex("\"[^\"]*\"|'[^']*'|`[^`]*`")
    private val SPACE = Regex("\\s+")
    private const val MAX_EXCEPTION_MESSAGE = 200

    /** [e]'s class and message with quoted text (may be note or card content), control characters and length removed. */
    fun message(e: Throwable): String {
        val raw = e.message.orEmpty().replace(QUOTED, "…").replace(SPACE, " ").trim()
        val clean = ClientErrorLimits.cap(raw, MAX_EXCEPTION_MESSAGE)
        return e.javaClass.name + if (clean.isEmpty()) "" else ": $clean"
    }

    /** The causal chain as class names and frames, no messages. */
    fun stack(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 5) {
            sb.append(if (depth == 0) "" else "Caused by: ").append(t.javaClass.name).append('\n')
            t.stackTrace.take(40).forEach { sb.append("    at ").append(it).append('\n') }
            t = t.cause?.takeIf { it !== t }
            depth++
        }
        return ClientErrorLimits.cap(sb.toString(), ClientErrorLimits.MAX_STACK)
    }
}

/** SharedPreferences-backed [QueueStorage] (small; `apply` off the caller, `commit` for crashes). */
class PrefsQueueStorage(private val prefs: SharedPreferences) : QueueStorage {
    constructor(context: Context) : this(context.getSharedPreferences("kotobaya-client-errors", Context.MODE_PRIVATE))

    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(text: String) = prefs.edit().putString(KEY, text).apply().let { }
    override fun saveNow(text: String) = prefs.edit().putString(KEY, text).commit().let { }

    private companion object {
        const val KEY = "queue"
    }
}

/** JSON form of the queue file and of the request body. */
object ClientErrorJson {
    fun itemToJson(i: ClientErrorItem): JSONObject = JSONObject().apply {
        put("client", "android")
        put("app_version", i.appVersion)
        put("occurred_at", i.occurredAt)
        put("level", i.level)
        put("kind", i.kind)
        put("message", i.message)
        i.stack?.let { put("stack", it) }
        i.route?.let { put("route", it) }
        i.requestId?.let { put("request_id", it) }
        i.httpMethod?.let { put("http_method", it) }
        i.httpPath?.let { put("http_path", it) }
        i.httpStatus?.let { put("http_status", it) }
        if (i.count > 1) put("count", i.count)
    }

    fun body(items: List<ClientErrorItem>, dropped: Int): JSONObject = JSONObject().apply {
        put("items", JSONArray().also { a -> items.forEach { a.put(itemToJson(it)) } })
        if (dropped > 0) put("dropped", dropped)
    }

    fun serialize(items: List<ClientErrorItem>, dropped: Int): String = body(items, dropped).toString()

    fun parse(text: String): Pair<List<ClientErrorItem>, Int>? = runCatching {
        val o = JSONObject(text)
        val a = o.getJSONArray("items")
        val items = (0 until a.length()).map { k ->
            val j = a.getJSONObject(k)
            ClientErrorItem(
                appVersion = j.getString("app_version"),
                occurredAt = j.getString("occurred_at"),
                level = j.getString("level"),
                kind = j.getString("kind"),
                message = j.getString("message"),
                stack = j.optString("stack").ifEmpty { null },
                route = j.optString("route").ifEmpty { null },
                requestId = j.optString("request_id").ifEmpty { null },
                httpMethod = j.optString("http_method").ifEmpty { null },
                httpPath = j.optString("http_path").ifEmpty { null },
                httpStatus = if (j.has("http_status")) j.getInt("http_status") else null,
                count = j.optInt("count", 1),
            )
        }
        items to o.optInt("dropped", 0)
    }.getOrNull()
}

/**
 * The process-wide entry point for reporting (OBS-1c-and). Everything is a no-op until [install] ran (unit tests,
 * previews). Recording never throws, never touches the network or the database, and a failure of the report
 * path itself is only logged under [REPORT_TAG], never reported.
 */
object ClientErrors {
    @Volatile private var queue: ClientErrorQueue? = null

    @Volatile private var version: String = "0.0.0"

    /** The current screen (a route pattern, never concrete ids or query strings); set by the nav host. */
    @Volatile var screen: String? = null

    fun install(q: ClientErrorQueue, appVersion: String) {
        queue = q
        version = appVersion
    }

    internal fun queueOrNull(): ClientErrorQueue? = queue

    private fun now(): String = Instant.now().toString()

    /** A failed screen load or other caught top-level exception. */
    fun exception(e: Throwable, where: String) {
        // ApiErrors were already reported by the ApiClient at the call (5xx/network) or are handled 4xx
        if (e is ApiError) return
        runCatching {
            queue?.add(
                ClientErrorItem(
                    appVersion = version, occurredAt = now(), level = "error", kind = "exception",
                    message = "$where: ${ErrorText.message(e)}", stack = ErrorText.stack(e), route = screen,
                ),
            )
        }.onFailure { Log.w(REPORT_TAG, "could not record an exception report: ${it.javaClass.simpleName}") }
    }

    /** The process is dying of [e]; written synchronously, sent on the next start. */
    fun crash(e: Throwable, thread: String) {
        runCatching {
            queue?.add(
                ClientErrorItem(
                    appVersion = version, occurredAt = now(), level = "error", kind = "crash",
                    message = "uncaught in $thread: ${ErrorText.message(e)}", stack = ErrorText.stack(e), route = screen,
                ),
                durable = true,
            )
        }.onFailure { Log.w(REPORT_TAG, "could not record a crash report: ${it.javaClass.simpleName}") }
    }

    /** An API call failed with a 5xx or at the network level. Never for `/client-errors` itself. */
    fun apiFailure(method: String, path: String, status: Int?, requestId: String?) {
        val p = ClientErrorLimits.stripQuery(path)
        if (p.endsWith("/client-errors")) return
        runCatching {
            queue?.add(
                ClientErrorItem(
                    appVersion = version, occurredAt = now(), level = "error", kind = "api_failure",
                    message = "$method $p failed: ${status ?: "network"}", route = screen,
                    requestId = requestId, httpMethod = method, httpPath = p, httpStatus = status,
                ),
            )
        }.onFailure { Log.w(REPORT_TAG, "could not record an api failure report: ${it.javaClass.simpleName}") }
    }
}

/** Chains to the previous handler after persisting the crash (the process still dies as before). */
class CrashReportHandler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching { ClientErrors.crash(e, t.name) }
        previous?.uncaughtException(t, e)
    }
}

/** Sends the queue through the app's [ApiClient]; single-flight, honours Retry-After, never reports its own failure. */
class ClientErrorFlusher(
    private val client: () -> ApiClient,
    private val isPaired: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile private var blockedUntil = 0L

    /** Fire-and-forget: after a sync cycle reached the server, and on app start. */
    fun flushAsync() {
        scope.launch { flush() }
    }

    suspend fun flush() {
        val q = ClientErrors.queueOrNull() ?: return
        if (q.size == 0 || !isPaired() || clock() < blockedUntil) return
        if (!mutex.tryLock()) return
        try {
            val r = q.flush { items, dropped -> send(items, dropped) }
            r.backoffSeconds?.let { blockedUntil = clock() + it * 1000 }
            if (r.sent > 0) Log.i(REPORT_TAG, "sent ${r.sent} client error report(s)")
        } catch (e: Throwable) {
            Log.w(REPORT_TAG, "flush failed: ${e.javaClass.simpleName}") // logged only; a failed report is never reported
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun send(items: List<ClientErrorItem>, dropped: Int): SendResult = try {
        client().post("/client-errors", ClientErrorJson.body(items, dropped), auth = true)
        SendResult.Accepted
    } catch (e: ApiError) {
        when (e) {
            is ApiError.RateLimited -> SendResult.RateLimited(e.retryAfterSeconds)
            is ApiError.Server, is ApiError.Offline, is ApiError.Unreachable, is ApiError.Unauthorized,
            is ApiError.Forbidden, is ApiError.BadBody,
            -> SendResult.Retry
            is ApiError.Other -> if (e.status == 413) SendResult.TooLarge else SendResult.Rejected
            else -> SendResult.Rejected
        }
    }
}
