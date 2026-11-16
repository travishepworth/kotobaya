package kotobaya.android.shell.api

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotobaya.android.shell.api.actions.routes as actionsRoutes
import kotobaya.android.shell.api.browse.routes as browseRoutes
import kotobaya.android.shell.api.decks.routes as decksRoutes
import kotobaya.android.shell.api.device.routes as deviceRoutes
import kotobaya.android.shell.api.identity.routes as identityRoutes
import kotobaya.android.shell.api.mining.routes as miningRoutes
import kotobaya.android.shell.api.review.routes as reviewRoutes
import kotobaya.android.shell.api.struggle.routes as struggleRoutes

/** The shell's state the dispatcher's gates read (SHELL.md 2.5, 3.1, L-8). */
interface ShellGate {
    /** A device token is stored in `TokenStore`. */
    fun isPaired(): Boolean

    /** `sync_state.rebuild_pending = "1"` (rule L-8). */
    fun isRebuildPending(): Boolean

    /** Returns once the startup gate (`SyncBootstrap.ready`) is open. */
    suspend fun awaitReady()
}

/** Forwards a request to the server with the device token (rules P-1 … P-3). SHELL-A5 provides the real one. */
fun interface Upstream {
    suspend fun forward(request: ShellRequest, operation: ApiOperation, requestId: String): ShellResponse
}

/** The default [Upstream]: always `503 shell_offline`, no network (until SHELL-A5). */
object OfflineUpstream : Upstream {
    override suspend fun forward(request: ShellRequest, operation: ApiOperation, requestId: String): ShellResponse =
        Problems.shellOffline().toResponse(request.path, requestId)
}

/** Every group's routes. Each L ticket fills only its own group's `Routes.kt`. */
object LocalRoutes {
    val all: List<LocalRoute>
        get() = identityRoutes + reviewRoutes + actionsRoutes + miningRoutes + browseRoutes + decksRoutes + struggleRoutes + deviceRoutes
}

/**
 * THE dispatcher (SHELL.md 1, 3.1): `handle(ShellRequest): ShellResponse`, transport-free and
 * free of Android framework types. Check order, first match wins:
 * 1. limits of 2.4 (body over 4 MiB or not `application/json`; `getBlob` through the bridge) → `415`;
 * 2. no operation → `404`;
 * 3. not paired, except `getShellInfo` / `pairShell` → `401`;
 * 4. `hidden` → `403 insufficient_scope`;
 * 5. `proxy` → [Upstream];
 * 6. otherwise the readiness gate (20 s, then `503 shell_rebuilding`), the rebuild gate (L-8),
 *    then the handler. A handler returning [Fallthrough] is forwarded through [Upstream]; an
 *    operation with no registered handler yet is forwarded too (`shell.yaml` operations: `404`).
 * Every response the shell produces carries `kotobaya-local: 1` and `x-request-id`.
 */
