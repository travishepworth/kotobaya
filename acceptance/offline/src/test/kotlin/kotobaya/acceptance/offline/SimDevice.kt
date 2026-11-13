package kotobaya.acceptance.offline

import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.StartupReplay
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.SyncEngine
import kotobaya.sync.client.protocol.SyncLimits
import kotobaya.sync.client.protocol.SyncTransport
import kotobaya.sync.client.protocol.TransportResponse
import kotobaya.sync.client.rebuild.DeviceSyncCycle
import kotobaya.sync.client.rebuild.Rebuild
import kotobaya.sync.client.rejected.RejectedOpsService
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotobaya.sync.client.store.bookkeeping
import kotlin.random.Random

/** The simulated process died (thrown out of a transaction, which therefore rolls back, or out of a send). */
class Killed(where: String) : RuntimeException("device process killed $where (simulated)")

/** True (server) time of the simulation. */
class World(var now: Long = WEEK_START)

/** A device's physical clock: true time + [offset] + [driftPerDay] per elapsed day. */
class SimClock(private val world: World) : Clock {
    var offset: Long = 0
    var driftPerDay: Long = 0

    override fun nowMs(): Long = world.now + offset + driftPerDay * (world.now - WEEK_START) / DAY

    /** The user sets the clock right (a small residual skew stays). */
    fun fix(residual: Long = 0) {
        offset = residual - driftPerDay * (world.now - WEEK_START) / DAY
    }

    /** Current error vs true time. */
    val error: Long get() = nowMs() - world.now
}

/** [SyncTransport] onto a [SimServer] with faults: offline, lost requests and responses, a kill after the server applied a push. */
class SimTransport(private val deviceId: String, private val server: SimServer) : SyncTransport {
    var offline: Boolean = false
    var failNextPushBeforeServer: Boolean = false
    var loseNextPushResponse: Boolean = false
    var loseNextPullResponse: Boolean = false
    var killAfterNextPush: Boolean = false

    override suspend fun push(body: String): TransportResponse {
        if (offline) return TransportResponse.NetworkError("offline")
        if (failNextPushBeforeServer) {
            failNextPushBeforeServer = false
            return TransportResponse.NetworkError("connection reset before the request was sent")
        }
        val r = server.push(deviceId, body)
        if (killAfterNextPush) {
            killAfterNextPush = false
            throw Killed("while the push response was in flight")
        }
        if (loseNextPushResponse) {
            loseNextPushResponse = false
            return TransportResponse.NetworkError("response lost")
        }
        return r
    }

    override suspend fun pull(since: Long): TransportResponse {
        if (offline) return TransportResponse.NetworkError("offline")
        val r = server.pull(since)
        if (loseNextPullResponse) {
            loseNextPullResponse = false
            return TransportResponse.NetworkError("response lost")
        }
        return r
    }

    override suspend fun limits(): SyncLimits? = if (offline) null else server.limits()

    fun clearFaults() {
        failNextPushBeforeServer = false
        loseNextPushResponse = false
        loseNextPullResponse = false
        killAfterNextPush = false
    }
}

/**
 * [DeviceStore] that can kill the process at the commit of the n-th outermost transaction from now
 * ([killAtTx]): the block ran, then [Killed] is thrown inside the transaction, so nothing of it commits
 * (like a SQLite process killed before COMMIT).
 */
class CrashingStore<L>(private val inner: DeviceStore<L>) : DeviceStore<L> {
    var killAtTx: Int? = null
    private var depth = 0

    override fun <R> transaction(block: (StoreTransaction<L>) -> R): R {
        depth++
        try {
            return inner.transaction { tx ->
                val r = block(tx)
                if (depth == 1) {
                    killAtTx?.let { n ->
                        if (n <= 1) {
                            killAtTx = null
                            throw Killed("at a transaction commit")
                        }
                        killAtTx = n - 1
                    }
                }
                r
            }
        } finally {
            depth--
        }
    }
}

/**
 * One simulated phone: the real T303 client stack (`OpMinter`, `SyncEngine`, `DeviceOpApplier` with
 * its card cache, `Rebuild`, `DeviceSyncCycle`, `RejectedOpsService`) on an in-memory store that
 * survives process kills, its own clock, and a faulty transport. [boot] starts a new process.
 */
class SimDevice(val name: String, val id: String, server: SimServer, world: World, seed: Int) {
    val clock = SimClock(world)
    val ids = UuidV7Source(Random(seed))
    val store = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
    val crash = CrashingStore(store)
    val transport = SimTransport(id, server)
    lateinit var app: App
        private set
    var boots = 0
        private set

    inner class App {
        val cache = DeviceCardCache()
        val applier = DeviceOpApplier<InMemoryLearningTables>(cache)
        val minter = OpMinter(crash, applier, clock, ids)
        val engine = SyncEngine(crash, transport, applier, clock)
        val rebuild = Rebuild(crash, engine, applier)
        val cycle = DeviceSyncCycle(crash, engine, rebuild, StartupReplay(cache))
        val rejected = RejectedOpsService(crash, minter, clock, ids)
    }

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, id) }
        boot()
    }

    /** A new process on the same local database. */
    fun boot() {
        crash.killAtTx = null
        app = App()
        boots++
    }

    /** One sync cycle; a simulated kill reboots the process and returns null. */
    fun sync(): CycleResult? = try {
        runSync { app.cycle.run() }
    } catch (k: Killed) {
        boot()
        null
    } finally {
        crash.killAtTx = null
    }

    /** Push only (an interrupted cycle); a kill reboots. */
    fun pushOnly(): CycleResult? = try {
        runSync { app.engine.pushAll() }
    } catch (k: Killed) {
        boot()
        null
    } finally {
        crash.killAtTx = null
    }

    fun mint(vararg requests: MintRequest): MintResult = app.minter.mintAll(requests.toList())

    fun tables(): InMemoryLearningTables = store.learning()

    fun rows(): List<PendingOpRow> = store.rows()

    fun state(key: String): String? = store.syncState()[key]

    fun count(state: PendingState): Int = store.rows().count { it.state == state }

    /**
     * Mints like a client **without** the guards G1/G2 and the clock block (SYNC.md 12.6: the HLC still
     * counts on `last`, payload times come from the wrong clock): local effect applied, op queued.
     */
    fun mintUnguarded(type: OpType, payload: JsonObject): Op = store.transaction { tx ->
        val pt = clock.nowMs()
        val bk = tx.bookkeeping
        val hlc = DefaultHlcRules.mint(DefaultHlcRules.stateOf(bk.hlcLast), pt, id).hlc
        val op = Op(ids.newOpId(pt), type, 1, type.entityType, DefaultOpCodec.entityIdOf(type, payload), id, hlc, payload)
        app.applier.apply(tx, op, ApplyOrigin.LOCAL)
        tx.outbox.insert(
            PendingOpRow(
                opId = op.opId, opType = type.wire, opVersion = 1, entityType = op.entityType, entityId = op.entityId,
                hlc = hlc, payload = DefaultCanonicalJson.encode(payload), createdAt = pt,
            ),
        )
        bk.hlcLast = hlc
        op
    }
}
