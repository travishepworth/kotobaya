package kotobaya.sync.client.remint

import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.diffRows
import kotobaya.sync.client.apply.projection
import kotobaya.sync.client.apply.serverReplica
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.store.PendingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNC.md 13 "Re-mint scenarios": each file's `rejected` rows, with their codes, go through the
 * device's step-1 selection and the whole Re-mint against a test server. Checked: `E` (with the
 * step-6a payloads, re-keyed where 10.4 says so) and `R` are minted exactly as the scenario's log
 * (op ids scripted; hlc, entity id, payload derived), the discard list is the scenario's
 * `discarded`, and after push + rebuild the device and the server hold `expected` and agree.
 */
class RemintScenarioTest {
    @Test
    fun everyRemintScenarioHasFourFiles() {
        assertEquals(
            listOf("remint-deck-items.json", "remint-preset-set-existing-card.json", "remint-word-status.json", "clock-behind-remint.json"),
            RemintScenario.names,
        )
    }

    @Test
    fun selectionMatchesEveryScenario() {
        for (name in RemintScenario.names) {
            val sc = RemintScenario(name)
            val stage = RemintStage(sc)
            val rows = stage.a.d.store.rows()
            val sel = RemintSelection.of(rows)
            assertEquals(sc.creationsFrom, sel.remint.map { it.row.opId }, "$name: E")
            assertEquals(sc.reviewsFrom, sel.reviews.map { it.opId }, "$name: R")
            assertEquals(sc.creationsTo.size, sel.planned().sumOf { it.size }, "$name: minted count of E")
            val notReminted = sel.deleted - sc.creationsFrom.toSet() - sc.reviewsFrom.toSet()
            assertEquals(sc.discarded.toSet(), notReminted, "$name: discarded")
        }
    }

    @Test
    fun remintScenarioDeckItems() = play("remint-deck-items.json")

    @Test
    fun remintScenarioWordStatus() = play("remint-word-status.json")

    @Test
    fun remintScenarioPresetSetExistingCard() = play("remint-preset-set-existing-card.json")

    @Test
    fun clockBehindRemintScenario() = play("clock-behind-remint.json")

    private fun play(name: String) {
        val sc = RemintScenario(name)
        val stage = RemintStage(sc)
        val a = stage.a
        // discard list: exactly the scenario's discarded rows
        val preview = a.service.previewRemint()
        assertNull(preview.blocked, "$name: Re-mint available")
        assertEquals(sc.discarded, preview.discards.map { it.row.opId }, "$name: discard list")
        assertEquals(sc.creationsFrom.size, preview.remintedOps, name)
        assertEquals(sc.reviewsFrom.size, preview.remintedReviews, name)

        stage.scriptIds()
        val out = assertIs<RemintOutcome.Reminted>(a.service.remint(preview), name)
        assertEquals(sc.discarded, out.discarded, "$name: discarded")
        assertEquals(sc.offset, out.delta, "$name: delta = remint_offset (the batch fits)")
        // E and R minted exactly as the scenario's log (op id scripted; everything else derived)
        val want = sc.remintedLog.map { it.copy(seq = null, causedByOpId = null) }
        assertEquals(want.map { it.opId }, out.minted.map { it.opId }, "$name: mint order")
        for ((w, g) in want.zip(out.minted)) assertEquals(w, g, "$name: minted ${w.opId}")
        assertEquals("1", a.d.state("rebuild_pending"), name)
        assertNull(a.d.state("remint_offset"), name)
        assertTrue(a.d.store.rows().none { it.state == PendingState.REJECTED }, "$name: no rejected row left")

        // push + rebuild; then every replica agrees and holds `expected`
        assertIs<CycleResult.Completed>(a.cycle(), name)
        assertNull(a.d.state("rebuild_pending"), name)
        for (dev in stage.devices.values) assertIs<CycleResult.Completed>(dev.cycle(), name)
        assertEquals(sc.log, stage.server.loggedOps(), "$name: server log")
        val serverTables = serverReplica(stage.server)
        assertExpected(sc.expected, serverTables, DeviceCardCache(), "$name server")
        val wantProjection = projection(serverTables)
        for (dev in stage.devices.values) {
            assertEquals(emptyList(), diffRows(wantProjection, projection(dev.d.tables())), "$name: ${dev.d.deviceId} diverged")
            assertExpected(sc.expected, dev.d.tables(), dev.d.cache, "$name ${dev.d.deviceId}")
        }
    }
}
