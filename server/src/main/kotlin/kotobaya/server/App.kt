package kotobaya.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.install
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import java.time.Instant

/** Build version from the generated `kotobaya-version.properties` (F-16); "dev" when absent. */
val SERVER_VERSION: String = runCatching {
    val props = java.util.Properties()
    object {}.javaClass.getResourceAsStream("/kotobaya-version.properties")?.use { props.load(it) }
    props.getProperty("version")?.takeIf { it.isNotBlank() }
}.getOrNull() ?: "dev"

const val CORE_VERSION = "0.1.0"

/** Everything the application needs; tests and `main` build one. */
class AppDeps(
    val db: Database,
    val spec: ApiSpec = ApiSpec.load(),
    val authenticator: Authenticator = AllowAll,
    /** operationId to handler. Anything missing is a 501 problem+json stub. Later tickets add to this. */
    val handlers: Map<String, OperationHandler> = emptyMap(),
    val startup: StartupSequence? = null,
    val bucketInit: BucketInit = BucketInit.Disabled,
    /** Extra readiness checks (T202 adds "SYNC 10.5 startup replay finished"). */
    val extraReadiness: List<ReadinessCheck> = emptyList(),
) {
    val readiness: List<ReadinessCheck> =
        listOf<ReadinessCheck>(DatabaseCheck(db)) + listOfNotNull(startup?.let { StartupCheck(it.state) }) + extraReadiness
    val requirements = ScopeRequirements.from(spec)
}

private val requestLog = LoggerFactory.getLogger("kotobaya.request")
private val StartKey = io.ktor.util.AttributeKey<Long>("kotobaya.start")

/** Probe and scrape paths: their access-log lines are DEBUG, so kubelet probes do not drown the log. */
private val QUIET_PATHS = setOf("/healthz", "/readyz", "/metrics")

/**
 * One structured JSON access-log line per request (OBS-1s): method, path, status, `duration_ms`, and from the MDC
 * `request_id`, `token.id`, `token.name` (the audit trail) and `device_id`. 5xx is WARN; probes are DEBUG.
 */
private val AccessLog = createApplicationPlugin("AccessLog") {
    on(ResponseSent) { call ->
        val ms = (System.nanoTime() - (call.attributes.getOrNull(StartKey) ?: System.nanoTime())) / 1_000_000
        val path = call.request.local.uri.substringBefore('?')
        val status = call.response.status()?.value
        val args = arrayOf<Any?>(
            kv("method", call.request.httpMethod.value),
            kv("path", path),
            kv("status", status),
            kv("duration_ms", ms),
        )
        withCallMdc(call) {
            when {
                status != null && status >= 500 -> requestLog.warn("request", *args)
                path in QUIET_PATHS -> requestLog.debug("request", *args)
                else -> requestLog.info("request", *args)
            }
        }
    }
}

/**
 * Request id (OBS-1s): the caller's `X-Request-Id` when well formed, else a minted UUIDv7; echoed in the response
 * header and in problem bodies. The rest of the call runs with a [CallMdc] holding it (and the credential once
 * [setToken] binds it), so every log line of the request carries them.
 */
private fun Application.installRequestContext() {
    intercept(ApplicationCallPipeline.Setup) {
        val id = acceptRequestId(call.request.headers["X-Request-Id"]) ?: Uuid7.generate().toString()
        call.attributes.put(RequestIdKey, id)
        call.attributes.put(StartKey, System.nanoTime())
        call.response.headers.append("X-Request-Id", id)
        val mdc = CallMdc(mapOf(LogKeys.REQUEST_ID to id))
        call.attributes.put(CallMdcKey, mdc)
        withContext(mdc) { proceed() }
    }
}

