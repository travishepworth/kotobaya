package kotobaya.sync.client.protocol

import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Learning tables of the fuzz: whole-row last-writer-wins registers (SYNC.md 7.4) keyed by entity. */
class LwwRows(
    val rows: MutableMap<String, Pair<String, String>> = HashMap(),
    /** How often each op id was applied from a pull (rolled back with its transaction). */
    val pulledApplies: MutableMap<String, Int> = HashMap(),
) {
    fun copy(): LwwRows = LwwRows(HashMap(rows), HashMap(pulledApplies))
}

/**
 * Stand-in for T303b's apply, for the op subset the fuzz generates (`settings.set`,
 * `word.status_set`, `word.status_cleared`): one register per entity, written iff the op's hlc is
 * greater than the stored one. Idempotent and order-free, like the real rule.
 */
object LwwApplier : OpApplier<LwwRows> {
    override fun apply(tx: StoreTransaction<LwwRows>, op: Op, origin: ApplyOrigin) {
        if (origin == ApplyOrigin.PULLED) tx.learning.pulledApplies[op.opId] = (tx.learning.pulledApplies[op.opId] ?: 0) + 1
        apply(tx.learning, op)
    }

    fun apply(rows: LwwRows, op: Op) {
        require(op.opType in FUZZ_TYPES) { "the stand-in applies only ${FUZZ_TYPES.map { it.wire }}" }
        val key = "${op.entityType}|${op.entityId}"
        val stored = rows.rows[key]
        if (stored == null || op.hlc > stored.first) {
            rows.rows[key] = op.hlc to "${op.opType.wire} ${DefaultCanonicalJson.encode(op.payload)}"
        }
    }

    val FUZZ_TYPES: Set<OpType> = setOf(OpType.SETTINGS_SET, OpType.WORD_STATUS_SET, OpType.WORD_STATUS_CLEARED)
}

private class FuzzDevice(val id: String, server: TestServer, val rnd: Random, seed: Int) {
    val clock = TestClock(NOW + rnd.nextLong(-60_000, 60_000))
    val store = InMemoryDeviceStore(LwwRows()) { it.copy() }
    val transport = TestTransport(server)
    val minter = OpMinter(store, LwwApplier, clock, UuidV7Source(Random(seed)))
    /** SYNC-T1: a random pull chunk size; a chunk is a smaller page, so every size must converge. */
    val engine = SyncEngine(store, transport, LwwApplier, clock, pullChunkOps = 1 + rnd.nextInt(5))
    val minted = ArrayList<String>()

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, id) }
    }

    fun mintRandom() {
        val payload: Pair<OpType, JsonObject> = when (rnd.nextInt(3)) {
            0 -> OpType.SETTINGS_SET to JsonObject(
                mapOf("key" to JsonString("k.${"abc"[rnd.nextInt(3)]}"), "value" to JsonNumber(rnd.nextInt(100).toDouble())),
            )
            1 -> OpType.WORD_STATUS_SET to JsonObject(
                mapOf(
                    "entry_seq" to JsonNumber((1 + rnd.nextInt(3)).toDouble()),
                    "status" to JsonString(listOf("known", "never_forget", "blacklisted", "ignored")[rnd.nextInt(4)]),
                ),
            )
            else -> OpType.WORD_STATUS_CLEARED to JsonObject(mapOf("entry_seq" to JsonNumber((1 + rnd.nextInt(3)).toDouble())))
        }
        val r = assertIs<MintResult.Minted>(minter.mint(payload.first, payload.second))
        minted.add(r.ops.single().opId)
    }
}

/**
 * Client-side convergence fuzz (SYNC.md 11): N >= 3 devices with real [OpMinter] / [SyncEngine] and
 * in-memory stores, a random pull chunk size 1..5, random offline stretches, sync orders, lost responses, failed sends, replayed
 * pages, crashes while recording push results, small push bodies (413 / batching) and small pages.
 * After final syncs every replica equals the server's replica, nothing minted is lost or logged
 * twice, and every outbox is empty. Deterministic per seed.
 */
