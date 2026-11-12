package kotobaya.sync.client.protocol

import kotobaya.contracts.sync.Op
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.OpIdSource
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random

/**
 * Runs a suspend block that never really suspends (in-memory transports) to completion, on every
 * platform, without kotlinx-coroutines.
 */
fun <T> runSync(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "the block suspended; test transports must answer synchronously" }.getOrThrow()
}

/** One applied op, as the recording applier saw it. */
data class Applied(val opId: String, val opType: String, val origin: ApplyOrigin, val seq: Long?)

/** Learning tables of the protocol tests: just the list of applied ops (the real apply is T303b). */
class Recording(val applied: MutableList<Applied> = ArrayList()) {
    fun copy(): Recording = Recording(ArrayList(applied))

    fun pulledIds(): List<String> = applied.filter { it.origin == ApplyOrigin.PULLED }.map { it.opId }
}

/** Records every apply; optionally throws for a chosen op (crash injection). */
class RecordingApplier(var failOn: (Op) -> Boolean = { false }) : OpApplier<Recording> {
    override fun apply(tx: kotobaya.sync.client.store.StoreTransaction<Recording>, op: Op, origin: ApplyOrigin) {
        if (failOn(op)) throw IllegalStateException("injected apply failure for ${op.opId}")
        tx.learning.applied.add(Applied(op.opId, op.opType.wire, origin, op.seq))
    }
}

/** A settable clock. */
class TestClock(var now: Long) : Clock {
    override fun nowMs(): Long = now
}

/** Op ids from a script first, then UUIDv7 from a seeded random. */
class ScriptedIds(seed: Int = 1) : OpIdSource {
    val queue: ArrayDeque<String> = ArrayDeque()
    private val fallback = UuidV7Source(Random(seed))

    override fun newOpId(nowMs: Long): String = queue.removeFirstOrNull() ?: fallback.newOpId(nowMs)
}

/** One simulated device: in-memory store, recording applier, real [OpMinter] and [SyncEngine]. */
class TestDevice(
    val deviceId: String,
    server: TestServer,
    clockMs: Long,
    seed: Int = 1,
) {
    val clock = TestClock(clockMs)
    val ids = ScriptedIds(seed)
    val applier = RecordingApplier()
    val store = InMemoryDeviceStore(Recording()) { it.copy() }
    val transport = TestTransport(server)
    val minter = OpMinter(store, applier, clock, ids)
    val engine = SyncEngine(store, transport, applier, clock)

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, deviceId) }
    }

    fun rows() = store.rows()

    fun state(key: String): String? = store.syncState()[key]

    fun cycle(): CycleResult = runSync { engine.cycle() }

    fun push(): CycleResult = runSync { engine.pushAll() }

    fun pull(): CycleResult = runSync { engine.pullAll() }
}