class LocalApi(
    routes: List<LocalRoute>,
    private val gate: ShellGate,
    private val upstream: Upstream = OfflineUpstream,
    private val services: ShellServices = ShellServices.None,
    private val newRequestId: () -> String,
    private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
    private val logError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val routesById: Map<String, LocalRoute>

    init {
        val map = LinkedHashMap<String, LocalRoute>()
        for (r in routes) {
            val op = requireNotNull(ApiOperations.byId[r.operationId]) { "route for unknown operation ${r.operationId}" }
            require(op.opClass == OpClass.LOCAL || op.opClass == OpClass.LOCAL_OR_PROXY || op.opClass == OpClass.SHELL) {
                "route for ${r.operationId}, which is ${op.opClass}"
            }
            require(op.method == r.method && op.pathTemplate == r.pathTemplate) {
                "route ${r.operationId} is ${r.method} ${r.pathTemplate}, contract says ${op.method} ${op.pathTemplate}"
            }
            require(map.put(r.operationId, r) == null) { "two routes for ${r.operationId}" }
        }
        routesById = map
    }

    /** The registered operationIds. */
    val registered: Set<String> get() = routesById.keys

    suspend fun handle(raw: ShellRequest): ShellResponse {
        val requestId = raw.header(ShellHeaders.REQUEST_ID)?.takeIf { REQUEST_ID_PATTERN.matches(it) } ?: newRequestId()
        val request = if (raw.method == "GET") raw.copy(body = null) else raw
        fun problem(p: Problem) = p.toResponse(request.path, requestId)

        request.body?.let { body ->
            if (utf8Length(body) > MAX_BODY_BYTES) return problem(Problems.shellUnsupported("The request body is larger than 4 MiB."))
            val media = request.header(ShellHeaders.CONTENT_TYPE)?.substringBefore(';')?.trim()?.lowercase()
            if (media != ShellHeaders.JSON) return problem(Problems.shellUnsupported("Only application/json bodies are supported in the app."))
        }
        val (op, params) = ApiOperations.match(request.method, request.path)
            ?: return problem(Problems.notFound("No operation matches ${request.method} ${request.path}."))
        if (op.operationId == GET_BLOB) return problem(Problems.shellUnsupported("Blobs are loaded by URL in the app."))
        if (op.operationId !in UNPAIRED_ALLOWED && !gate.isPaired()) return problem(Problems.unauthenticated())
        when (op.opClass) {
            OpClass.HIDDEN -> return problem(Problems.insufficientScope(op.requiredScope))
            OpClass.PROXY -> return forward(request, op, requestId)
            else -> Unit
        }
        val route = routesById[op.operationId]
            ?: return if (op.opClass == OpClass.SHELL) {
                problem(Problems.notFound("${op.operationId} is not available in this version."))
            } else {
                forward(request, op, requestId)
            }
        if (op.operationId !in GATE_EXEMPT) {
            withTimeoutOrNull(readyTimeoutMs) { gate.awaitReady() } ?: return problem(Problems.shellRebuilding())
            if (gate.isRebuildPending()) return problem(Problems.shellRebuilding())
        }
        val response = try {
            route.handler(LocalCall(request, op, params, requestId, services))
        } catch (e: ProblemException) {
            return problem(e.problem)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logError("${op.operationId} failed (request $requestId)", e)
            return problem(Problems.internal())
        }
        if (response === Fallthrough) return forward(request, op, requestId)
        val headers = LinkedHashMap<String, String>()
        response.headers.forEach { (k, v) -> headers[k.lowercase()] = v }
        headers[ShellHeaders.LOCAL] = "1"
        headers[ShellHeaders.REQUEST_ID] = requestId
        return response.copy(headers = headers)
    }

    private suspend fun forward(request: ShellRequest, op: ApiOperation, requestId: String): ShellResponse = try {
        upstream.forward(request, op, requestId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logError("forwarding ${op.operationId} failed (request $requestId)", e)
        Problems.internal().toResponse(request.path, requestId)
    }

    companion object {
        /** `LOCAL_API_VERSION` (SHELL.md 7.2). */
        const val LOCAL_API_VERSION: Int = 2

        const val MAX_BODY_BYTES: Long = 4L * 1024 * 1024
        const val READY_TIMEOUT_MS: Long = 20_000
        const val GET_BLOB: String = "getBlob"

        /** Answered while unpaired (SHELL.md 3.1 step 3). */
        val UNPAIRED_ALLOWED: Set<String> = setOf("getShellInfo", "pairShell", "getDisplayPrefs", "updateDisplayPrefs")

        /** Exempt from the readiness gate (2.5) and the rebuild gate (L-8). */
        val GATE_EXEMPT: Set<String> = setOf("getShellInfo", "pairShell", "unpairShell", "getSyncStatus", "syncNow")

        private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")

        internal fun utf8Length(s: String): Long {
            var n = 0L
            var i = 0
            while (i < s.length) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return n
        }
    }
}
