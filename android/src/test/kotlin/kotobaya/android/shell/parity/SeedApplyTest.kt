package kotobaya.android.shell.parity

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.bookkeeping

/**
 * SHELL.md 8.2 seeding: the seed log (and every apply step) reaches the replica through the production pull-apply path
 * (SyncEngine.pullAll + the device applier), never through `pending_op` and never by direct table writes.
 */
class SeedApplyTest {
    @Test
    fun everySeedIsAppliedThroughThePullPathNotTheOutbox() {
        for (s in ParityScenario.all()) {
            val d = ParityDevice(s.deviceId, s.clockMs, emptyList())
            d.pull(s.seedLog, "${s.id} seed")
            val outbox = d.store.read { tx -> PendingState.entries.associateWith { tx.outbox.count(it) } }
            assertEquals(PendingState.entries.associateWith { 0 }, outbox, "${s.id}: pulled ops must not be in pending_op")
            assertEquals(s.seedLog.size.toLong(), d.store.read { it.bookkeeping.pullSeq }, "${s.id}: pull_seq = ops applied")
            assertEquals(SeedLog.LOG_EPOCH, d.store.read { it.bookkeeping.logEpoch }, s.id)
            assertTrue(d.log.pulls >= 1, s.id)
            assertEquals(0, d.localChanges, s.id)
            if (s.seedLog.isNotEmpty()) {
                assertEquals(ParityJson.str(s.seedLog.last().members["hlc"]), d.store.read { it.bookkeeping.hlcLast }, "${s.id}: hlc observed")
            }
            assertCreatedRowsExist(s.id, d, s.seedLog)
        }
    }

    @Test
    fun applyStepsContinueTheSeqsAfterTheSeed() {
        val withApply = ParityScenario.all().filter { s -> s.steps.indices.any { s.isApply(it) } }
        assertTrue(withApply.isNotEmpty(), "no scenario has an apply step")
        for (s in withApply) {
            val exec = ParityRunner.execute(s, emptyList())
            val applied = s.steps.indices.filter { s.isApply(it) }.sumOf { s.applyOps(it).size }
            assertEquals((s.seedLog.size + applied).toLong(), exec.device.store.read { it.bookkeeping.pullSeq }, s.id)
            assertEquals(s.seedLog.size + applied, exec.device.log.size, s.id)
            assertEquals(0, exec.device.store.read { tx -> tx.outbox.count(PendingState.PENDING) }, s.id)
        }
    }

    @Test
    fun theEmptySeedLeavesOnlyTheBuiltInRows() {
        val s = ParityScenario.load("empty-replica")
        assertEquals(0, s.seedLog.size)
        val d = ParityDevice(s.deviceId, s.clockMs, emptyList())
        d.pull(emptyList(), "seed")
        val t = d.tables()
        assertEquals(0, t.all(LearningTable.NOTE).size)
        assertEquals(0, t.all(LearningTable.CARD).size)
        assertEquals(0, t.all(LearningTable.REVIEW_LOG).size)
        assertTrue(t.all(LearningTable.NOTE_TYPE).isNotEmpty(), "built-in note types")
        assertEquals(s.deviceId, d.store.read { it.syncState.get(kotobaya.sync.client.store.SyncStateKeys.DEVICE_ID) })
    }

    @Test
    fun theInstalledDictionaryHoldsEveryDictEntryAndNoDictMissing() {
        val dict = ParityDictionary.fixture
        val d = ParityDevice("01931f3e-0000-7000-8000-0000000000d4", 1_795_000_000_000L, emptyList())
        assertTrue(d.dictStore.installed.isFile)
        DriverManager.getConnection("jdbc:sqlite:${d.dictStore.installed.path}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { it.next(); assertEquals(1, it.getInt(1)) }
                st.executeQuery("SELECT id, scope FROM dict_release").use {
                    assertTrue(it.next())
                    assertEquals(dict.releaseId, it.getLong(1))
                    assertEquals("all", it.getString(2))
                }
                st.executeQuery("SELECT count(*) FROM dict_entry").use { it.next(); assertEquals(dict.seqs.size, it.getInt(1)) }
                st.executeQuery("SELECT pos, kanji_forms FROM dict_entry WHERE seq = 1000160").use {
                    assertTrue(it.next())
                    assertEquals("[\"n\"]", it.getString(1))
                    assertTrue(it.getString(2).startsWith("[{"))
                }
            }
        }
        for (s in ParityScenario.all()) {
            s.dictEntries.forEach { assertTrue(it in dict.seqs, "${s.id}: dict_entries $it is not in the fixture") }
            s.dictMissing.forEach { assertTrue(it !in dict.seqs, "${s.id}: dict_missing $it is in the fixture") }
        }
    }

    @Test
    fun postgresArrayLiteralsBecomeJsonArrays() {
        assertEquals("[]", ParityDictionary.arrayJson("{}"))
        assertEquals("""["n","adj-i"]""", ParityDictionary.arrayJson("{n,adj-i}"))
        assertEquals("""["rank next","say \"hi\"","a\\b","-ous"]""", ParityDictionary.arrayJson("""{"rank next","say \"hi\"","a\\b",-ous}"""))
    }

    /** Every `*.created` / `review.logged` op of the log left its row (keyed by `id`) in the learning tables. */
    private fun assertCreatedRowsExist(id: String, d: ParityDevice, log: List<kotobaya.contracts.sync.JsonObject>) {
        val t = d.tables()
        var checked = 0
        for (op in log) {
            val type = ParityJson.str(op.members["op_type"])
            if (!type.endsWith(".created") && type != "review.logged") continue
            val table = LearningTable.entries.firstOrNull { it.sqlName == ParityJson.str(op.members["entity_type"]) } ?: continue
            if (table.keyColumns != listOf("id")) continue
            val entity = ParityJson.str(op.members["entity_id"])
            assertNotNull(t.all(table).firstOrNull { it["id"] == JsonString(entity) }, "$id: ${table.sqlName} $entity of $type not applied")
            checked++
        }
        val creates = log.count { val t2 = ParityJson.str(it.members["op_type"]); t2.endsWith(".created") || t2 == "review.logged" }
        assertTrue(checked > 0 || creates == 0, "$id: nothing checked")
    }
}
