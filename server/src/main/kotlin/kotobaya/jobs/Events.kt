package kotobaya.jobs

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotobaya.server.Database
import kotobaya.server.formatInstant
import kotobaya.server.json
import org.jooq.DSLContext
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet

/** `EventType` of the API. `hello` is produced per connection by the stream itself. */
val EVENT_TYPES: Set<String> = setOf("hello", "sync.changed", "note.created", "job.updated", "device.paired", "device.revoked")

/** Slow consumers are disconnected when more than this many events are buffered for them (OpenAPI `/events`). */
const val MAX_BUFFERED_EVENTS = 1000

/**
 * Postgres NOTIFY channel that fans events out between API processes (and the worker).
 *
 * Payload: the `Event` JSON (`{"type","at","data"}`, at most ~8000 bytes). Every API process LISTENs on one dedicated
 * non-pooled connection and delivers what it receives to its own SSE subscribers. Anything may `pg_notify` here:
 * - `sync.changed`, `note.created`, `device.paired`, `device.revoked`: published by their owners through [EventBus].
 * - `job.updated`: only a hint ("poll now"); the job's data always comes from the row (see [JobWatcher]), so a writer
 *   that cannot notify (the worker, a SQL console) still produces events within one poll interval.
 */
const val EVENTS_CHANNEL = "kotobaya_events"

/** One event frame ready to be written to a stream. */
class Frame(val type: String, val json: String)

/** One SSE connection's buffer. */
class Subscription internal constructor(
    val types: Set<String>?,
    val tokenId: String?,
    capacity: Int,
) {
    val channel: Channel<Frame> = Channel(capacity)

    /** True once the hub dropped this subscriber for being too slow (or closed it for a revoked credential). */
    @Volatile var dropped = false
        private set

    internal fun accepts(type: String) = types == null || type in types

    /** False when the buffer is full. */
    internal fun offer(f: Frame): Boolean = channel.trySend(f).isSuccess

    internal fun drop() {
        dropped = true
        channel.close()
    }
}

/** Process-local fan-out to SSE subscribers. Pure in-memory; fed by [PgListener] and [JobWatcher]. */
class EventHub(private val capacity: Int = MAX_BUFFERED_EVENTS, private val clock: () -> Long = System::currentTimeMillis) {
    private val subs = CopyOnWriteArraySet<Subscription>()

    @Volatile private var pendingSync: Long? = null
    @Volatile private var lastSyncMs = 0L

    val subscriberCount: Int get() = subs.size

    fun subscribe(types: Set<String>?, tokenId: String? = null): Subscription =
        Subscription(types, tokenId, capacity).also { subs += it }

    fun unsubscribe(s: Subscription) {
        subs -= s
        s.channel.close()
    }

    /** Disconnect every stream of a revoked token (T105b's revocation hook calls this). */
    fun closeToken(tokenId: String) {
        subs.filter { it.tokenId == tokenId }.forEach { it.drop(); subs -= it }
    }

    /** Deliver one event to the matching subscribers; a full buffer disconnects that subscriber. */
    fun dispatch(type: String, at: Instant, data: Any) {
        val frame = Frame(type, json.writeValueAsString(mapOf("type" to type, "at" to formatInstant(at), "data" to data)))
        for (s in subs) {
            if (s.accepts(type) && !s.offer(frame)) {
                s.drop()
                subs -= s
            }
        }
    }

    /** `sync.changed` is coalesced to at most one per second carrying the newest sequence. */
    @Synchronized fun offerSyncChanged(highWaterSeq: Long) {
        pendingSync = maxOf(pendingSync ?: 0L, highWaterSeq)
        flushSync()
    }

    /** Called by the tick loop and on every offer. */
    @Synchronized fun flushSync() {
        val seq = pendingSync ?: return
        val now = clock()
        if (now - lastSyncMs < 1000) return
        pendingSync = null
        lastSyncMs = now
        dispatch("sync.changed", Instant.ofEpochMilli(now), mapOf("high_water_seq" to seq))
    }
}

/** Publishes events to every API process through NOTIFY. Owners (T105b devices, T202 sync, note op apply) call this. */
class EventBus(private val db: Database) {
    /** Use inside the transaction that made the change so the event is only sent on commit. */
    fun publishInTx(dsl: DSLContext, type: String, data: Any, at: Instant = Instant.now()) {
        require(type in EVENT_TYPES && type != "hello") { "cannot publish $type" }
        val payload = json.writeValueAsString(mapOf("type" to type, "at" to formatInstant(at), "data" to data))
        dsl.fetchValue("select pg_notify(?, ?)", EVENTS_CHANNEL, payload)
    }

