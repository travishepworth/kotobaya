package kotobaya.sync.restore

import kotobaya.server.SchedulerReplay
import kotobaya.sync.merge.CoreCardCache
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

/**
 * The startup replay of SYNC.md 10.5 (server): [replayAll] fully replays every maintained card cache
 * (live, not a merge loser) with the core's replay engine. `StartupSequence.checkSchedulerVersion`
 * calls it inside its transaction holding lock 7033001, only when the stored
 * `core_scheduler_version` differs from [schedulerVersion], and upserts the version in the same
 * transaction; the instance is not ready (`/readyz`, `StartupCheck`) until that has committed.
 *
 * A card the engine refuses gets the refused cache of SYNC.md 9.4 and is logged at ERROR (see
 * [CoreCardCache]); the replay and the new `core_scheduler_version` still commit, so one bad card cannot
 * keep the server unready.
 */
class CoreSchedulerReplay(private val cache: CoreCardCache = CoreCardCache()) : SchedulerReplay {
    private val log = LoggerFactory.getLogger(CoreSchedulerReplay::class.java)

    /** The running core's version: `ReplayEngine.schedulerVersion` (T203). Replaces `PLACEHOLDER_SCHEDULER_VERSION`. */
    val schedulerVersion: String get() = cache.schedulerVersion

    override fun replayAll(tx: DSLContext) {
        val n = cache.replayAll(tx)
        log.info("startup replay: {} card caches replayed with scheduler {}", n, schedulerVersion)
    }
}
