package kotobaya.sync.client.apply

import kotobaya.contracts.Reserved
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.TestServer
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.bookkeeping
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device convergence (SYNC.md 11) with the full apply: T303a's protocol fuzz extended to every op
 * type of the scenario files. Per seed, one scenario of `contracts/sync/examples/scenarios` is played
 * by its devices (plus an observer) through real [kotobaya.sync.client.protocol.SyncEngine]s,
 * [DeviceOpApplier]s, [kotobaya.sync.client.rebuild.Rebuild]s and a [TestServer] that refuses ops
 * with a missing reference and emits the scenario's merges after their cause. Each device op is put
 * in its device's outbox (local effect applied at once) when everything before it in the log has
 * reached the server, so the server's log is the scenario's; in between, devices sync in random
 * order with random offline stretches, lost responses, failed sends, replayed pages, tiny pages and
 * spurious rebuilds. At the end every replica equals the server's replica under the section 11
 * projection, the scenario's `expected` holds on all of them, and no op was rejected or lost.
 */
class DeviceConvergenceFuzzTest {
    @Test
    fun replicasConvergeWithTheServerProjectionForEveryScenario() {
        for (seed in 0 until SEEDS) runSeed(seed)
    }

    private fun runSeed(seed: Int) {
        val rnd = Random(seed)
        val name = scenarioNames[seed % scenarioNames.size]
        val ctx = "seed $seed ($name)"
        val log = scenarioLog(name)
        val server = TestServer(log.maxOf { DefaultHlcRules.wall(it.hlc) })
        server.emitAfter = { op -> log.filter { it.causedByOpId == op.opId }.map { it.copy(seq = null, causedByOpId = null) } }
        server.verdict = { op -> DeviceOpApplier.missingReference(serverReplica(server), op)?.let { "unknown_reference" to "no $it" } }
        server.pageSize = 1 + rnd.nextInt(5)
        val ids = log.map { it.deviceId }.filter { it != Reserved.SERVER_DEVICE_ID }.distinct() + OBSERVER
        val devices = ids.mapIndexed { i, id -> id to ApplyDevice(id, server, server.nowMs, seed * 31 + i) }.toMap()
        val all = devices.values.toList()
        var next = 0

        /** Puts the next log op in place if everything before it reached the server; true if it did. */
        fun advance(): Boolean {
            while (next < log.size && log[next].causedByOpId != null && log[next].seq!! <= server.highWaterSeq) next++
            if (next >= log.size) return false
            val e = log[next]
            if (server.highWaterSeq < e.seq!! - 1) return false
            if (e.deviceId == Reserved.SERVER_DEVICE_ID) {
                if (e.causedByOpId != null) return false // emitted with its cause
                server.append(e.copy(seq = null, causedByOpId = null), null)
            } else {
                val d = devices.getValue(e.deviceId)
                if (d.state(SyncStateKeys.REBUILD_PENDING) != null) return false // minting is blocked
                d.inject(e)
            }
            next++
            return true
        }

        repeat(STEPS) {
            val d = all[rnd.nextInt(all.size)]
            when (rnd.nextInt(14)) {
                in 0..4 -> advance()
                in 5..8 -> d.cycle()
                9, 10 -> d.transport.offline = !d.transport.offline
                11 -> when (rnd.nextInt(3)) {
                    0 -> d.transport.loseNextPushResponse = true
                    1 -> d.transport.failNextPushBeforeServer = true
                    // a replayed page answers an older `since`; only meaningful while pull_seq moves forward
                    // (after a rebuild's wipe it would be a response to another request, not a replay)
                    else -> if (d.state(SyncStateKeys.REBUILD_PENDING) == null) {
                        d.transport.replayNextPull = true
                        d.cycle()
                        d.transport.replayNextPull = false
                    }
                }
                12 -> if (rnd.nextInt(3) == 0) d.store.transaction { it.bookkeeping.rebuildPending = true }
                else -> server.pageSize = 1 + rnd.nextInt(5)
            }
        }
        // final: everyone online, no faults; the rest of the log; two clean cycles each
        for (d in all) {
            d.transport.offline = false
            d.transport.loseNextPushResponse = false
            d.transport.failNextPushBeforeServer = false
            d.transport.replayNextPull = false
        }
        var guard = 0
        while (next < log.size) {
            if (!advance()) all.forEach { it.cycle() }
            check(guard++ < 1_000) { "$ctx: the log does not progress" }
        }
        repeat(2) { for (d in all) assertIs<CycleResult.Completed>(d.cycle(), ctx) }

        assertEquals(log, server.loggedOps(), "$ctx: server log")
        val serverTables = serverReplica(server)
        val want = projection(serverTables)
        assertScenarioExpected(name, serverTables, DeviceCardCache(), "$ctx server")
        for (d in all) {
            val t = d.tables()
            assertEquals(emptyList(), diffRows(want, projection(t)), "$ctx: ${d.deviceId} diverged")
            assertScenarioExpected(name, t, d.cache, "$ctx ${d.deviceId}")
            assertTrue(d.store.rows().none { it.state != PendingState.ACKED }, "$ctx: ${d.deviceId} has unpushed or rejected rows")
            assertNull(d.state(SyncStateKeys.REBUILD_PENDING), ctx)
            assertEquals(emptyList(), d.diagnostics.refusals, ctx)
        }
    }

    private companion object {
        const val SEEDS = 112
        const val STEPS = 120
        const val OBSERVER = "01931f3e-0000-7000-8000-0000000000c3"
    }
}