fun Application.kotobayaModule(deps: AppDeps) {
    val log = LoggerFactory.getLogger("kotobaya.app")
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    installRequestContext()
    install(AccessLog)
    install(MicrometerMetrics) { this.registry = registry }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            // A handler's own 5xx (unavailable, ...) is not a crash, but it is worth a line with its cause.
            if (e.status.value >= 500) withCallMdc(call) { log.warn("request failed: {} {}", e.status.value, e.message) }
            call.respondProblem(e)
        }
        exception<Throwable> { call, e ->
            withCallMdc(call) {
                log.error("unhandled error {} {}", call.request.httpMethod.value, call.request.local.uri.substringBefore('?'), e)
            }
            call.respondProblem(Problems.internal())
        }
        // Unmatched routes (no body written): problem+json instead of an empty 404.
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respondProblem(
                ApiException(HttpStatusCode.MethodNotAllowed, "method_not_allowed", "Method not allowed", "No such operation."),
            )
        }
        status(HttpStatusCode.NotFound) { call, _ -> call.respondProblem(Problems.notFound("No such route.")) }
    }

    val builtin: Map<String, OperationHandler> = mapOf(
        "getHealth" to { getHealth(deps) },
        "getMeta" to { getMeta(deps) },
    )
    val handlers = builtin + deps.handlers

    routing {
        get("/healthz") { call.respondText("ok") }
        get("/readyz") { call.readyz(deps) }
        get("/metrics") {
            call.respondText(registry.scrape(), ContentType.parse("text/plain; version=0.0.4; charset=utf-8"))
        }
        apiRoutes(deps, handlers)
    }

    monitor.subscribe(ApplicationStarted) {
        val onCrash = loggingExceptionHandler()
        deps.startup?.let { s -> launch(onCrash) { s.runUntilDone() } }
        launch(onCrash) { ensureBucketWithRetry(deps.bucketInit) }
    }
}

/** Registers every operation of the spec under its server base (`/api/v1`); no hand-written paths. */
private fun Route.apiRoutes(deps: AppDeps, handlers: Map<String, OperationHandler>) {
    route(deps.spec.basePath) {
        for (op in deps.spec.operations) {
            val handler = handlers[op.operationId]
            route(op.path, op.method) {
                handle {
                    val token = deps.authenticator.authenticate(call, deps.requirements.forOperation(op.operationId))
                    if (token != null) call.setToken(token)
                    if (handler == null) throw Problems.notImplemented(op.operationId)
                    handler(call)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.readyz(deps: AppDeps) {
    val failures = deps.readiness.mapNotNull { c -> c.check()?.let { c.name to it } }
    if (failures.isEmpty()) {
        respondText("ok")
    } else {
        throw Problems.unavailable(failures.joinToString("; ") { (n, why) -> "$n: $why" })
    }
}

private suspend fun ApplicationCall.getHealth(deps: AppDeps) {
    if (!deps.db.ping()) throw Problems.unavailable("Postgres is unreachable.")
    respondJson(mapOf("status" to "ok", "version" to SERVER_VERSION, "time" to formatInstant(Instant.now())))
}

private suspend fun ApplicationCall.getMeta(deps: AppDeps) {
    val row = runCatching {
        deps.db.tx { dsl ->
            val epoch = dsl.fetchOptional("select value from app.server_state where key = ?", ServerStateKeys.LOG_EPOCH)
                .map { it.get(0, String::class.java) }.orElse(null)
            val seq = dsl.fetchValue("select coalesce(max(seq), 0) from app.change") as Long
            val release = dsl.fetchOptional("select id, version from ref.dict_release where is_current")
                .map { mapOf("id" to it.get(0, Int::class.javaObjectType), "version" to it.get(1, String::class.java)) }
                .orElse(null)
            Triple(epoch, seq, release)
        }
    }.getOrElse { throw Problems.unavailable("Postgres is unreachable.") }
    // The log epoch is created by the startup sequence; until then the log has no identity to report.
    val epoch = row.first ?: throw Problems.unavailable("The sync log epoch is not initialised yet.")
    respondJson(
        mapOf(
            "api_version" to "v1",
            "server_version" to SERVER_VERSION,
            "core_version" to CORE_VERSION,
            "server_device_id" to SERVER_DEVICE_ID,
            "high_water_seq" to row.second,
            "log_epoch" to epoch,
            "server_time" to formatInstant(Instant.now()),
            "dict_release" to row.third,
            "limits" to Limits.asMap(),
        ),
    )
}

/** `GET /meta` limits. Invariant: `maxOpBytes <= maxPushBytes - 4096`. */
object Limits {
    const val MAX_PUSH_OPS = 500
    const val MAX_PUSH_BYTES = 8_388_608
    const val MAX_OP_BYTES = 1_048_576
    const val MAX_PULL_OPS = 1000
    const val MAX_BLOB_BYTES = 2_147_483_648L
    const val MAX_JSON_BYTES = 4_194_304
    const val MAX_PAGE_SIZE = 500

    fun asMap(): Map<String, Any> = mapOf(
        "max_push_ops" to MAX_PUSH_OPS,
        "max_push_bytes" to MAX_PUSH_BYTES,
        "max_op_bytes" to MAX_OP_BYTES,
        "max_pull_ops" to MAX_PULL_OPS,
        "max_blob_bytes" to MAX_BLOB_BYTES,
        "max_json_bytes" to MAX_JSON_BYTES,
        "max_page_size" to MAX_PAGE_SIZE,
    )
}
