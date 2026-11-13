package kotobaya.sync.client.apply

import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.fsrs.FsrsScheduler
import kotobaya.replay.DefaultReplayEngine
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
        for (seed in 0 until SEEDS) {
            val name = scenarioNames[seed % scenarioNames.size]
            runSeed(seed, name, scenarioLog(name), checkExpected = true)
        }
    }

    /**
     * BUG-6: scenario `reviews-same-card-offline` with an imported jpdb history of its card spliced in
     * after `card.created` (server-minted `review.logged` ops with `import_source` and `replay_hlc`,
     * answered weeks before the devices' reviews). Devices fold their own reviews first and receive
     * the imports later (full replay, out of order), or the other way round; every replica's card
     * cache must still equal the server replica's (SYNC.md 9.5, 11). The scenario's `expected`
     * replay set no longer applies; instead the replay set must hold the imports first.
     */
    @Test
    fun replicasConvergeWithImportedHistoryMixedIn() {
        val name = "reviews-same-card-offline.json"
        val log = withImportedHistory(scenarioLog(name))
        for (seed in 0 until IMPORT_SEEDS) runSeed(1000 + seed, name, log, checkExpected = false)
        // the history matters: under the pre-BUG-6 core the same log gives another cache
        val server = serverReplica(log)
        val card = log.first { it.opType.wire == "card.created" }.entityId
        val set = DeviceCardCache().replaySet(server, card)
        val ordered = DefaultReplayEngine.replaySet(set)
        assertEquals(IMPORT_GRADES.size, ordered.takeWhile { it.importSource == "jpdb" }.size, "imports sort first")
        val preset = FsrsScheduler.paramsOf(DeviceCardCache.presetRowOf(server.get(LearningTable.FSRS_PARAMS, listOf(JsonString(Reserved.DEFAULT_PRESET_ID)))!!))
        assertTrue(DefaultReplayEngine.replay(set, preset) != LegacyFsrs61Engine.replay(set, preset), "the rule changes this card")
        // after the imports alone the card is in REVIEW (it would be stuck in learning before BUG-6)
        assertEquals(CardState.REVIEW, DefaultReplayEngine.replay(ordered.take(IMPORT_GRADES.size), preset).schedule.state)
        assertEquals(CardState.LEARNING, LegacyFsrs61Engine.replay(ordered.take(IMPORT_GRADES.size), preset).schedule.state)
    }

    /** [log] with [IMPORT_GRADES] spliced in after its first `card.created`, seqs renumbered. */
    private fun withImportedHistory(log: List<Op>): List<Op> {
        val at = log.indexOfFirst { it.opType.wire == "card.created" }
        val card = log[at]
        val firstReview = log.first { it.opType.wire == "review.logged" }
        val answered = (firstReview.payload.members.getValue("reviewed_at") as JsonNumber).value.toLong()
        val minted = DefaultHlcRules.wall(card.hlc) + 1
        val imports = IMPORT_GRADES.mapIndexed { i, (daysBefore, rating) ->
            ImportOps.review(card.entityId, 100 + i, answered - daysBefore * Reserved.DAY_MS, rating, minted).copy(hlc = ImportOps.serverHlc(minted, i))
        }
        val spliced = log.take(at + 1) + imports + log.drop(at + 1)
        return spliced.mapIndexed { i, op -> op.copy(seq = (i + 1).toLong()) }
    }

    private fun serverReplica(log: List<Op>): InMemoryLearningTables {
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        return InMemoryLearningTables.seeded().also { t -> for (op in log) applier.applyTo(t, op) }
    }

    private fun runSeed(seed: Int, name: String, log: List<Op>, checkExpected: Boolean) {
        val rnd = Random(seed)
        val ctx = "seed $seed ($name)"
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
        if (checkExpected) assertScenarioExpected(name, serverTables, DeviceCardCache(), "$ctx server")
        for (d in all) {
            val t = d.tables()
            assertEquals(emptyList(), diffRows(want, projection(t)), "$ctx: ${d.deviceId} diverged")
            if (checkExpected) assertScenarioExpected(name, t, d.cache, "$ctx ${d.deviceId}")
            assertTrue(d.store.rows().none { it.state != PendingState.ACKED }, "$ctx: ${d.deviceId} has unpushed or rejected rows")
            assertNull(d.state(SyncStateKeys.REBUILD_PENDING), ctx)
            assertEquals(emptyList(), d.diagnostics.refusals, ctx)
        }
    }

    private companion object {
        const val SEEDS = 112
        const val STEPS = 120
        const val OBSERVER = "01931f3e-0000-7000-8000-0000000000c3"
        const val IMPORT_SEEDS = 40

        /** (days before the first device review, rating): stuck in learning before BUG-6, REVIEW after. */
        val IMPORT_GRADES: List<Pair<Long, Int>> = listOf(30L to 1, 29L to 3, 27L to 2, 23L to 2, 10L to 1, 8L to 2)
    }
}
