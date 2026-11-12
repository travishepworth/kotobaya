package kotobaya.sync.client.protocol

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SYNC.md 12.1–12.4 run through the client (OpMinter + SyncEngine + in-memory store) against the test
 * server. What is checked here is the protocol: the ops the clients mint and push are the scenario's
 * log byte for byte (same seqs, the server's merges after their causes), every replica pulls every op
 * exactly once in seq order, observes every clock, and ends with an empty outbox; rejected ops end
 * `rejected` with their code. The row-level outcome of applying the ops is T303b's.
 */
class WorkedExamplesTest {
    private fun TestDevice.pulled() = store.learning().pulledIds()

    @Test
    fun s12_1_twoDevicesReviewTheSameCardOffline() {
        val h = ScenarioHarness(Scenario("reviews-same-card-offline.json"))
        val a = h.device(h.scenario.bySeq(1).deviceId)
        val b = h.device(h.scenario.bySeq(5).deviceId)
        // card C exists everywhere
        h.mint(1)
        h.mint(2)
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<CycleResult.Completed>(b.cycle())
        // both offline: A reviews at T+10, T+30; B at T+20 and drills at T+25
        h.mint(3)
        h.mint(4)
        h.mint(5)
        h.mint(6)
        assertIs<CycleResult.Completed>(a.push())
        assertIs<CycleResult.Completed>(b.push())
        assertIs<CycleResult.Completed>(b.cycle())
        assertIs<CycleResult.Completed>(a.cycle())
        h.assertServerLogIsTheScenarioLog()
        assertEquals(listOf(0L, 2L), b.transport.pullSinces) // B pull since=2 gets seq 3..6
        assertEquals(listOf(0L, 2L), a.transport.pullSinces)
        val all = h.scenario.log.map { it.opId }
        // nothing is overwritten or dropped: every review reached every replica exactly once
        assertEquals(all, a.pulled())
        assertEquals(all, b.pulled())
        for (d in listOf(a, b)) {
            assertEquals(emptyList(), d.rows())
            assertObservedEverything(h, d)
        }
    }

    @Test
    fun s12_2_editVersusDelete() {
        val h = ScenarioHarness(Scenario("edit-vs-delete.json"))
        val a = h.device(h.scenario.bySeq(1).deviceId)
        val b = h.device(h.scenario.bySeq(3).deviceId)
        h.mint(1)
        h.mint(2)
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<CycleResult.Completed>(b.cycle())
        // A: tags@10, meaning@30, review@31, suspend@32 offline; B: delete N @20
        for (s in 4L..7L) h.mint(s)
        h.mint(3)
        assertIs<CycleResult.Completed>(b.push())
        assertIs<CycleResult.Completed>(a.push())
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<CycleResult.Completed>(b.cycle())
        h.assertServerLogIsTheScenarioLog()
        val delete = h.scenario.bySeq(3)
        assertEquals(OpType.NOTE_DELETED, delete.opType)
        // A applies the delete it pulls; its own later edits are pulled back too (no-ops)
        val aApplied = a.store.learning().applied
        assertTrue(aApplied.any { it.opId == delete.opId && it.origin == ApplyOrigin.PULLED && it.seq == 3L })
        assertEquals(h.scenario.log.drop(2).map { it.opId }, a.pulled().drop(2))
        assertEquals(h.scenario.log.map { it.opId }, b.pulled())
        for (d in listOf(a, b)) {
            assertEquals(emptyList(), d.rows())
            assertObservedEverything(h, d)
        }
    }

    @Test
    fun s12_3_theSameWordAddedOnTwoDevices() {
        val h = ScenarioHarness(Scenario("same-word-added-twice.json"))
        val log = h.scenario.log
        val a = h.device(h.scenario.bySeq(1).deviceId)
        val b = h.device(h.scenario.bySeq(4).deviceId)
        for (s in 1L..3L) h.mint(s)
        assertIs<CycleResult.Completed>(a.cycle())
        // B offline the whole time: creates L with two cards, tags it, suspends a card, reviews it
        for (e in log.filter { it.deviceId == b.deviceId }) h.mint(e.seq!!)
        assertIs<CycleResult.Completed>(b.push())
        // the server merged L into W while accepting B's batch (seq 5, 7, 9, 10 caused by B's ops)
        h.mint(14)
        assertIs<CycleResult.Completed>(a.push())
        assertIs<CycleResult.Completed>(b.cycle())
        assertIs<CycleResult.Completed>(a.cycle())
        h.assertServerLogIsTheScenarioLog()
        assertEquals(log.map { it.opId }, b.pulled())
        assertEquals(log.drop(3).map { it.opId }, a.pulled().drop(3))
        val merges = log.filter { it.deviceId == Reserved.SERVER_DEVICE_ID }.map { it.opType }
        assertEquals(listOf(OpType.NOTE_MERGED, OpType.CARD_MERGED, OpType.CARD_CREATED, OpType.CARD_MERGED), merges)
        // B observed the server's merge clocks: its next HLC is above them
        val serverMax = log.filter { it.deviceId == Reserved.SERVER_DEVICE_ID }.maxOf { it.hlc }
        assertEquals(serverMax, b.state(SyncStateKeys.HLC_LAST))
        val next = b.setting("k.after", 1)
        assertTrue(DefaultHlcRules.compare(next.hlc.substring(0, 17), serverMax.substring(0, 17)) > 0)
        for (d in listOf(a, b)) assertObservedEverything(h, d)
    }

    @Test
    fun s12_4_everyScenarioPlaysThroughTheClients() {
        for (name in ClientScenarios.files.keys.sorted()) {
            val h = ScenarioHarness(Scenario(name))
            h.playLogInOrder()
            h.assertServerLogIsTheScenarioLog()
            h.seedRejected()
            for (d in h.allDevices().toList()) assertIs<CycleResult.Completed>(d.cycle(), name)
            for (d in h.allDevices().toList()) assertIs<CycleResult.Completed>(d.cycle(), name)
            h.assertServerLogIsTheScenarioLog() // no rejected op entered the log
            val expectedPulled = h.scenario.log.filter { it.opType.deviceApplies }.map { it.opId }
            for (d in h.allDevices()) {
                assertEquals(expectedPulled, d.pulled(), "$name ${d.deviceId}: pulled in seq order, each once")
                assertObservedEverything(h, d)
                // only rejected rows remain; each carries its scenario code
                for (row in d.rows()) {
                    assertEquals(PendingState.REJECTED, row.state, "$name ${row.opId}")
                    val code = h.code(row.opId)
                    assertTrue(row.lastError!!.startsWith("$code: "), "$name ${row.opId}: ${row.lastError} expected $code")
                }
                val clockRejected = d.rows().any { h.code(it.opId) == "clock_ahead" || h.code(it.opId) == "clock_behind" }
                assertEquals(clockRejected, d.state(SyncStateKeys.REMINT_OFFSET) != null, "$name remint_offset")
            }
            val rejectedRows = h.allDevices().sumOf { d -> d.rows().size }
            assertEquals(h.scenario.rejected.size, rejectedRows, name)
        }
    }

    private fun assertObservedEverything(h: ScenarioHarness, d: TestDevice) {
        val last = DefaultHlcRules.stateOf(d.state(SyncStateKeys.HLC_LAST))
        val max = DefaultHlcRules.stateOf(h.scenario.log.maxOf { it.hlc.substring(0, 17) + it.hlc.substring(17) })
        assertTrue(last.ms > max.ms || (last.ms == max.ms && last.counter >= max.counter), "${d.deviceId} did not observe the log")
    }
}
