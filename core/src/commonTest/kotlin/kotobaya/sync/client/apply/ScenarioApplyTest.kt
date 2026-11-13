package kotobaya.sync.client.apply

import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.Op
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SYNC.md 13 "Apply function (core, device)": every scenario's log applied in seq order to a seeded
 * replica gives `expected`; every `order_free` scenario gives the same replica (SYNC.md 11
 * projection) under random interleavings of the per-device streams with re-deliveries.
 */
class ScenarioApplyTest {
    @Test
    fun everyScenarioLogInSeqOrderGivesTheExpectedRows() {
        assertTrue(scenarioNames.size >= 16)
        for (name in scenarioNames) {
            val cache = DeviceCardCache()
            val applier = DeviceOpApplier<InMemoryLearningTables>(cache)
            val t = InMemoryLearningTables.seeded()
            for (op in scenarioLog(name)) assertTrue(applier.applyTo(t, op), "$name seq ${op.seq}: missing reference in seq order")
            assertScenarioExpected(name, t, cache, name)
            assertEquals(emptyList(), cache.diagnostics.refusals, name)
        }
    }

    @Test
    fun orderFreeScenariosConvergeUnderRandomInterleavingsAndReDelivery() {
        for (name in scenarioNames) {
            if (scenarioDoc(name).members["order_free"] != JsonBool(true)) continue
            val log = scenarioLog(name)
            val reference = InMemoryLearningTables.seeded().also { t ->
                val a = DeviceOpApplier<InMemoryLearningTables>()
                log.forEach { a.applyTo(t, it) }
            }
            val want = projection(reference)
            for (seed in 0 until INTERLEAVINGS) {
                val got = interleaved(log, Random(seed * 7919 + name.hashCode()))
                assertEquals(want, projection(got), "$name seed $seed")
            }
        }
    }

    /**
     * One random interleaving (SYNC.md 11): each device's ops in their own order, an op applied only
     * once the rows it references exist, and random re-deliveries of ops already applied.
     */
    private fun interleaved(log: List<Op>, rnd: Random): InMemoryLearningTables {
        val cache = DeviceCardCache()
        val a = DeviceOpApplier<InMemoryLearningTables>(cache)
        val t = InMemoryLearningTables.seeded()
        val streams = log.groupBy { it.deviceId }.values.map { ArrayDeque(it) }
        val done = ArrayList<Op>()
        while (streams.any { it.isNotEmpty() }) {
            if (done.isNotEmpty() && rnd.nextInt(4) == 0) {
                a.applyTo(t, done[rnd.nextInt(done.size)])
                continue
            }
            val ready = streams.filter { it.isNotEmpty() && DeviceOpApplier.missingReference(t, it.first()) == null }
            check(ready.isNotEmpty()) { "no stream can proceed" }
            val op = ready[rnd.nextInt(ready.size)].removeFirst()
            a.applyTo(t, op)
            done += op
        }
        assertEquals(emptyList(), cache.diagnostics.refusals)
        return t
    }

    private companion object {
        const val INTERLEAVINGS = 60
    }
}
