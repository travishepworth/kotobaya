package kotobaya.server

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ThreadContextElement
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * OBS-1s: the MDC keys every log line of a request carries (LogstashEncoder writes MDC entries as top-level JSON
 * fields). `token.id` / `token.name` keep the names of the original audit trail.
 */
object LogKeys {
    const val REQUEST_ID = "request_id"
    const val TOKEN_ID = "token.id"
    const val TOKEN_NAME = "token.name"
    const val DEVICE_ID = "device_id"
}

/** Accepted inbound `X-Request-Id` values; anything else is replaced by a minted id (no log injection). */
private val REQUEST_ID_RE = Regex("^[A-Za-z0-9._:-]{1,128}$")

/** The caller's `X-Request-Id` when it is safe to log and echo, else null (the caller mints one). */
fun acceptRequestId(header: String?): String? = header?.trim()?.takeIf { REQUEST_ID_RE.matches(it) }

/**
 * The MDC of one call, carried by its coroutine: on every resume the thread's MDC is set to [values], and restored
 * when the coroutine suspends. [bind] adds the credential once it is known (after authentication).
 */
class CallMdc(initial: Map<String, String>) :
    AbstractCoroutineContextElement(Key), ThreadContextElement<Map<String, String>?> {
    companion object Key : CoroutineContext.Key<CallMdc>

    private val values = ConcurrentHashMap(initial)

    fun put(key: String, value: String?) {
        if (value == null) {
            values.remove(key)
            MDC.remove(key)
        } else {
            values[key] = value
            MDC.put(key, value)
        }
    }

    fun bind(token: TokenInfo?) {
        if (token == null) return
        put(LogKeys.TOKEN_ID, token.id)
        put(LogKeys.TOKEN_NAME, token.name)
        put(LogKeys.DEVICE_ID, token.deviceId)
    }

    override fun updateThreadContext(context: CoroutineContext): Map<String, String>? {
        val old = MDC.getCopyOfContextMap()
        MDC.setContextMap(HashMap(values))
        return old
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Map<String, String>?) {
        if (oldState == null) MDC.clear() else MDC.setContextMap(oldState)
    }
}

val CallMdcKey = AttributeKey<CallMdc>("kotobaya.mdc")

/** The log context of [this] call from its attributes (request id, and the credential once known). */
fun ApplicationCall.logContext(): Map<String, String> = buildMap {
    attributes.getOrNull(RequestIdKey)?.let { put(LogKeys.REQUEST_ID, it) }
    attributes.getOrNull(TokenKey)?.let { t ->
        put(LogKeys.TOKEN_ID, t.id)
        put(LogKeys.TOKEN_NAME, t.name)
        t.deviceId?.let { put(LogKeys.DEVICE_ID, it) }
    }
}

/**
 * Runs [block] (synchronous logging) with the thread's MDC set to [call]'s context, whatever coroutine context the
 * caller runs in (StatusPages and ResponseSent hooks run outside the call's [CallMdc]).
 */
fun <T> withCallMdc(call: ApplicationCall, block: () -> T): T {
    val old = MDC.getCopyOfContextMap()
    MDC.setContextMap(call.logContext())
    try {
        return block()
    } finally {
        if (old == null) MDC.clear() else MDC.setContextMap(old)
    }
}

/** For background scopes: an uncaught coroutine failure is logged at ERROR with its stack trace, never lost. */
fun loggingExceptionHandler(log: Logger = LoggerFactory.getLogger("kotobaya.coroutines")): CoroutineExceptionHandler =
    CoroutineExceptionHandler { ctx, e -> log.error("uncaught coroutine exception in {}", ctx[kotlinx.coroutines.CoroutineName]?.name ?: "background job", e) }

/** Installs the JVM-wide fallback: an uncaught thread exception is logged at ERROR with its stack trace. */
fun installUncaughtExceptionLogging() {
    val log = LoggerFactory.getLogger("kotobaya.uncaught")
    Thread.setDefaultUncaughtExceptionHandler { t, e -> log.error("uncaught exception in thread {}", t.name, e) }
}

/** Records the call's credential (the audit trail of the access log) and adds it to the call's MDC. */
fun ApplicationCall.setToken(token: TokenInfo) {
    attributes.put(TokenKey, token)
    attributes.getOrNull(CallMdcKey)?.bind(token)
}
