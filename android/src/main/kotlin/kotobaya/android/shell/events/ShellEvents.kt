package kotobaya.android.shell.events

import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotobaya.android.ankiapi.provider.AnkiNote
import kotobaya.android.ankiapi.provider.AnkiStore
import kotobaya.android.ankiapi.provider.NewNote
import kotobaya.android.ankiapi.store.AddOutcome
import kotobaya.android.ankiapi.store.TakobotoAnkiStore
import kotobaya.android.shell.cache.CacheHooks
import kotobaya.android.shell.cache.CacheUpdate
import kotobaya.android.shell.cache.SqlResponseCacheStore
import kotobaya.android.shell.cache.StaleMarks
import kotobaya.android.shell.web.BridgeCore
import kotobaya.android.shell.web.BridgeMessage
import kotobaya.android.sync.SyncOutcome
import kotobaya.android.sync.SyncStatusView
import kotobaya.contracts.sync.JsonBool
import kotobaya.db.app.AppDatabase
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.protocol.CycleResult
import org.koin.core.context.GlobalContext

/**
 * The shell's native -> web events (SHELL.md 2.6), except `hello` (the bridge's, in answer to `ready`) and `shell.dict` (A7).
 * Delivery is best effort. `sync.changed` and `shell.sync` are coalesced to at most one per [intervalMs] with the last
 * state always delivered; `note.created` and `shell.connectivity` go out at once.
 *
 * Producers call the `on*` functions: the sync coordinator's outcome ([onSyncOutcome]), the local write commit
 * ([onLocalWrite], rule L-2), the note stores ([EventingAnkiStore]), `NetworkMonitor` ([onConnectivity]) and the status
 * flow ([watch]). Nothing here reads the replica except through [syncChanged] / [syncStatus] at emit time.
 */
