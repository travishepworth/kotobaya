package kotobaya.android.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys

/**
 * The outbox write path on the real database: a process killed at any point of a push loses no op and
 * duplicates none (SYNC.md 4, 5.4), and a mint is all-or-nothing (SYNC.md 4).
 */
class DurabilityTest {
    private val deviceA = "01931f3e-0000-7000-8000-0000000000a1"
    private val t0 = 1_733_500_000_000L

    private fun setting(i: Int) = MintRequest(
        OpType.SETTINGS_SET,
        JsonObject(mapOf("key" to JsonString("test.k$i"), "value" to JsonNumber(i.toDouble()))),
    )

    private fun mint(d: SqlDevice, from: Int, n: Int): List<String> {
        val r = assertIs<MintResult.Minted>(d.stack.minter.mintAll((from until from + n).map(::setting)))
        return r.ops.map { it.opId }
    }

    private fun settingsOn(d: SqlDevice): Map<String, Any?> =
        d.store.read { tx -> tx.learning.all(LearningTable.SETTINGS).associate { (it["key"] as JsonString).value to it["value"] } }

    @Test
    fun theDatabaseIsOpenedWithWalAndSynchronousFull() {
        val d = SqlDevice(deviceA, TestServer(t0), t0)
        assertEquals("wal", DurableDriver.journalMode(d.driver))
        assertEquals(2L, DurableDriver.synchronousLevel(d.driver))
    }

    @Test
    fun killAppMidPushAfterTheServerAppliedLosesAndDuplicatesNothing() {
        val server = TestServer(t0)
        val d = SqlDevice(deviceA, server, t0)
        val minted = mint(d, 0, 3)
        d.transport.killAfterNextPush = true
        assertFailsWith<Killed> { d.cycle() }
        assertEquals(minted, server.loggedOpIds(), "the server applied them")
        d.restart()
        assertEquals(3, d.rows().count { it.state == PendingState.PENDING }, "still pending locally after the kill")
        assertIs<CycleResult.Completed>(d.cycle())
        assertEquals(minted, server.loggedOpIds(), "the re-push got `duplicate`, nothing was logged twice")
        assertEquals(emptyList(), d.rows(), "acked and purged")
    }

    @Test
    fun killAppBeforeThePushIsSentLosesNothing() {
        val server = TestServer(t0)
        val d = SqlDevice(deviceA, server, t0)
        val minted = mint(d, 0, 2)
        d.transport.killBeforeNextPush = true
        assertFailsWith<Killed> { d.cycle() }
        assertEquals(emptyList(), server.loggedOpIds())
        d.restart()
        assertEquals(minted, d.rows().map { it.opId })
        assertIs<CycleResult.Completed>(d.cycle())
        assertEquals(minted, server.loggedOpIds())
        assertEquals(emptyList(), d.rows())
    }

    @Test
    fun randomKillsAndLostResponsesWithRestartsBetweenStepsConverge() {
        for (seed in 0 until 12) {
            val rnd = Random(seed)
            val server = TestServer(t0)
            server.pageSize = 3
            val d = SqlDevice(deviceA, server, t0, seed = seed + 1)
            val minted = ArrayList<String>()
            var next = 0
            repeat(25) {
                val n = rnd.nextInt(0, 4)
                if (n > 0) {
                    minted += mint(d, next, n)
                    next += n
                }
                d.clock.now += 1_000
                server.nowMs = d.clock.now
                when (rnd.nextInt(6)) {
                    0 -> d.transport.killAfterNextPush = true
                    1 -> d.transport.killBeforeNextPush = true
                    2 -> d.transport.loseNextPushResponse = true
                    3 -> d.transport.failNextPushBeforeServer = true
                    4 -> d.transport.killAfterNextPull = true
                    else -> Unit
                }
                try {
                    d.cycle()
                } catch (e: Killed) {
                    // the process died here
                }
                d.transport.killAfterNextPush = false
                d.transport.killBeforeNextPush = false
                d.transport.loseNextPushResponse = false
                d.transport.failNextPushBeforeServer = false
                d.transport.killAfterNextPull = false
                d.restart() // close and reopen the database between every step
            }
            var rounds = 0
            while (d.rows().any { it.state == PendingState.PENDING } && rounds++ < 10) d.cycle()
            assertIs<CycleResult.Completed>(d.cycle(), "seed $seed")
            assertEquals(emptyList(), d.rows(), "seed $seed: outbox empty")
            val logged = server.loggedOpIds()
            assertEquals(logged.distinct(), logged, "seed $seed: an op logged twice")
            assertEquals(minted.toSet(), logged.toSet(), "seed $seed: an op lost")
            assertEquals(minted.size, logged.size, "seed $seed")
            assertSameRows(projection(serverReplica(server)), projection(d.tables()), "seed $seed: replica")
            assertEquals(next, settingsOn(d).size, "seed $seed")
        }
    }

