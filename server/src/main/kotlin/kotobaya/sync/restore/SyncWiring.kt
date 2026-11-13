package kotobaya.sync.restore

import kotobaya.server.Database
import kotobaya.server.StartupSequence
import kotobaya.sync.CoreAutoStatusHook
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncEvents
import kotobaya.sync.merge.CoreCardCache
import kotobaya.sync.merge.DuplicateMerge

/**
 * The production configuration of the sync module, for the wiring ticket (Main.kt is not T202c's).
 *
 * ```
 * val startup = SyncWiring.startup(db)                       // instead of StartupSequence(db)
 * val sync = SyncModule(db, SyncWiring.config(events = SyncEvents { dsl, hw, _ -> jobs.bus.syncChangedInTx(dsl, hw) }))
 * ```
 * Both use the same [CoreCardCache], so the startup replay and the per-op cache maintenance run the
 * same engine and version.
 */
object SyncWiring {
    private val cache = CoreCardCache()

    /** [SyncConfig] with the real merge policy (SYNC.md 8.1), the core card cache (9) and the backups setting (6.3). */
    fun config(events: SyncEvents = SyncEvents.NoOp, env: Map<String, String> = System.getenv()): SyncConfig = SyncConfig(
        cardCache = cache,
        autoStatus = CoreAutoStatusHook(),
        merge = DuplicateMerge(),
        events = events,
        backupsDisabled = DurableSeq.backupsDisabled(env),
    )

    /** The SYNC.md 6.3 / 10.5 startup sequence with the core's scheduler version and replay. */
    fun startup(db: Database, env: Map<String, String> = System.getenv()): StartupSequence {
        val replay = CoreSchedulerReplay(cache)
        return StartupSequence(
            db, schedulerVersion = replay.schedulerVersion, replay = replay, backupsDisabled = DurableSeq.backupsDisabled(env),
        )
    }
}
