package kotobaya.android.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayEngine
import kotobaya.contracts.replay.ReplayReview
import kotobaya.replay.DefaultReplayEngine
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.DeviceDiagnostics
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.StartupReplay
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.SyncStatus
import kotobaya.sync.client.store.SyncStateKeys

/** A replay engine that refuses every replay and fold (as DefaultReplayEngine does for invalid input). */
class RefusingEngine : ReplayEngine by DefaultReplayEngine {
    override fun replay(reviews: List<ReplayReview>, params: SchedulerParams): CardCache =
        if (reviews.isEmpty()) DefaultReplayEngine.replay(reviews, params) else throw IllegalArgumentException("injected refusal")

    override fun fold(cache: CardCache, review: ReplayReview, params: SchedulerParams): CardCache =
        throw IllegalArgumentException("injected refusal")
}

/**
 * T303b's apply, replay, rebuild and log-reset tests re-run on [SqlDelightDeviceStore] over the JDBC
 * driver: the scenario files give the same expected rows, and the SQLite tables hold exactly what
 * T303a's in-memory store holds after the same inputs (same rows, same order).
 */
class SqlStoreApplyTest {
    private val deviceA = "01931f3e-0000-7000-8000-0000000000a1"

    // ------------------------------------------------------------------ apply (T303b ScenarioApplyTest, ExampleOpsApplyTest)

    @Test
    fun everyScenarioLogInSeqOrderGivesTheExpectedRowsAndTheInMemoryRows() {
        assertTrue(Scenarios.names.size >= 16)
        for (name in Scenarios.names) {
            val store = SqlDelightDeviceStore(memoryDriver())
            val cache = DeviceCardCache()
            val applier = DeviceOpApplier<SqlLearningTables>(cache)
            val mem = InMemoryLearningTables.seeded()
            val memApplier = DeviceOpApplier<InMemoryLearningTables>()
            for (op in Scenarios.log(name)) {
                assertTrue(store.transaction { applier.applyTo(it.learning, op) }, "$name seq ${op.seq}: missing reference")
                memApplier.applyTo(mem, op)
            }
            store.read { tx ->
                assertScenarioExpected(name, tx.learning, cache, name)
                assertSameRows(dump(mem), dump(tx.learning), name)
                assertEquals(orderedKeys(mem), orderedKeys(tx.learning), "$name: row order")
            }
            assertEquals(emptyList(), cache.diagnostics.refusals, name)
        }
    }

    @Test
    fun everyExampleOpAppliedTwiceGivesTheInMemoryRows() {
        val store = SqlDelightDeviceStore(memoryDriver())
        val applier = DeviceOpApplier<SqlLearningTables>()
        val mem = InMemoryLearningTables.seeded()
        val memApplier = DeviceOpApplier<InMemoryLearningTables>()
        val order = Examples.world + Examples.all.keys.sorted().filter { it !in Examples.world }
        for (name in order) {
            val op = Examples.op(name)
            store.transaction { applier.applyTo(it.learning, op) }
            memApplier.applyTo(mem, op)
            store.read { assertSameRows(dump(mem), dump(it.learning), name) }
        }
        val before = store.read { dump(it.learning) }
        for (name in order) store.transaction { applier.applyTo(it.learning, Examples.op(name)) } // SYNC.md 7.8
        assertSameRows(before, store.read { dump(it.learning) }, "re-applied")
    }

    @Test
    fun scenariosPlayedThroughTheServerConvergeOnSqlDevicesLikeOnInMemoryDevices() {
        for (name in Scenarios.names) {
            val sql = play(name) { id, server, n -> SqlDevice(id, server, server.nowMs, n) }
            val mem = play(name) { id, server, n -> MemDeviceApi(MemDevice(id, server, server.nowMs, n)) }
            val want = projection(serverReplica(sql.first))
            for ((id, d) in sql.second) {
                assertSameRows(want, projection(d.tables()), "$name device $id vs server")
                assertSameRows(dump(mem.second.getValue(id).tables()), dump(d.tables()), "$name device $id sql vs memory")
            }
        }
    }

