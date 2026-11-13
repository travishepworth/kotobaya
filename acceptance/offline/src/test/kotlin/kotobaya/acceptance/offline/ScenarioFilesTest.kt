package kotobaya.acceptance.offline

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.TransportResponse
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * SYNC.md 12.4 (every conflict case) and 13: each scenario file of `contracts/sync/examples/scenarios`
 * played through real devices — each device op is put in its phone's outbox with its local effect and
 * pushed at its place in the log (server clock = `wall(hlc)`, the merges are the server's own) — then
 * every phone syncs. The server's log must carry the scenario's verdicts, every replica must hold the
 * scenario's `expected` rows, absences and replay sets, and the full oracle must pass. Then every
 * `rejected[]` op is pushed and must get its code.
 */
class ScenarioFilesTest {
    private val dir: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "contracts/sync/examples/scenarios") }.first { it.isDirectory }

    @Test
    fun everyScenarioFileThroughRealDevices() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertEquals(16, files.size, "scenario files (SYNC.md 12.4)")
        val failures = ArrayList<String>()
        for (f in files) {
            try {
                play(f)
            } catch (e: Throwable) {
                failures += "${f.name}: ${e.message?.take(1500)}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    private fun play(f: File) {
        val name = f.name
        val doc = DefaultCanonicalJson.decode(f.readText()).value as JsonObject
        val log = (doc.members.getValue("log") as JsonArray).items.map { decode(it, OpSource.PULLED, name) }
        val start = log.minOf { DefaultHlcRules.wall(it.hlc) }
        val server = SimServer.create(start)
        server.use {
            val world = World(start)
            val devices = LinkedHashMap<String, SimDevice>()
            fun device(id: String) = devices.getOrPut(id) { SimDevice("d${id.takeLast(2)}", id, server, world, devices.size + 1) }
            val sim = Sim(server, world, emptyList())
            for (op in log) {
                // the server's clock when it judged the op: the scenario minted its consequences then
                val caused = log.firstOrNull { it.causedByOpId == op.opId }
                sim.at(maxOf(world.now, DefaultHlcRules.wall((caused ?: op).hlc)))
                when {
                    op.deviceId != Reserved.SERVER_DEVICE_ID -> {
                        // the merge's server-created cards take the scenario's ids
                        log.filter { it.causedByOpId == op.opId && it.opType.wire == "card.created" }.forEach { server.scriptedCardIds.addLast(it.p("id")!!) }
                        val d = device(op.deviceId)
                        d.inject(op)
                        assertIs<CycleResult.Completed>(d.pushOnly(), "$name: push of seq ${op.seq}")
                        assertEquals(0, d.count(kotobaya.sync.client.store.PendingState.REJECTED), "$name: seq ${op.seq} was rejected: ${d.rows().map { it.lastError }}")
                    }
                    op.causedByOpId == null -> server.emit(op.opType, op.payload) // job output (e.g. confusable.suggested)
                    else -> Unit // emitted by the server itself
                }
            }
            val got = server.log()
            assertEquals(log.map { it.opType.wire to it.causedByOpId?.let { "caused" } }, got.map { it.opType.wire to it.causedByOpId?.let { "caused" } }, "$name: the server's verdicts")
            repeat(2) { for (d in devices.values) assertIs<CycleResult.Completed>(d.sync(), "$name: ${d.name} sync") }
            val expected = doc.members.getValue("expected") as JsonObject
            val serverMinted = log.any { it.deviceId == Reserved.SERVER_DEVICE_ID && it.causedByOpId == null }
            assertExpected(expected, replicaOf(got), "$name (server log)", serverMinted)
            for (d in devices.values) assertExpected(expected, d.tables(), "$name (${d.name})", serverMinted)
            val all = Sim(server, world, devices.values.toList())
            assertEquals(emptyList(), all.check(), "$name: oracle")
            for (r in (doc.members["rejected"] as? JsonArray)?.items.orEmpty()) {
                val m = (r as JsonObject).members
                val raw = (m.getValue("op") as JsonObject).members
                val opLabel = "${(raw["op_type"] as? JsonString)?.value} ${(raw["op_id"] as? JsonString)?.value}"
                if (m["after_seq"] != null) continue // judged against an earlier state (remint-scenarios convention, C-8)
                server.nowMs = (m["received_at"] as? JsonNumber)?.value?.toLong() ?: world.now
                val body = DefaultCanonicalJson.encode(JsonObject(mapOf("ops" to JsonArray(listOf(m.getValue("op"))))))
                val resp = assertIs<TransportResponse.Ok>(server.push((raw.getValue("device_id") as JsonString).value, body), "$name: push of a rejected op")
                val result = ((DefaultCanonicalJson.decode(resp.body).value as JsonObject).members.getValue("results") as JsonArray).items.single() as JsonObject
                val code = ((result.members["error"] as? JsonObject)?.members?.get("code") as? JsonString)?.value
                assertEquals((m.getValue("code") as JsonString).value, code, "$name: verdict for $opLabel")
            }
        }
    }

    private fun decode(v: JsonValue, src: OpSource, name: String): Op = assertIs<OpDecodeResult.Known>(DefaultOpCodec.decodeTree(v, src), name).op

    private fun table(name: String) = LearningTable.entries.single { it.sqlName == name }

    private fun matches(row: Row, partial: Map<String, JsonValue>) = partial.all { (k, v) -> (row[k] ?: JsonNull) == v }

    /**
     * `expected.rows` / `absent` / `replay_sets` against [t]. With server-minted job ops in the log
     * ([serverMinted]) the server mints them with its own clock, so HLC-derived columns of rows they
     * write (`clocks`, `updated_at`, `created_at`) are not compared.
     */
    private fun assertExpected(expected: JsonObject, t: LearningTables, context: String, serverMinted: Boolean) {
        val hlcDerived = setOf("clocks", "updated_at", "created_at")
        (expected.members["rows"] as? JsonObject)?.members?.forEach { (tableName, list) ->
            val table = table(tableName)
            for (item in (list as JsonArray).items) {
                val partial = (item as JsonObject).members
                val key = table.keyColumns.associateWith { partial[it] ?: fail("$context: expected $tableName row without key column $it") }
                val row = t.all(table).singleOrNull { matches(it, key) }
                assertNotNull(row, "$context: $tableName row $key missing")
                for ((col, v) in partial) {
                    if (serverMinted && tableName == "confusable" && col in hlcDerived) continue
                    assertEquals(v, row[col] ?: JsonNull, "$context: $tableName $key column $col")
                }
            }
        }
        (expected.members["absent"] as? JsonObject)?.members?.forEach { (tableName, list) ->
            for (item in (list as JsonArray).items) {
                val partial = (item as JsonObject).members
                assertNull(t.all(table(tableName)).firstOrNull { matches(it, partial) }, "$context: $tableName row $partial must be absent")
            }
        }
        (expected.members["replay_sets"] as? JsonObject)?.members?.forEach { (cardId, ids) ->
            val want = (ids as JsonArray).items.map { (it as JsonString).value }
            assertEquals(want, DeviceCardCache().replaySet(t, cardId).map { it.id }, "$context: replay set of $cardId")
        }
    }
}