class ShellEvents(
    private val scope: CoroutineScope,
    /** `BridgeTransport.send`; a dropped event (no page) is fine. */
    private val send: (BridgeMessage.Event) -> Unit,
    /** `ShellEventSyncChanged` at emit time: the pull cursor and `pending_ops`. */
    private val syncChanged: () -> Pair<Long, Int>,
    /** `ShellSyncStatus` at emit time (`SyncStatusData.toJson`). */
    private val syncStatus: () -> JsonObject,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    /** LF-A2b: the hooks used while [cacheHooks] is unset (no WebView yet): stale marks straight on the DB. */
    private val fallbackHooks: () -> CacheHooks? = ::dbStaleMarks,
) {
    private val changed = Coalescer { emitSyncChanged() }
    private val status = Coalescer { emitSyncStatus() }
    private val lock = Any()
    private var lastStatus: String? = null
    private var lastOnline: Boolean? = null
    private val cacheUpdates = HashMap<String, Coalescer>()
    private val cachePayloads = HashMap<String, CacheUpdate>()

    /** The response cache (LF-A2, SHELL.md K-4): invalidated where `sync.changed` is due, warmed after a good cycle. */
    @Volatile
    var cacheHooks: CacheHooks? = null

    /**
     * After a sync cycle: `sync.changed` when it applied ops (a pull page, a rebuild), else nothing. The response cache
     * marks entries stale on the same occasions (all of them after a rebuild or log reset) and warms after a completed cycle.
     */
    fun onSyncOutcome(outcome: SyncOutcome) {
        val result = (outcome as? SyncOutcome.Ran)?.result as? CycleResult.Completed
        if (result != null) {
            if (result.pulledOps > 0 || result.pullAdvanced) changed.request()
            status.request()
        }
        applyCacheMarks(outcome, hooks())
    }

    /** After a committed local write (`LocalWrites` after-commit hook). */
    fun onLocalWrite() {
        changed.request()
        status.request()
        runCatching { hooks()?.onReplicaChanged() }
    }

    private fun hooks(): CacheHooks? = cacheHooks ?: runCatching { fallbackHooks() }.getOrNull()

    /**
     * `cache.updated` (C-31, SHELL.md 2.6): at most one per cache key per [intervalMs]; the last payload of a key is
     * always delivered.
     */
    fun onCacheUpdated(update: CacheUpdate) {
        val key = update.operationId + "\n" + update.path + "\n" + update.query
        val coalescer = synchronized(lock) {
            cachePayloads[key] = update
            cacheUpdates.getOrPut(key) { Coalescer { emitCacheUpdated(key) } }
        }
        coalescer.request()
    }

    private fun emitCacheUpdated(key: String) {
        val u = synchronized(lock) { cachePayloads[key] } ?: return
        emit(
            "cache.updated",
            obj(
                "operation_id" to JsonString(u.operationId),
                "path" to JsonString(u.path),
                "query" to JsonString(u.query),
                "status" to JsonNumber(u.status.toDouble()),
            ),
        )
    }

    /** A `note.created` the SPA did not cause. [seq] is 0 for a note minted on this device. */
    fun onNoteCreated(noteId: String, originDeviceId: String, origin: String, seq: Long = 0) {
        emit(
            "note.created",
            obj(
                "note_id" to JsonString(noteId),
                "origin_device_id" to JsonString(originDeviceId),
                "origin" to JsonString(origin),
                "seq" to JsonNumber(seq.toDouble()),
            ),
        )
    }

    /** `NetworkMonitor` changed; only a change of [online] is sent. */
    fun onConnectivity(online: Boolean) {
        synchronized(lock) {
            if (lastOnline == online) return
            lastOnline = online
        }
        emit("shell.connectivity", obj("online" to JsonBool(online)))
        status.request()
    }

    /** Any property of `getSyncStatus` may have changed (pairing, unpairing, `SyncStatusRepository` updates). */
    fun onStatusChanged() = status.request()

    /** Collects [flow] (`SyncStatusRepository.status`) and sends `shell.sync` for each change. */
    fun watch(flow: Flow<SyncStatusView?>) {
        scope.launch { flow.collect { onStatusChanged() } }
    }

    private fun emitSyncChanged() {
        val (seq, pending) = syncChanged()
        emit("sync.changed", obj("high_water_seq" to JsonNumber(seq.toDouble()), "pending_ops" to JsonNumber(pending.toDouble())))
    }

    private fun emitSyncStatus() {
        val json = syncStatus()
        val text = DefaultCanonicalJson.encode(json)
        synchronized(lock) {
            if (text == lastStatus) return
            lastStatus = text
        }
        emit("shell.sync", json)
    }

    private fun emit(type: String, data: JsonObject) {
        runCatching { send(BridgeMessage.Event(type, BridgeCore.formatAt(nowMs()), data)) }
    }

    /** At most one emission per [intervalMs]; requests while one is waiting merge into it (the state is read at emit time). */
    private inner class Coalescer(private val emitNow: () -> Unit) {
        private val guard = Any()
        private var scheduled = false
        private var lastAt: Long? = null

        fun request() {
            val wait: Long
            synchronized(guard) {
                if (scheduled) return
                scheduled = true
                wait = lastAt?.let { max(0L, it + intervalMs - nowMs()) } ?: 0L
            }
            scope.launch {
                if (wait > 0) delay(wait)
                synchronized(guard) {
                    scheduled = false
                    lastAt = nowMs()
                }
                runCatching { emitNow() }
            }
        }
    }

    private fun obj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*members))

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 1000

        /**
         * The K-4 marks of one sync outcome: all stale after a rebuild or log reset; the replica tables after a cycle
         * that applied ops; the warm pass after a completed cycle. `ShellEventHub` may call it with [dbStaleMarks] when
         * no [ShellEvents] exists in the process.
         */
        fun applyCacheMarks(outcome: SyncOutcome, hooks: CacheHooks?) {
            if (hooks == null) return
            val ran = (outcome as? SyncOutcome.Ran)?.result
            if (ran is CycleResult.RebuildPending || ran is CycleResult.LogChanged) runCatching { hooks.onAllStale() }
            val result = ran as? CycleResult.Completed ?: return
            if (result.pulledOps > 0 || result.pullAdvanced) runCatching { hooks.onReplicaChanged() }
            runCatching { hooks.onCycleSucceeded() }
        }

        /** [StaleMarks] over the app's `response_cache` (Koin's [AppDatabase]); null before Koin started. */
        fun dbStaleMarks(): CacheHooks? =
            GlobalContext.getOrNull()?.getOrNull<AppDatabase>()?.let { StaleMarks(SqlResponseCacheStore(it)) }
    }
}

/** What adds a note and says what happened (`TakobotoAnkiStore.add`). */
fun interface NoteAdd {
    fun add(note: NewNote): AddOutcome
}

/**
 * An [AnkiStore] that tells [events] about each note it created for an outside caller (AnkiProvider / Takoboto, on-device
 * AnkiConnect): `note.created`, once per note, nothing for a duplicate. Everything else is [delegate]'s.
 * Production: `EventingAnkiStore(store, store::add, events) { deviceId }` over the [TakobotoAnkiStore].
 */
class EventingAnkiStore(
    private val delegate: AnkiStore,
    private val adder: NoteAdd,
    private val events: ShellEvents,
    private val deviceId: () -> String?,
) : AnkiStore by delegate {
    override fun addNote(note: NewNote): AnkiNote {
        val outcome = adder.add(note)
        if (outcome is AddOutcome.Added) {
            deviceId()?.let { events.onNoteCreated(outcome.noteId, it, originOf(note)) }
        }
        return outcome.note
    }

    private fun originOf(note: NewNote): String =
        if (note.callerPackage?.contains("takoboto", ignoreCase = true) == true) {
            TakobotoAnkiStore.ORIGIN_TAKOBOTO
        } else {
            TakobotoAnkiStore.ORIGIN_API
        }
}
