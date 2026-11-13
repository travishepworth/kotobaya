package kotobaya.acceptance.offline

import kotobaya.auth.Principal
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.server.Database
import kotobaya.server.Limits
import kotobaya.server.Migrations
import kotobaya.server.StartupSequence
import kotobaya.server.json
import kotobaya.sync.CoreAutoStatusHook
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.PushOutcome
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncModule
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.protocol.TransportResponse
import kotobaya.sync.merge.CoreCardCache
import kotobaya.sync.merge.DuplicateMerge
import kotobaya.sync.restore.CoreSchedulerReplay
import org.testcontainers.containers.PostgreSQLContainer
import kotobaya.sync.client.store.UuidV7Source
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * One Postgres container for the whole test JVM, a migrated template database, and one database per
 * run copied from it (`CREATE DATABASE … TEMPLATE`, cheaper than migrating or truncating each time).
 */
object Pg {
    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17")
            .withEnv("POSTGRES_INITDB_ARGS", "--locale=C.UTF-8")
            .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
            .also { it.start() }
    }
    private val counter = AtomicInteger()

    fun url(name: String): String {
        val base = container.jdbcUrl.substringBeforeLast('/')
        return "$base/$name?user=${container.username}&password=${container.password}"
    }

    private fun admin(sql: String) {
        DriverManager.getConnection(url("postgres")).use { c -> c.createStatement().use { it.execute(sql) } }
    }

    /** The migrated, never-served template. */
    val template: String by lazy {
        val name = "kotobaya_template"
        admin("create database $name")
        Database.connect(url(name)).use { Migrations.migrate(it.dataSource) }
        name
    }

    /** A new database copied from [src] (no connection to [src] may be open). */
    fun copy(src: String): String {
        val name = "r${counter.incrementAndGet()}_${System.nanoTime()}"
        admin("create database $name template $src")
        return name
    }

    fun drop(name: String) = admin("drop database if exists $name with (force)")
}

/**
 * The real server: `:server`'s [SyncModule] (push, pull, op apply, the [DuplicateMerge] policy, the
 * [CoreCardCache], the core auto-status hook) on its own Postgres database, with a settable clock.
 * Backups are database copies; [restoreLatestBackup] follows the T112 restore script (load the backup,
 * write `restore_pending`, start the server, whose [StartupSequence] finalizes the restore, SYNC.md 6.3).
 */
class PgSimServer(startMs: Long, private val pageSize: Int = 500, seed: Int = 1) : SimServer {
    override val kind: String = "postgres"
    private val now = AtomicReference(Instant.ofEpochMilli(startMs))
    private val cache = CoreCardCache()
    private var dbName: String = Pg.copy(Pg.template)
    private var db: Database = Database.connect(Pg.url(dbName))
    private var module: SyncModule
    private val backups = ArrayList<String>()
    private val devices = HashSet<String>()
    /** Ids of cards the merge creates on a survivor: seeded, so a run is reproducible (op ids stay random). */
    private val cardIds = UuidV7Source(Random(seed))
    override val scriptedCardIds = ArrayDeque<String>()

    init {
        startup()
        module = module()
    }

    override var nowMs: Long
        get() = now.get().toEpochMilli()
        set(v) = now.set(Instant.ofEpochMilli(v))

    private fun startup() {
        val replay = CoreSchedulerReplay(cache)
        StartupSequence(db, schedulerVersion = replay.schedulerVersion, replay = replay, now = { now.get() }, backupsDisabled = false).runOnce()
    }

    private fun module() = SyncModule(
        db,
        SyncConfig(clock = { now.get() }, cardCache = cache, autoStatus = CoreAutoStatusHook(), merge = DuplicateMerge { scriptedCardIds.removeFirstOrNull() ?: cardIds.newOpId(now.get().toEpochMilli()) }, backupsDisabled = false),
    )

