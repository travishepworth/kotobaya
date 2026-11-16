package kotobaya.sync.client.apply

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.InMemoryDeviceStore

/**
 * ADR-035 on the device apply path, with the ops of scenario `reading-retired-offline-review`:
 * `auto_status` ignores retired reading cards, and applying `settings.set cards.reading_enabled`
 * re-derives every vocab entry (op by op and in a pull batch alike).
 */
class ReadingRetiredApplyTest {
    private val name = "reading-retired-offline-review.json"
    private val log: List<Op> = scenarioLog(name)
    private fun seq(n: Long): Op = log.single { it.seq == n }
    private val entry: List<JsonValue> = listOf(JsonNumber(1358280.0))

    private fun status(t: LearningTables): Pair<String?, String?>? = t.get(LearningTable.WORD_STATUS, entry)?.let {
        (it["status"] as? JsonString)?.value to (it["auto_status"] as? JsonString)?.value
    }

    @Test
    fun settingFlipsRederiveTheEntryOpByOp() {
        val t = InMemoryLearningTables.seeded()
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        // Note, meaning M, reading R, then B's review of R; no setting row yet (= false, the default).
        for (n in listOf(1L, 2L, 3L, 7L)) applier.applyTo(t, seq(n))
        assertNull(status(t), "R is retired: M is new, so the word is new (no row)")
        applier.applyTo(t, seq(4)) // reading_enabled = true
        assertEquals("learning" to "learning", status(t), "R counts again")
        applier.applyTo(t, seq(5)) // false at T+40
        assertNull(status(t), "retired again: back to no row")
        applier.applyTo(t, seq(4)) // the older true arrives again: loses LWW, nothing changes
        assertNull(status(t))
        applier.applyTo(t, seq(6)) // A answers M
        assertEquals("learning" to "learning", status(t), "derived from M alone")
    }

    @Test
    fun wholeScenarioInABatchMatchesExpectedAndOpByOp() {
        val one = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
        val oneApplier = DeviceOpApplier<InMemoryLearningTables>()
        for (op in log) one.transaction { tx -> oneApplier.apply(tx, op, ApplyOrigin.PULLED) }
        assertScenarioExpected(name, one.learning(), oneApplier.cache, "op by op")

        val batched = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
        val batchApplier = DeviceOpApplier<InMemoryLearningTables>()
        batched.transaction { tx -> batchApplier.batch(tx) { log.forEach { batchApplier.apply(tx, it, ApplyOrigin.PULLED) } } }
        assertScenarioExpected(name, batched.learning(), batchApplier.cache, "batch")
        assertEquals(status(one.learning()), status(batched.learning()))
        assertEquals("learning" to "learning", status(batched.learning()))
    }

    @Test
    fun retiredOnlyProgressGivesNewAndEnablingShowsIt() {
        // R answered (learning), M never answered, setting false at the end: the row is removed.
        val t = InMemoryLearningTables.seeded()
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        for (n in listOf(1L, 2L, 3L, 4L, 7L)) applier.applyTo(t, seq(n))
        assertEquals("learning" to "learning", status(t))
        applier.applyTo(t, seq(5))
        assertNull(status(t))
    }
}
