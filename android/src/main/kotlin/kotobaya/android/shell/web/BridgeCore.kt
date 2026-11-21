package kotobaya.android.shell.web

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotobaya.android.shell.api.ApiOperations
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString

/** Where native posts a message to: the reply proxy of the page that sent the last message. */
fun interface BridgeReply {
    fun post(text: String)
}

/**
 * The transport-independent half of the bridge (SHELL.md 2.1-2.5): origin and main-frame filter, decoding,
 * duplicate ids, page lives, `ready` → `hello`, dispatch of `req` to [handle] on [io] and posting of `res`
 * on [main]. The Android adapter is [BridgeTransport]; tests drive this directly.
 *
 * Threading: [onMessage], [onPageStarted] and [send] are called on the main thread.
 */
class BridgeCore(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val main: CoroutineDispatcher,
    private val handle: suspend (ShellRequest) -> ShellResponse,
    /** `ShellEventHello` data (device pull cursor, log epoch, device clock); called on [io]. */
    private val helloData: () -> JsonObject,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Messages of types this layer does not act on itself (`back.result`, `back.state`, `haptic`, `theme`): A4. */
    private val onWebMessage: (BridgeMessage) -> Unit = {},
    /** A `ready` asked for a newer `LOCAL_API_VERSION` than this shell has (SHELL.md 7.2). */
    private val onIncompatible: (BridgeMessage.Ready) -> Unit = {},
    private val warn: (String) -> Unit = {},
    /** A new page life began (reset of per-page native state such as back enablement and theme). */
    private val onPageReset: () -> Unit = {},
    /** A handler took longer than [SLOW_HANDLER_MS]: the operationId and the duration (LF-A7); never a body. */
    private val onSlow: (operationId: String, ms: Long) -> Unit = ::logSlow,
    /** PERF-GATE: one structured record per call (see [PerfRecord]); only called when [perfAll] or the call was slow. */
    private val onPerf: (PerfRecord) -> Unit = ::logPerf,
    /** PERF-GATE: log every call, not only slow ones (system property `kotobaya.perf` = 1, or `setprop log.tag.KTB-PERF DEBUG`). */
    private val perfAll: () -> Boolean = ::perfAllFromEnvironment,
) {
    /** Incremented by every page load; a `res` of an older page life is dropped (2.5 "Reload"). */
    private var pageLife = 0L
    private val seenIds = HashSet<String>()
    private var ready = false
    private var reply: BridgeReply? = null
    private var pendingNavigate: String? = null

    /** True once `ready` arrived in this page life. */
    val isReady: Boolean get() = ready

    /** A new page load started (reload, renderer restart): forget ids, drop late `res`, not ready (2.5). */
    fun onPageStarted() {
        pageLife++
        seenIds.clear()
        ready = false
        reply = null
        onPageReset()
    }

    /**
     * One message from the WebView. Dropped without a reply unless [sourceOrigin] is exactly the shell origin and
     * [isMainFrame] (SHELL.md 2.1); invalid messages are dropped silently and logged without the body (2.3).
     */
    fun onMessage(text: String?, sourceOrigin: String, isMainFrame: Boolean, replyTo: BridgeReply) {
        if (sourceOrigin.trimEnd('/') != ShellOrigin.ORIGIN || !isMainFrame) {
            warn("bridge: dropped a message from ${if (isMainFrame) sourceOrigin else "a sub-frame"}")
            return
        }
        if (text == null) return
        val msg = when (val d = BridgeCodec.decode(text, BridgeDirection.WEB_TO_NATIVE)) {
            is Decoded.Valid -> d.message
            is Decoded.Unknown -> return
            is Decoded.Invalid -> {
                warn("bridge: dropped an invalid message (${d.reason})")
                return
            }
        }
        reply = replyTo
        when (msg) {
            is BridgeMessage.Req -> dispatch(msg, replyTo)
            is BridgeMessage.Ready -> onReady(msg, replyTo)
            else -> onWebMessage(msg)
        }
    }

    private fun dispatch(req: BridgeMessage.Req, replyTo: BridgeReply) {
        if (!seenIds.add(req.id)) return // duplicate id: not executed again, no second res (2.5)
        val life = pageLife
        scope.launch(io) {
            val startedAt = nowMs()
            val timing = CallTiming(nowMs)
            val response = try {
                withContext(timing) { handle(ShellRequest.fromRaw(req.method, req.path, req.query, req.headers, req.body)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                warn("bridge: ${req.method} ${req.path} failed: ${e.javaClass.simpleName}")
                Problems.internal().toResponse(req.path, req.header("x-request-id") ?: req.id)
            }
            val handled = nowMs()
            val took = handled - startedAt
            val id = ApiOperations.match(req.method, req.path)?.first?.operationId ?: "unmatched"
            if (took > SLOW_HANDLER_MS) runCatching { onSlow(id, took) }
            val text = BridgeCodec.encode(BridgeMessage.Res(req.id, response.status, response.headers, response.body))
            withContext(main) {
                if (life == pageLife) replyTo.post(text)
            }
            if (took > SLOW_HANDLER_MS || runCatching(perfAll).getOrDefault(false)) {
                runCatching {
                    onPerf(PerfRecord(id, response.status, timing.gateMs, timing.laneMs, timing.mutexMs, timing.computeMs, took, nowMs() - handled))
                }
            }
        }
    }

    private fun onReady(ready: BridgeMessage.Ready, replyTo: BridgeReply) {
        if (this.ready) return // once per page life
        if (ready.minLocalApi > LocalApi.LOCAL_API_VERSION) onIncompatible(ready)
        this.ready = true
        val life = pageLife
        scope.launch(io) {
            val data = try {
                helloData()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                warn("bridge: hello data failed: ${e.javaClass.simpleName}")
                fallbackHello()
            }
            withContext(main) {
                if (life != pageLife) return@withContext
                replyTo.post(BridgeCodec.encode(BridgeMessage.Event("hello", formatAt(nowMs()), data)))
                pendingNavigate?.let {
                    pendingNavigate = null
                    replyTo.post(BridgeCodec.encode(BridgeMessage.Navigate(it)))
                }
            }
        }
    }

    /**
     * Sends a native message (A4/A6). Before `ready` nothing is sent (2.5): a `navigate` is kept (only the latest)
     * and delivered right after `hello`; `event` and `back` are dropped. Returns true when posted now.
     */
    fun send(message: BridgeMessage): Boolean {
        val r = reply
        if (!ready || r == null) {
            if (message is BridgeMessage.Navigate) pendingNavigate = message.path
            return false
        }
        r.post(BridgeCodec.encode(message))
        return true
    }

    /** `hello` data when the store cannot be read: the SPA still gets its `hello` (cursor 0, nil epoch, device clock). */
    private fun fallbackHello(): JsonObject = JsonObject(
        linkedMapOf(
            "high_water_seq" to JsonNumber(0.0),
            "log_epoch" to JsonString("00000000-0000-0000-0000-000000000000"),
            "server_time" to JsonString(formatAt(nowMs())),
        ),
    )

    private fun BridgeMessage.Req.header(name: String): String? = headers[name]

    companion object {
        /** A request whose handler takes longer than this is logged (LF-A7). */
        const val SLOW_HANDLER_MS: Long = 250

        /** Logcat tag of every timing line (PERF-GATE), also of the startup "gate open after N ms" line. */
        const val PERF_TAG: String = "KTB-PERF"

        /** The slow-handler log line: operationId and duration only. */
        fun slowLine(operationId: String, ms: Long): String = "slow handler: $operationId took $ms ms"

        /** The `KTB-PERF` line: operationId, status and milliseconds only; never a body. */
        fun perfLine(r: PerfRecord): String =
            "call op=${r.operationId} status=${r.status} gate=${r.gateMs} lane=${r.laneMs} mutex=${r.mutexMs} compute=${r.computeMs} total=${r.totalMs} encode=${r.encodeMs}"

        private fun logPerf(r: PerfRecord) {
            runCatching { android.util.Log.i(PERF_TAG, perfLine(r)) }
        }

        private fun perfAllFromEnvironment(): Boolean =
            System.getProperty("kotobaya.perf") == "1" || runCatching { android.util.Log.isLoggable(PERF_TAG, android.util.Log.DEBUG) }.getOrDefault(false)

        private fun logSlow(operationId: String, ms: Long) {
            runCatching { android.util.Log.w("KotobayaLoad", slowLine(operationId, ms)) }
        }

        private val AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** RFC 3339 UTC with milliseconds (`event.at`). */
        fun formatAt(ms: Long): String = AT.format(Instant.ofEpochMilli(ms))
    }
}

/**
 * PERF-GATE: where one handler call spent its time. [totalMs] is the whole `handle` (clock starts before the readiness
 * gate); [gateMs] + [laneMs] + [mutexMs] + [computeMs] account for it (the rest is routing, a few microseconds);
 * [encodeMs] is the JSON encode of the `res` plus the post to the WebView, after the handler returned.
 */
data class PerfRecord(
    val operationId: String,
    val status: Int,
    val gateMs: Long,
    val laneMs: Long,
    val mutexMs: Long,
    val computeMs: Long,
    val totalMs: Long,
    val encodeMs: Long,
)

/** The marks of one call, carried in the coroutine context from [BridgeCore] to `LocalApi.handle`. */
class CallTiming(private val clock: () -> Long) : AbstractCoroutineContextElement(Key) {
    var gateMs: Long = 0
        private set
    var laneMs: Long = 0
        private set
    var mutexMs: Long = 0
        private set
    var computeMs: Long = 0
        private set

    fun now(): Long = clock()

    fun addGate(ms: Long) { gateMs += ms }
    fun addLane(ms: Long) { laneMs += ms }
    fun addMutex(ms: Long) { mutexMs += ms }
    fun addCompute(ms: Long) { computeMs += ms }

    companion object Key : CoroutineContext.Key<CallTiming>
}