    private fun principal(id: String): Principal {
        if (devices.add(id)) {
            db.txBlocking { it.execute("insert into app.device (id, name, kind) values (?::uuid, 'sim', 'android') on conflict (id) do nothing", id) }
        }
        return Principal(UUID.randomUUID(), "sim-device", TokenKind.DEVICE, listOf("read", "write", "review"), UUID.fromString(id), null)
    }

    override fun push(deviceId: String, body: String): TransportResponse {
        if (body.toByteArray(Charsets.UTF_8).size > Limits.MAX_PUSH_BYTES) return TransportResponse.HttpError(413, "payload_too_large")
        return when (val o = module.push.push(body, principal(deviceId))) {
            is PushOutcome.Ok -> TransportResponse.Ok(json.writeValueAsString(o.body))
            is PushOutcome.BadRequest -> TransportResponse.HttpError(400, o.message)
        }
    }

    override fun pull(since: Long): TransportResponse = TransportResponse.Ok(module.pull.pull(since, pageSize, null).text)

    override fun backup() {
        db.close()
        val name = Pg.copy(dbName)
        db = Database.connect(Pg.url(dbName))
        module = module()
        backups += name
        // backup job step 3: the copy is verified; durable_seq = its S, monotonically
        db.txBlocking {
            it.execute(
                "insert into app.server_state (key, value, updated_at) values ('durable_seq', (select coalesce(max(seq), 0) from app.change)::text, now()) " +
                    "on conflict (key) do update set value = greatest(app.server_state.value::bigint, excluded.value::bigint)::text, updated_at = now()",
            )
        }
    }

    override fun restoreLatestBackup() {
        val backup = backups.lastOrNull() ?: error("no backup to restore")
        db.close()
        val lost = dbName
        dbName = Pg.copy(backup)
        Pg.drop(lost)
        db = Database.connect(Pg.url(dbName))
        db.txBlocking {
            it.execute(
                "insert into app.server_state (key, value, updated_at) values ('restore_pending', '1', now()) " +
                    "on conflict (key) do update set value = '1', updated_at = now()",
            )
        }
        startup()
        module = module()
        devices.clear()
    }

    override fun emit(type: OpType, payload: JsonObject): Op = module.opApply.transactionBlocking(null) { it.emit(type, payload) }.op

    override fun log(): List<Op> = pullAll(this)

    override val logEpoch: String
        get() = db.txBlocking { it.fetchValue("select value from app.server_state where key = 'log_epoch'") as String }

    override fun projection(reference: Projection): Projection = db.txBlocking { dsl ->
        COMPARED.associate { table ->
            val columns = reference[table.sqlName].orEmpty().values.flatMapTo(HashSet()) { it.keys }
            val rows = dsl.fetch("select to_jsonb(t)::text from app.${table.sqlName} t").map { rec ->
                val raw = (DefaultCanonicalJson.decode(rec.get(0, String::class.java)).value as JsonObject).members
                deviceRow(table, raw, columns)
            }
            table.sqlName to projectRows(table, rows)
        }
    }

    /** One Postgres row in device representation (SCHEMA.md 5.1): instants as epoch ms; only device columns. */
    private fun deviceRow(table: LearningTable, raw: Map<String, JsonValue>, columns: Set<String>): Row {
        val keep = if (columns.isEmpty()) raw.keys else columns intersect raw.keys
        val out = LinkedHashMap<String, JsonValue>()
        for (c in raw.keys) {
            if (c !in keep && c !in table.keyColumns) continue
            val v = raw.getValue(c)
            out[c] = if (v is JsonString && TIMESTAMP.matches(v.value)) JsonNumber(OffsetDateTime.parse(v.value).toInstant().toEpochMilli().toDouble()) else v
        }
        return out
    }

    override fun close() {
        db.close()
        Pg.drop(dbName)
        backups.forEach(Pg::drop)
    }

    private companion object {
        val TIMESTAMP = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?([+-]\d\d:\d\d|Z)$""")
    }
}