    /** T303b's ScenarioPlay: each device op is injected and pushed at once; then every device syncs. */
    private fun <D : DeviceApi> play(name: String, make: (String, TestServer, Int) -> D): Pair<TestServer, Map<String, D>> {
        val log = Scenarios.log(name)
        val server = TestServer(log.maxOf { DefaultHlcRules.wall(it.hlc) })
        server.emitAfter = { op -> log.filter { it.causedByOpId == op.opId }.map { it.copy(seq = null, causedByOpId = null) } }
        val devices = LinkedHashMap<String, D>()
        for (e in log) {
            when {
                e.deviceId != Reserved.SERVER_DEVICE_ID -> {
                    val d = devices.getOrPut(e.deviceId) { make(e.deviceId, server, devices.size + 1) }
                    d.inject(e)
                    assertIs<CycleResult.Completed>(d.pushAll(), "$name push of seq ${e.seq}")
                }
                e.causedByOpId == null -> server.append(e.copy(seq = null, causedByOpId = null), null)
                else -> Unit
            }
        }
        for (d in devices.values) assertIs<CycleResult.Completed>(d.sync(), "$name sync")
        return server to devices
    }

    // ------------------------------------------------------------------ startup replay (T303b StartupReplayTest)

    @Test
    fun startupReplayRunsOnceAndSurvivesAKillBeforeCommit() {
        val server = TestServer(DefaultHlcRules.wall(Examples.op("review.logged.json").hlc))
        val d = SqlDevice(deviceA, server, server.nowMs)
        for (n in Examples.world) d.inject(Examples.op(n))
        val card = Examples.op("card.created.json").payload.members.getValue("id")
        val good = d.store.read { it.learning.get(LearningTable.CARD, listOf(card)) }!!
        // an old core's cache: wrong reps, and a different stored version
        d.store.transaction { tx ->
            tx.learning.put(LearningTable.CARD, good + ("reps" to JsonNumber(99.0)))
            tx.syncState.put(StartupReplay.CORE_SCHEDULER_VERSION, "old-core")
        }
        // killed in the middle of the replay transaction: nothing of it is kept
        assertFailsWith<Killed> {
            d.store.transaction { tx ->
                d.stack.startup.ensure(tx)
                throw Killed("during the startup replay")
            }
        }
        d.restart()
        assertEquals("old-core", d.state(StartupReplay.CORE_SCHEDULER_VERSION))
        assertEquals(JsonNumber(99.0), d.store.read { it.learning.get(LearningTable.CARD, listOf(card)) }!!["reps"])

        assertTrue(d.stack.startup.run(d.store))
        d.restart()
        assertEquals(good, d.store.read { it.learning.get(LearningTable.CARD, listOf(card)) })
        assertEquals(DeviceCardCache().engine.schedulerVersion, d.state(StartupReplay.CORE_SCHEDULER_VERSION))
        assertFalse(d.stack.startup.run(d.store))
    }

    // ------------------------------------------------------------------ refused cache (C-8 follow-up)

    @Test
    fun aRefusedReplayPersistsTheRefusedCacheAndTheDiagnosticsCountIt() {
        val server = TestServer(DefaultHlcRules.wall(Examples.op("review.logged.json").hlc))
        val d = SqlDevice(deviceA, server, server.nowMs, engine = RefusingEngine())
        for (n in Examples.world) d.inject(Examples.op(n))
        assertTrue(d.stack.diagnostics.refusals.isNotEmpty())
        d.restart()
        val refused = d.store.read { DeviceDiagnostics.refusedCacheCards(it.learning) }
        assertTrue(refused.isNotEmpty(), "the refused cache is durable")
        for (id in refused) {
            val card = d.store.read { it.learning.get(LearningTable.CARD, listOf(JsonString(id))) }!!
            assertEquals(JsonString(Reserved.REPLAY_REFUSED_ID), card["last_review_id"])
            assertEquals(JsonString("new"), card["state"])
        }
        assertEquals(refused.size, d.store.read { it.learning.refusedCacheCount() })
        val status = SyncStatusRepository(d.store, { d.clock.now }, { true }).current()
        assertEquals(refused.size, status.status.refusedCacheCards)
    }

    // ------------------------------------------------------------------ log reset and rebuild (T303b RebuildTest, SYNC.md 12.5)