    suspend fun publish(type: String, data: Any) = db.tx { publishInTx(it, type, data) }

    /** The `sync.changed` emitter hook; T202 calls it after logging ops. */
    fun syncChangedInTx(dsl: DSLContext, highWaterSeq: Long) = publishInTx(dsl, "sync.changed", mapOf("high_water_seq" to highWaterSeq))
}

/** LISTEN on a dedicated, non-pooled connection built from the same JDBC URL; reconnects with a pause on failure. */
class PgListener(
    private val jdbcUrl: String,
    private val hub: EventHub,
    private val onJobHint: () -> Unit,
) {
    private val log = LoggerFactory.getLogger("kotobaya.events")

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                DriverManager.getConnection(jdbcUrl).use { c ->
                    c.createStatement().use { it.execute("LISTEN $EVENTS_CHANNEL") }
                    onJobHint() // anything missed while disconnected is picked up by the watcher
                    val pg = c.unwrap(PGConnection::class.java)
                    while (isActive) {
                        pg.getNotifications(500)?.forEach { handle(it.parameter) }
                    }
                }
            } catch (e: Exception) {
                if (!isActive) break
                log.warn("event listener lost its connection", e)
                delay(1000)
            }
        }
    }

    internal fun handle(payload: String) {
        val node: JsonNode = runCatching { json.readTree(payload) }.getOrNull() ?: return
        val type = node.path("type").asText()
        when (type) {
            "job.updated" -> onJobHint()
            "sync.changed" -> hub.offerSyncChanged(node.path("data").path("high_water_seq").asLong(0))
            "note.created", "device.paired", "device.revoked" -> hub.dispatch(
                type,
                runCatching { Instant.parse(node.path("at").asText()) }.getOrDefault(Instant.now()),
                node.path("data"),
            )
        }
    }
}

/**
 * Turns `app.job` changes into `job.updated` events by polling the active (and just finished) jobs. Polling is the
 * source of truth so that the worker needs no NOTIFY; a hint ([poke]) only shortens the wait. Rate: at most one event
 * per job per second while it is queued or running; a terminal state is always sent at once; a new job is sent at once.
 */
class JobWatcher(
    private val db: Database,
    private val hub: EventHub,
    private val pollMs: Long = 250,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger("kotobaya.events")
    private val poke = Channel<Unit>(Channel.CONFLATED)

    private data class Snap(val status: String, val progress: Double, val message: String?, val error: String?, val attempts: Int)
    private class Seen(var snap: Snap, var lastEmitMs: Long)

    private val seen = HashMap<java.util.UUID, Seen>()
    private var baselined = false

    fun poke() {
        poke.trySend(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                pollOnce()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.error("job watcher poll failed", e)
            }
            withTimeoutOrNull(pollMs) { poke.receive() }
        }
    }

    internal suspend fun pollOnce() {
        val rows = withContext(Dispatchers.IO) {
            db.tx { dsl ->
                dsl.fetch(
                    "select ${JobRepo.COLUMNS} from app.job where kind in (${API_JOB_KINDS.joinToString(",") { "'$it'" }}) " +
                        "and (status in ('queued','running') or finished_at > now() - interval '10 seconds')",
                ).map(JobRepo::map)
            }
        }
        val now = clock()
        val present = HashSet<java.util.UUID>()
        for (j in rows) {
            present += j.id
            val snap = Snap(j.status, j.progress, j.progressMessage, j.error, j.attempts)
            val prev = seen[j.id]
            if (prev == null) {
                seen[j.id] = Seen(snap, if (baselined) now else 0L)
                if (baselined) {
                    emit(j)
                    if (j.terminal) JobResultLog.log(j)
                }
            } else if (prev.snap != snap && (j.terminal || now - prev.lastEmitMs >= 1000)) {
                // OBS-1s: the transition into a terminal state is seen exactly once here; log the job's result.
                if (j.terminal && prev.snap.status != j.status) JobResultLog.log(j)
                prev.snap = snap
                prev.lastEmitMs = now
                emit(j)
            }
        }
        seen.keys.retainAll(present)
        baselined = true
    }

    private fun emit(j: JobRow) = hub.dispatch("job.updated", Instant.now(), j.toApi())
}
