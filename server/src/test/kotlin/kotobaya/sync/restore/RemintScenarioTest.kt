package kotobaya.sync.restore

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.server.json
import kotobaya.sync.PushResult
import kotobaya.sync.fuzz.RealSyncFixture
import kotobaya.sync.fuzz.RefReplica
import kotobaya.sync.fuzz.ServerProjection
import kotobaya.sync.fuzz.SimDevice
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Re-mint scenarios of `contracts/sync/examples/remint-scenarios` (SYNC.md 10.4, 13; C-8) on the server.
 *
 * Each device op of `log` is pushed in seq order at server time `wall(op.hlc)`. Every `rejected[]` op is
 * pushed **when the log has reached its `after_seq`** (13: judged against the state after the log up to that
 * seq, not the whole log), at its `received_at`, and must get its `code`. Then:
 * 1. the server's log is the scenario's (op ids, types, hlcs, payloads);
 * 2. the tables hold `expected` rows and `absent` keys;
 * 3. the server's SYNC.md 11 projection equals an independent [RefReplica] that applied the log;
 * 4. the step-1 selection of the fuzz's [SimDevice] (the port of `model.remint_select`), fed the `rejected`
 *    rows with their codes, gives `remint.creations_from` as E, puts `remint.from` into R, discards exactly
 *    `remint.discarded`, and its step-6a payloads are those of `remint.creations_to` (window-derived deck
 *    items re-keyed `n:<note>`, a re-keyed removal minted twice) — the oracle the fuzz relies on;
 * 5. pushing every log op again is a `duplicate`.
 */
class RemintScenarioTest : RealSyncFixture() {
    private fun scenario(id: String): JsonNode = json.readTree(File(contracts, "examples/remint-scenarios/$id.json"))

    private val pk = mapOf("word_status" to listOf("entry_seq"), "deck_item" to listOf("deck_id", "item_key"))

    private fun keyOf(table: String, partial: JsonNode): Array<Pair<String, Any?>> =
        (pk[table] ?: listOf("id")).map { c ->
            val v = partial[c] ?: error("$table key $c missing")
            c to (if (v.isNumber) v.longValue() else v.asText())
        }.toTypedArray()

    private fun run(id: String) {
        val s = scenario(id)
        val log = s["log"].toList()
        assertTrue(log.none { it["device_id"].asText() == SERVER }, "$id: these scenarios have no server-emitted ops")
        val rejected = s.path("rejected").toList()
        assertTrue(rejected.isNotEmpty(), "$id: a re-mint scenario has rejected ops")

        fun judge(upTo: Long) {
            for (r in rejected.filter { (it["after_seq"]?.asLong() ?: Long.MAX_VALUE) == upTo }) {
                val at = r["received_at"]?.asLong() ?: kotobaya.sync.fuzz.wallOf(log.last()["hlc"].asText())
                assertRejected(r["code"].asText(), pushAsOwner(r["op"], at))
            }
        }
        judge(0)
        for (op in log) {
            assertAccepted(pushAsOwner(op))
            judge(op["seq"].asLong())
        }
        judge(Long.MAX_VALUE)

        // 1. The log.
        val actual = db.txBlocking { dsl ->
            dsl.fetch("select op_id::text, op_type, hlc::text, payload::text from app.change order by seq")
                .map { r -> List(4) { r.get(it, String::class.java) } }
        }
        assertEquals(log.size, actual.size, "$id: number of log entries")
        for ((want, got) in log.zip(actual)) {
            val label = "$id seq ${want["seq"]}"
            assertEquals(want["op_id"].asText(), got[0], label)
            assertEquals(want["op_type"].asText(), got[1], label)
            assertEquals(want["hlc"].asText(), got[2], label)
            assertEquals(want["payload"], json.readTree(got[3]), "$label payload")
        }

        // 2. Expected rows and absent keys.
        for ((table, rows) in s["expected"].path("rows").fields()) {
            for (exp in rows) {
                val row = row(table, *keyOf(table, exp))
                assertNotNull(row, "$id: $table $exp missing")
                for ((col, v) in exp.fields()) assertJsonEquals(v, row[col], "$id: $table.$col of ${keyOf(table, exp).toList()}")
            }
        }
        for ((table, keys) in s["expected"].path("absent").fields()) {
            for (k in keys) assertNull(row(table, *keyOf(table, k)), "$id: $table $k must not exist")
        }

        // 3. Convergence with an independent replica (caches only when every card uses the Default preset).
        val ref = RefReplica().also { r -> log.forEach(r::apply) }
        val defaultPresetOnly = log.none { it["op_type"].asText().startsWith("fsrs_params.") }
        fun strip(p: Map<String, Map<String, Any?>>) = if (defaultPresetOnly) p else p.mapValues { (table, rows) ->
            if (table != "card") rows else rows.mapValues { (_, r) -> (r as Map<*, *>).filterKeys { it != "cache" } }
        }
        assertEquals(emptyList(), ServerProjection.diff(strip(ServerProjection.of(db)), strip(ref.projection())), "$id: server vs replica")

        // 4. The step-1 selection and the step-6a payloads of the SimDevice port.
        val rm = s["remint"]
        val rows = rejected.map { SimDevice.Row(it["op"].deepCopy<ObjectNode>(), "rejected", lastError = it["code"].asText()) }
        val sel = SimDevice.remintSelect(rows)
        fun ids(rs: List<SimDevice.Row>) = rs.map { it.op["op_id"].asText() }
        assertEquals(rm["creations_from"].map { it.asText() }, ids(sel.e), "$id: E")
        val from = rm["from"].map { it.asText() }
        assertTrue(ids(sel.r).containsAll(from), "$id: remint.from ⊆ R")
        val gone = (sel.c + sel.d + sel.u + sel.e + sel.r).sortedBy { it.op["hlc"].asText() }.let(::ids).distinct()
            .filter { it !in ids(sel.e) && it !in from }
        assertEquals(rm["discarded"].map { it.asText() }, gone, "$id: discarded")
        val byId = log.associateBy { it["op_id"].asText() }
        val minted = ids(sel.e).flatMap { i -> sel.payloads.getValue(i).map { i to it } }
        val to = rm["creations_to"].map { it.asText() }
        assertEquals(to.size, minted.size, "$id: one creations_to per step-6a op")
        val originals = rows.associateBy { it.op["op_id"].asText() }
        for ((pair, j) in minted.zip(to)) {
            val (i, payload) = pair
            assertEquals(originals.getValue(i).op["op_type"].asText(), byId.getValue(j)["op_type"].asText(), "$id: type of $j")
            assertEquals(byId.getValue(j)["payload"], payload as JsonNode, "$id: $j is the step-6a op for $i")
        }

        // 5. Idempotency (7.8).
        val before = count("select count(*) from app.change")
        for (op in log) assertEquals(PushResult.DUPLICATE, pushAsOwner(op).status)
        assertEquals(before, count("select count(*) from app.change"))
    }

    @Test fun remintDeckItems() = run("remint-deck-items")

    @Test fun remintPresetSetExistingCard() = run("remint-preset-set-existing-card")

    @Test fun remintWordStatus() = run("remint-word-status")

    @Test
    fun everyRemintScenarioFileIsCoveredHere() {
        val files = File(contracts, "examples/remint-scenarios").listFiles()!!.map { it.name.removeSuffix(".json") }.toSet()
        val tests = RemintScenarioTest::class.java.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.size - 1
        assertEquals(files.size, tests, "a new re-mint scenario file must get a test here: $files")
    }
}