    /** Device A's world ops, pushed and pulled; the server keeps only what is durable at [durable]. */
    private fun <D : DeviceApi> restoredWorld(make: (TestServer) -> D, durable: Long): Pair<TestServer, D> {
        val ops = Examples.world.map { Examples.op(it) }.filter { it.deviceId == deviceA }
        val server = TestServer(ops.maxOf { DefaultHlcRules.wall(it.hlc) })
        server.durableSeq = durable
        val d = make(server)
        for (op in ops) d.inject(op)
        assertIs<CycleResult.Completed>(d.sync())
        server.restore(durable, "0193a1b2-00ee-7000-8000-000000000002")
        return server to d
    }

    @Test
    fun aServerRestoredFromABackupIsResetAndRebuiltLikeInMemory() {
        val (server, sql) = restoredWorld({ SqlDevice(deviceA, it, it.nowMs) }, durable = 4)
        val (memServer, mem) = restoredWorld({ MemDeviceApi(MemDevice(deviceA, it, it.nowMs)) }, durable = 4)
        assertIs<CycleResult.Completed>(sql.sync())
        assertIs<CycleResult.Completed>(mem.sync())
        assertNull(sql.state(SyncStateKeys.REBUILD_PENDING))
        assertEquals("0193a1b2-00ee-7000-8000-000000000002", sql.state(SyncStateKeys.LOG_EPOCH))
        assertEquals(server.loggedOpIds(), server.loggedOpIds().distinct(), "no op twice in the log")
        assertEquals(memServer.loggedOpIds(), server.loggedOpIds())
        assertSameRows(projection(serverReplica(server)), projection(sql.tables()), "sql device vs restored server")
        assertSameRows(dump(mem.tables()), dump(sql.tables()), "sql vs memory")
    }

    @Test
    fun aRebuildCutOffAfterItsWipeOrKilledMidPullFinishesAfterARestart() {
        val (server, d) = restoredWorld({ SqlDevice(deviceA, it, it.nowMs) }, durable = 4)
        d.transport.failPullFromZero = true
        assertIs<CycleResult.Failed>(d.cycle())
        assertEquals("1", d.state(SyncStateKeys.REBUILD_PENDING))
        d.restart()
        d.transport.failPullFromZero = false
        d.transport.killAfterNextPull = true
        assertFailsWith<Killed> { d.cycle() }
        d.restart()
        assertEquals("1", d.state(SyncStateKeys.REBUILD_PENDING))
        assertIs<CycleResult.Completed>(d.cycle())
        assertNull(d.state(SyncStateKeys.REBUILD_PENDING))
        assertEquals(server.loggedOpIds(), server.loggedOpIds().distinct())
        assertSameRows(projection(serverReplica(server)), projection(d.tables()), "after the restarted rebuild")
    }

    @Test
    fun theStoreAndTheInMemoryStoreAgreeOnRowOrderAfterUpdatesAndDeletes() {
        val store = SqlDelightDeviceStore(memoryDriver())
        val mem = InMemoryLearningTables.seeded()
        val applier = DeviceOpApplier<SqlLearningTables>()
        val memApplier = DeviceOpApplier<InMemoryLearningTables>()
        val ops: List<Op> = Examples.world.map { Examples.op(it) } +
            listOf("note.updated.json", "card.suspended.json", "note.deleted.json", "card.deleted.json").map { Examples.op(it) }
        for (op in ops) {
            store.transaction { applier.applyTo(it.learning, op) }
            memApplier.applyTo(mem, op)
        }
        store.read { assertEquals(orderedKeys(mem), orderedKeys(it.learning)) }
        assertNotEquals(0, store.read { it.learning.all(LearningTable.CARD).size })
    }
}

/** What the scenario and restore tests need from a device, on either store. */
interface DeviceApi {
    fun inject(op: Op)
    fun pushAll(): CycleResult
    fun sync(): CycleResult
    fun tables(): LearningTables
    fun state(key: String): String?
}

class MemDeviceApi(val d: MemDevice) : DeviceApi {
    override fun inject(op: Op) = d.inject(op)
    override fun pushAll(): CycleResult = runSync { d.stack.engineSync.pushAll() }
    override fun sync(): CycleResult = d.cycle()
    override fun tables(): LearningTables = d.tables()
    override fun state(key: String): String? = d.store.syncState()[key]
}
