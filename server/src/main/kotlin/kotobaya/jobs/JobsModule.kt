package kotobaya.jobs

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import io.ktor.utils.io.writeStringUtf8
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.ServerStateKeys
import kotobaya.server.TokenInfo
import kotobaya.server.TokenKey
import kotobaya.server.formatInstant
import kotobaya.server.json
import kotobaya.server.respondJson
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Jobs API, retention and the `/events` stream.
 *
 * Wiring (Main): `val jobs = JobsModule(db, config.databaseUrl)`, pass `handlers = jobs.handlers` in `AppDeps`
 * and call `jobs.start(scope)` once at startup (`jobs.close()` on shutdown). Other tickets enqueue through [JobRepo.enqueue]
 * and publish events through [bus].
 *
 * @param credentialValid asked every [guardIntervalMs] for each open stream; false ends the stream. T105b plugs the
 *   revocation/expiry check in here; it can also call `hub.closeToken(id)` for an immediate close.
 */
class JobsModule(
    private val db: Database,
    private val jdbcUrl: String,
    val hub: EventHub = EventHub(),
    val credentialValid: suspend (TokenInfo?) -> Boolean = { true },
    private val pingIntervalMs: Long = 25_000,
    private val guardIntervalMs: Long = 5_000,
    private val pollMs: Long = 250,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger("kotobaya.jobs")
    val bus = EventBus(db)
    private val watcher = JobWatcher(db, hub, pollMs)
    private val tasks = mutableListOf<Job>()

    val handlers: Map<String, OperationHandler> = mapOf(
        "listJobs" to { listJobs() },
        "getJob" to { getJob() },
        "cancelJob" to { cancelJob() },
        "streamEvents" to { streamEvents() },
    )

    /** Starts the LISTEN connection, the job watcher, the `sync.changed` coalescing tick and the daily prune. */
    fun start(scope: CoroutineScope) {
        tasks += PgListener(jdbcUrl, hub, watcher::poke).start(scope)
        tasks += watcher.start(scope)
        tasks += scope.launch {
            while (isActive) {
                hub.flushSync()
                delay(250)
            }
        }
        tasks += scope.launch {
            while (isActive) {
                try {
                    val n = db.tx { JobRepo.prune(it) }
                    if (n > 0) log.info("pruned {} finished jobs", n)
                } catch (e: Exception) {
                    log.warn("job prune failed: {}", e.message)
                }
                delay(24L * 3600 * 1000)
            }
        }
    }

    override fun close() {
        tasks.forEach { it.cancel() }
    }

    // ------------------------------------------------------------------ jobs

    private fun invalidQuery(detail: String) =
        ApiException(HttpStatusCode.UnprocessableEntity, "invalid_query", "Invalid query", detail)

    private fun ApplicationCall.jobId(): UUID =
        runCatching { UUID.fromString(parameters["job_id"]) }.getOrNull() ?: throw Problems.notFound("No such job.")

    private suspend fun ApplicationCall.listJobs() {
        val q = request.queryParameters
        val kind = q["kind"]?.also { if (it !in API_JOB_KINDS) throw invalidQuery("Unknown kind '$it'.") }
        val statuses = q["status"]?.split(',')?.map { it.trim() }?.distinct()?.also { list ->
            list.firstOrNull { it !in JOB_STATUSES }?.let { throw invalidQuery("Unknown status '$it'.") }
        }
        val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..500 } ?: throw invalidQuery("limit must be 1 to 500.") } ?: 50
        val cursor = q["cursor"]?.let { JobRepo.decodeCursor(it) ?: throw invalidQuery("Malformed cursor.") }
        val page = db.tx { JobRepo.list(it, kind, statuses, limit, cursor) }
        respondJson(mapOf("items" to page.items.map { it.toApi() }, "next_cursor" to page.nextCursor))
    }

    private suspend fun ApplicationCall.getJob() {
        val id = jobId()
        val job = db.tx { JobRepo.get(it, id) } ?: throw Problems.notFound("No such job.")
        respondJson(job.toApi())
    }

    private suspend fun ApplicationCall.cancelJob() {
        val id = jobId()
        val job = db.tx { JobRepo.cancel(it, id) }
        watcher.poke()
        respondJson(job.toApi())
    }

    // ------------------------------------------------------------------ events

    private suspend fun ApplicationCall.streamEvents() {
        val types: Set<String>? = request.queryParameters["types"]?.split(',')?.map { it.trim() }?.toSet()?.also { set ->
            set.firstOrNull { it !in EVENT_TYPES }?.let { throw invalidQuery("Unknown event type '$it'.") }
        }
        val token = attributes.getOrNull(TokenKey)
        val (seq, epoch) = db.tx { dsl ->
            val s = dsl.fetchValue("select coalesce(max(seq), 0) from app.change") as Long
            val e = dsl.fetchOptional("select value from app.server_state where key = ?", ServerStateKeys.LOG_EPOCH)
                .map { it.get(0, String::class.java) }.orElse(null)
            s to e
        }
        epoch ?: throw Problems.unavailable("The sync log epoch is not initialised yet.")

        // Subscribe before `hello` is computed so nothing between the two is lost (there is no replay).
        val sub = hub.subscribe(types?.minus("hello"), token?.id)
        response.cacheControl(CacheControl.NoCache(null))
        response.header("X-Accel-Buffering", "no")
        try {
            respondBytesWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                var n = 0
                suspend fun write(text: String) {
                    writeStringUtf8(text)
                    flush()
                }
                suspend fun frame(f: Frame) = write("id: ${++n}\nevent: ${f.type}\ndata: ${f.json}\n\n")

                val now = formatInstant(Instant.now())
                frame(
                    Frame(
                        "hello",
                        json.writeValueAsString(
                            mapOf(
                                "type" to "hello", "at" to now,
                                "data" to mapOf("high_water_seq" to seq, "log_epoch" to epoch, "server_time" to now),
                            ),
                        ),
                    ),
                )
                val tick = minOf(pingIntervalMs, guardIntervalMs)
                var lastWrite = System.currentTimeMillis()
                var lastGuard = lastWrite
                while (true) {
                    val f = try {
                        withTimeoutOrNull(tick) { sub.channel.receive() }
                    } catch (_: ClosedReceiveChannelException) {
                        break // dropped: slow consumer, or the credential was revoked
                    }
                    val t = System.currentTimeMillis()
                    if (f != null) {
                        frame(f)
                        lastWrite = t
                    } else if (t - lastWrite >= pingIntervalMs) {
                        write(": ping\n\n")
                        lastWrite = t
                    }
                    if (t - lastGuard >= guardIntervalMs) {
                        lastGuard = t
                        if (!credentialValid(token)) break
                    }
                }
            }
        } catch (e: io.ktor.utils.io.ClosedWriteChannelException) {
            streamLog.debug("events stream: client disconnected")
        } catch (e: java.io.IOException) {
            // Netty reports a peer that went away mid-write as "Cannot write to a channel" (ChannelWriteException).
            if (e.message?.contains("Cannot write to a channel") == true || e.javaClass.simpleName == "ChannelWriteException") {
                streamLog.debug("events stream: client disconnected ({})", e.message)
            } else {
                throw e
            }
        } finally {
            hub.unsubscribe(sub)
        }
    }
}

private val streamLog = org.slf4j.LoggerFactory.getLogger("kotobaya.events.stream")
