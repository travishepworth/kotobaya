package kotobaya.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotobaya.sync.restore.CoreSchedulerReplay
import net.logstash.logback.argument.StructuredArguments.kv
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/** `app.server_state` keys (contracts/db/SCHEMA.md 4.4, SYNC.md 6.3 and 10.5). */
object ServerStateKeys {
    const val LOG_EPOCH = "log_epoch"
    const val DURABLE_SEQ = "durable_seq"
    const val RESTORE_PENDING = "restore_pending"
    const val CORE_SCHEDULER_VERSION = "core_scheduler_version"

    /**
     * The server's persisted HLC `last` (full HLC string, server device id). Reserved here (FOLLOWUPS freeze A/B):
     * T202 mints/observes against it; restore finalization already raises it (SYNC.md 6.2 step 3).
     */
    const val HLC_LAST = "hlc_last"
}

/** Advisory lock every op-apply path holds (SYNC.md 5.2); startup takes it too. */
const val SYNC_LOCK = 7033001L

const val SERVER_DEVICE_ID = "00000000-0000-7000-8000-000000000001"

/**
 * Extension point for T203: full replay of every maintained card cache (SYNC.md 10.5 server step 3).
 * Called inside the startup transaction that holds [SYNC_LOCK]; the version row is upserted in the
 * same transaction after it returns, so a throw leaves the row unchanged.
 */
fun interface SchedulerReplay {
    fun replayAll(tx: DSLContext)

    companion object {
        val NoOp = SchedulerReplay { }
    }
}

class StartupState {
    @Volatile var done: Boolean = false
    @Volatile var lastError: String? = null
}

/**
 * Startup sequence of SYNC.md 6.3 then 10.5. The instance is not ready until both have committed.
 *  1. One transaction under [SYNC_LOCK]: finalize a pending restore, else create `log_epoch` if absent.
 *  2. Then the `core_scheduler_version` check (own transaction, same lock).
 */
class StartupSequence(
    private val db: Database,
    private val schedulerVersion: String = DEFAULT_REPLAY.schedulerVersion,
    private val replay: SchedulerReplay = DEFAULT_REPLAY,
    private val now: () -> Instant = Instant::now,
    /** `BACKUPS_DISABLED` (SYNC.md 6.2): startup step 3 raises `durable_seq` when true. */
    private val backupsDisabled: Boolean = false,
    val state: StartupState = StartupState(),
) {
    private val log = LoggerFactory.getLogger(StartupSequence::class.java)

    fun runOnce() {
        finalizeRestoreOrCreateEpoch()
        checkSchedulerVersion()
    }

    /** Retries with backoff until it succeeds (Postgres may come up after the server); then marks ready. */
    suspend fun runUntilDone() {
        var wait = 1_000L
        while (true) {
            try {
                withContext(Dispatchers.IO) { runOnce() }
                state.lastError = null
                state.done = true
                log.info("startup sequence finished")
                return
            } catch (e: Exception) {
                state.lastError = e.message ?: e.javaClass.simpleName
                log.warn("startup sequence failed, retrying", kv("retry_in_ms", wait), e)
                delay(wait)
                wait = minOf(wait * 2, 15_000L)
            }
        }
    }

    private fun <T> tx(block: (DSLContext) -> T): T = db.txBlocking { dsl ->
        dsl.execute("select pg_advisory_xact_lock(?)", SYNC_LOCK)
        block(dsl)
    }

    internal fun finalizeRestoreOrCreateEpoch() = tx { dsl ->
        // Re-read under the lock: several instances starting together finalize once.
        val pending = dsl.fetchOptional("select value from app.server_state where key = ?", ServerStateKeys.RESTORE_PENDING)
        if (pending.isPresent) {
            val epoch = Uuid7.generate()
            val maxSeq = dsl.fetchValue("select coalesce(max(seq), 0) from app.change") as Long
            val hlc = raisedHlc(dsl)
            upsert(dsl, ServerStateKeys.LOG_EPOCH, epoch.toString())
            upsert(dsl, ServerStateKeys.DURABLE_SEQ, maxSeq.toString())
            upsert(dsl, ServerStateKeys.HLC_LAST, hlc)
            dsl.execute("delete from app.server_state where key = ?", ServerStateKeys.RESTORE_PENDING)
            log.warn(
                "restore finalized: new log epoch",
                kv("log_epoch", epoch.toString()), kv("durable_seq", maxSeq), kv("hlc_last", hlc),
            )
        } else if (dsl.fetchOptional("select 1 from app.server_state where key = ?", ServerStateKeys.LOG_EPOCH).isEmpty) {
            val epoch = Uuid7.generate()
            upsert(dsl, ServerStateKeys.LOG_EPOCH, epoch.toString())
            log.info("created log epoch", kv("log_epoch", epoch.toString()))
        }
        // SYNC.md 6.3 step 3 (C-8): in every case, with backups disabled, raise durable_seq to max(seq).
        kotobaya.sync.restore.DurableSeq.raiseOnStartup(dsl, backupsDisabled)
    }

    internal fun checkSchedulerVersion() = tx { dsl ->
        val stored = dsl.fetchOptional(
            "select value from app.server_state where key = ?", ServerStateKeys.CORE_SCHEDULER_VERSION,
        ).map { it.get(0, String::class.java) }.orElse(null)
        if (stored != schedulerVersion) {
            log.info("core scheduler version changed: full replay", kv("from", stored), kv("to", schedulerVersion))
            replay.replayAll(dsl) // extension point for T203
            upsert(dsl, ServerStateKeys.CORE_SCHEDULER_VERSION, schedulerVersion)
        }
    }

    /** SYNC.md 6.2 step 3: greatest of restored `hlc_last`, every `change.hlc`, and `(now + 24 h, 0)`; server device id. */
    private fun raisedHlc(dsl: DSLContext): String {
        val candidates = mutableListOf<Pair<Long, Int>>()
        candidates += now().plus(Duration.ofHours(24)).toEpochMilli() to 0
        dsl.fetchOptional("select value from app.server_state where key = ?", ServerStateKeys.HLC_LAST)
            .ifPresent { r -> parseHlc(r.get(0, String::class.java))?.let(candidates::add) }
        dsl.fetchOptional("select hlc::text from app.change order by hlc collate \"C\" desc limit 1")
            .ifPresent { r -> parseHlc(r.get(0, String::class.java))?.let(candidates::add) }
        val best = candidates.maxWith(compareBy({ it.first }, { it.second }))
        return formatHlc(best.first, best.second)
    }

    private fun upsert(dsl: DSLContext, key: String, value: String) {
        dsl.execute(
            "insert into app.server_state (key, value, updated_at) values (?, ?, now()) " +
                "on conflict (key) do update set value = excluded.value, updated_at = now()",
            key, value,
        )
    }

    companion object {
        /** The core's version and replay: the defaults, so a caller cannot get a real version with a no-op replay. */
        private val DEFAULT_REPLAY = CoreSchedulerReplay()

        /** `ms`(12 hex) `-` `ctr`(4 hex) `-` server device id (SYNC.md 3.1). */
        fun formatHlc(ms: Long, ctr: Int): String = "%012x-%04x-%s".format(ms, ctr, SERVER_DEVICE_ID)

        fun parseHlc(s: String): Pair<Long, Int>? {
            if (s.length < 17 || s[12] != '-') return null
            val ms = s.substring(0, 12).toLongOrNull(16) ?: return null
            val ctr = s.substring(13, 17).toIntOrNull(16) ?: return null
            return ms to ctr
        }
    }
}
