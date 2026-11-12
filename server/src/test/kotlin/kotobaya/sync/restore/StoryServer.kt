package kotobaya.sync.restore

import kotobaya.auth.Principal
import kotobaya.auth.TokenKind
import kotobaya.server.Database
import kotobaya.server.SchedulerReplay
import kotobaya.server.StartupSequence
import kotobaya.server.TestPg
import kotobaya.server.Uuid7
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncModule
import kotobaya.sync.fuzz.SimServer
import kotobaya.sync.merge.CoreCardCache
import kotobaya.sync.merge.DuplicateMerge
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * A real server for the protocol stories of SYNC.md 12.5–12.7: its own Postgres database, the
 * production hooks, a settable clock, and backup / restore by copying databases
 * (`CREATE DATABASE … TEMPLATE …`, the moral equivalent of `pg_dump` + restore). [restore] follows
 * the T112 restore script exactly: load the backup, write `restore_pending`, start the server (whose
 * startup finalizes the restore, SYNC.md 6.3).
 */
class StoryServer(startMs: Long, backupsDisabled: Boolean = false) : AutoCloseable {
    /** The instance's `BACKUPS_DISABLED` setting; change it with [restartWith]. */
    var backupsDisabled: Boolean = backupsDisabled
        private set

    val now = AtomicReference(Instant.ofEpochMilli(startMs))
    var url: String = TestPg.freshDatabaseUrl()
        private set
    var db: Database = open(url)
        private set
    val sim = SimServer(module(), now, ::principal)

    init {
        kotobaya.server.Migrations.migrate(db.dataSource)
        startup()
    }

    /**
     * The SYNC.md 6.3 startup: [StartupSequence] (steps 1–2), then step 3 (C-8) under lock 7033001 with this
     * instance's backups setting. (`server/Startup.kt` does not call step 3 yet; see F-3 HANDOFF.)
     */
    private fun startup() {
        StartupSequence(db, schedulerVersion = "unset", replay = SchedulerReplay.NoOp, now = { now.get() }).runOnce()
        db.txBlocking { dsl ->
            dsl.execute("select pg_advisory_xact_lock(?)", kotobaya.server.SYNC_LOCK)
            DurableSeq.raiseOnStartup(dsl, backupsDisabled)
        }
    }

    /** Restarts the server with another backups setting (same database, same epoch). */
    fun restartWith(backupsDisabled: Boolean) {
        this.backupsDisabled = backupsDisabled
        startup()
        sim.module = module()
    }

    var pt: Long
        get() = sim.pt
        set(v) { sim.pt = v }

    private fun open(u: String): Database = Database.connect(u)

    private fun module() = SyncModule(
        db,
        SyncConfig(clock = { now.get() }, cardCache = CoreCardCache(), merge = DuplicateMerge(), backupsDisabled = backupsDisabled),
    )

    private val devices = mutableSetOf<String>()

    private fun principal(id: String): Principal {
        db.txBlocking { it.execute("insert into app.device (id, name, kind) values (?::uuid, 'dev', 'android') on conflict (id) do nothing", id) }
        devices += id
        return Principal(UUID.randomUUID(), "story-device", TokenKind.DEVICE, listOf("read", "write", "review"), UUID.fromString(id), null)
    }

    /** A verified backup of everything committed: a copy of the database; the backup job then writes `durable_seq` (6.3). */
    fun backup(): String {
        db.close()
        val name = copy(url)
        db = open(url)
        sim.module = module()
        val s = db.txBlocking { (it.fetchValue("select coalesce(max(seq), 0) from app.change") as Number).toLong() }
        db.txBlocking {
            it.execute(
                "insert into app.server_state (key, value, updated_at) values ('durable_seq', ?, now()) " +
                    "on conflict (key) do update set value = greatest(app.server_state.value::bigint, excluded.value::bigint)::text, updated_at = now()",
                s.toString(),
            )
        }
        return name
    }

    /** Disk lost: restore [backup] (restore script steps 1–2), then start the server (6.3 finalization). */
    fun restore(backup: String) {
        db.close()
        url = urlOf(copy(urlOf(backup)))
        db = open(url)
        db.txBlocking {
            it.execute(
                "insert into app.server_state (key, value, updated_at) values ('restore_pending', '1', now()) " +
                    "on conflict (key) do update set value = '1', updated_at = now()",
            )
        }
        startup()
        sim.module = module()
    }

    fun serverState(key: String): String? = db.txBlocking { dsl ->
        dsl.fetchOptional("select value from app.server_state where key = ?", key).map { it.get(0, String::class.java) }.orElse(null)
    }

    override fun close() = db.close()

    private fun urlOf(name: String) = url.replace(Regex("/[^/?]+\\?"), "/$name?")

    /** `CREATE DATABASE <new> TEMPLATE <db of srcUrl>`; returns the new database's name. */
    private fun copy(srcUrl: String): String {
        val src = Regex("/([^/?]+)\\?").find(srcUrl)!!.groupValues[1]
        val name = "c" + Uuid7.generate().toString().replace("-", "").takeLast(20)
        DriverManager.getConnection(srcUrl.replace("/$src?", "/postgres?")).use { c ->
            c.createStatement().use { it.execute("create database $name template $src") }
        }
        return name
    }
}