class ClientConvergenceFuzzTest {
    @Test
    fun replicasConvergeWithTheServerUnderRandomSyncOrdersAndFaults() {
        for (seed in 0 until SEEDS) runSeed(seed)
    }

    private fun runSeed(seed: Int) {
        val rnd = Random(seed)
        val server = TestServer(NOW)
        if (rnd.nextBoolean()) server.maxPushBytes = 900L + rnd.nextInt(1_500)
        server.pageSize = 1 + rnd.nextInt(6)
        if (rnd.nextBoolean()) server.pageByteBound = 600L + rnd.nextInt(800)
        val serverRows = LwwRows()
        server.onAccept = { LwwApplier.apply(serverRows, it) }
        val n = 3 + rnd.nextInt(2)
        val devices = (0 until n).map { i ->
            FuzzDevice("01931f3e-0000-7000-8000-0000000000${(0xa0 + i).toString(16)}", server, rnd, seed * 31 + i)
        }
        // some seeds have a skewed device (within the server's 24 h tolerance): 2 h ahead or 3 h behind
        when (seed % 3) {
            0 -> devices[0].clock.now += 2 * HOUR
            1 -> devices[0].clock.now -= 3 * HOUR
            else -> Unit
        }
        repeat(STEPS) {
            val d = devices[rnd.nextInt(n)]
            when (rnd.nextInt(20)) {
                in 0..8 -> d.mintRandom()
                in 9..13 -> runSync { d.engine.cycle() }
                14 -> d.transport.offline = !d.transport.offline
                15 -> d.transport.loseNextPushResponse = true
                16 -> d.transport.failNextPushBeforeServer = true
                17 -> d.transport.replayNextPull = true
                18 -> runCatching { runSync { SyncEngine(CrashOnAckStore(d.store, rnd.nextInt(3)), d.transport, LwwApplier, d.clock).pushAll() } }
                else -> server.durableSeq = if (rnd.nextBoolean()) null else rnd.nextLong(0, server.highWaterSeq + 1)
            }
            d.clock.now += rnd.nextLong(0, 5_000)
            server.nowMs += rnd.nextLong(0, 5_000)
        }
        // final syncs: everyone online, no faults, durable everything
        server.durableSeq = null
        for (d in devices) {
            d.transport.offline = false
            d.transport.loseNextPushResponse = false
            d.transport.failNextPushBeforeServer = false
            d.transport.replayNextPull = false
        }
        repeat(2) {
            for (d in devices) assertIs<CycleResult.Completed>(runSync { d.engine.cycle() }, "seed $seed")
        }
        val logged = server.loggedOps().map { it.opId }
        assertEquals(logged.size, logged.toSet().size, "seed $seed: an op was logged twice")
        assertEquals(devices.flatMap { it.minted }.toSet(), logged.toSet(), "seed $seed: minted ops lost or invented")
        for (d in devices) {
            assertEquals(serverRows.rows, d.store.learning().rows, "seed $seed: ${d.id} diverged")
            assertEquals(emptyList(), d.store.rows(), "seed $seed: ${d.id} outbox not purged")
            assertEquals(server.highWaterSeq.toString(), d.store.syncState()[SyncStateKeys.PULL_SEQ] ?: "0", "seed $seed")
            val applies = d.store.learning().pulledApplies
            assertEquals(logged.toSet(), applies.keys, "seed $seed: ${d.id} missed a pulled op")
            assertTrue(applies.values.all { it == 1 }, "seed $seed: ${d.id} applied a pulled op twice: ${applies.filterValues { it != 1 }}")
        }
        assertTrue(devices.none { d -> d.store.rows().any { it.state == PendingState.REJECTED } })
    }

    private companion object {
        const val SEEDS = 120
        const val STEPS = 90
        const val HOUR = 3_600_000L
    }
}
