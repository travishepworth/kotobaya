package kotobaya.sync.client.apply

import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.StoreTransaction

/**
 * The `core_scheduler_version` startup replay of SYNC.md 10.5 (C-1). Run [run] at app start, before
 * any sync, review or other read of a card cache.
 *
 * If the stored `sync_state.core_scheduler_version` is absent or differs from the running engine's
 * `schedulerVersion`: in **one** transaction, fully replay every maintained card's cache, then store
 * the running version. A crash before commit leaves the key unchanged and the next start replays
 * again (idempotent). Mints no op. A refused replay set gives that card the refused cache and the
 * version is still stored (9.4 item 3).
 *
 * [DeviceOpApplier] calls [ensure] at the start of every apply as well, so nothing is ever folded
 * into a cache computed by another core version even if the app forgot [run].
 */
public class StartupReplay(
    private val cache: DeviceCardCache = DeviceCardCache(),
) {
    /** Runs the check and, if needed, the replay in one transaction of [store]. Returns whether a replay ran. */
    public fun <L : LearningTables> run(store: DeviceStore<L>): Boolean = store.transaction { ensure(it) }

    /** The same inside the caller's transaction [tx]. Returns whether a replay ran. */
    public fun ensure(tx: StoreTransaction<out LearningTables>): Boolean {
        val running = cache.engine.schedulerVersion
        if (tx.syncState.get(CORE_SCHEDULER_VERSION) == running) return false
        cache.replayAll(tx.learning)
        tx.syncState.put(CORE_SCHEDULER_VERSION, running)
        return true
    }

    /** Keys. */
    public companion object {
        /** `sync_state` key of SYNC.md 10.5: the core scheduler version every cache was last fully replayed with. */
        public const val CORE_SCHEDULER_VERSION: String = "core_scheduler_version"
    }
}