    @Test
    fun aMintFailingBetweenTheLocalEffectAndTheOutboxInsertLeavesNothing() {
        var armed = false
        val server = TestServer(t0)
        val d = SqlDevice(deviceA, server, t0)
        val inner = d.stack.applier
        // the same stack, with an applier that throws right after the local effect was written
        val failing = OpApplier<SqlLearningTables> { tx, op, origin ->
            inner.apply(tx, op, origin)
            if (armed) throw IllegalStateException("crash between the local effect and the outbox insert")
        }
        val minter = kotobaya.sync.client.mint.OpMinter(d.store, failing, d.clock, kotobaya.sync.client.store.UuidV7Source(Random(9)))
        assertIs<MintResult.Minted>(minter.mintAll(listOf(setting(0))))
        val hlcBefore = d.state(SyncStateKeys.HLC_LAST)
        armed = true
        assertFailsWith<IllegalStateException> { minter.mintAll(listOf(setting(1), setting(2))) }
        d.restart()
        assertEquals(mapOf<String, Any?>("test.k0" to JsonNumber(0.0)), settingsOn(d), "no local effect kept")
        assertEquals(1, d.rows().size, "no outbox row kept")
        assertEquals(hlcBefore, d.state(SyncStateKeys.HLC_LAST), "hlc_last unchanged")
        assertNull(d.store.read { it.learning.get(LearningTable.SETTINGS, listOf(JsonString("test.k1"))) })
        // and the device keeps working
        armed = false
        assertIs<MintResult.Minted>(minter.mintAll(listOf(setting(1))))
        assertTrue(d.rows().size == 2)
    }

    @Test
    fun aFailedTransactionRollsBackOutboxSyncStateAndLearningTables() {
        val d = SqlDevice(deviceA, TestServer(t0), t0)
        mint(d, 0, 1)
        val before = d.rows()
        assertFailsWith<IllegalStateException> {
            d.store.transaction { tx ->
                tx.outbox.delete(before.single().opId)
                tx.syncState.put(SyncStateKeys.LAST_SYNC_AT, "1")
                tx.learning.clear(LearningTable.SETTINGS)
                // a nested transaction joins the outer one
                d.store.transaction { inner -> inner.syncState.put(SyncStateKeys.PULL_SEQ, "7") }
                error("boom")
            }
        }
        d.restart()
        assertEquals(before, d.rows())
        assertNull(d.state(SyncStateKeys.LAST_SYNC_AT))
        assertNull(d.state(SyncStateKeys.PULL_SEQ))
        assertEquals(1, settingsOn(d).size)
    }

    @Test
    fun aDuplicateOutboxInsertThrowsAndKeepsTheFirstRow() {
        val d = SqlDevice(deviceA, TestServer(t0), t0)
        mint(d, 0, 1)
        val row = d.rows().single()
        assertFailsWith<IllegalStateException> { d.store.transaction { it.outbox.insert(row.copy(payload = "{}")) } }
        assertEquals(listOf(row), d.rows())
    }
}
