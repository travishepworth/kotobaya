package kotobaya.sync.client.remint

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
import kotobaya.sync.client.apply.ApplyDevice
import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.protocol.ClientScenarios
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.TestServer
import kotobaya.sync.client.protocol.runSync
import kotobaya.sync.client.rejected.RejectedOpsService
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/** A Re-mint scenario file (SYNC.md 13): `contracts/sync/examples/remint-scenarios/` or `scenarios/clock-behind-remint`. */
class RemintScenario(val name: String) {
    val doc: JsonObject = DefaultCanonicalJson.decode(
        RemintScenarios.files[name] ?: ClientScenarios.files.getValue(name),
    ).value as JsonObject

    val log: List<Op> = arr("log").map { decode(it, OpSource.PULLED) }

    class Rejected(val op: Op, val code: String, val afterSeq: Long?, val receivedAt: Long?)

    val rejected: List<Rejected> = arr("rejected").map {
        val m = (it as JsonObject).members
        Rejected(
            decode(m.getValue("op"), OpSource.PUSHED),
            (m.getValue("code") as JsonString).value,
            (m["after_seq"] as? JsonNumber)?.value?.toLong(),
            (m["received_at"] as? JsonNumber)?.value?.toLong(),
        )
    }

    private val remint = doc.members.getValue("remint") as JsonObject
    val offset: Long = long(remint, "offset_ms")
    val now: Long = long(remint, "now")
    val reviewsFrom: List<String> = ids(remint, "from")
    val reviewsTo: List<String> = ids(remint, "to")
    val creationsFrom: List<String> = ids(remint, "creations_from")
    val creationsTo: List<String> = ids(remint, "creations_to")
    val discarded: List<String> = ids(remint, "discarded")
    val expected: JsonObject = doc.members.getValue("expected") as JsonObject

    /** Device id of the re-minting device (the one whose ops were rejected). */
    val deviceA: String = rejected.first().op.deviceId

    /** The log ops minted by the Re-mint, in log order. */
    val remintedLog: List<Op> = log.filter { it.opId in creationsTo || it.opId in reviewsTo }

    /** The log before the Re-mint (everything the server accepted on its own). */
    val prefix: List<Op> = log.filter { it.opId !in creationsTo && it.opId !in reviewsTo }

    private fun arr(k: String) = (doc.members.getValue(k) as JsonArray).items

    private fun long(o: JsonObject, k: String) = (o.members.getValue(k) as JsonNumber).value.toLong()

    private fun ids(o: JsonObject, k: String): List<String> =
        (o.members[k] as? JsonArray)?.items?.map { (it as JsonString).value } ?: emptyList()

    private fun decode(v: JsonValue, src: OpSource): Op = assertIs<OpDecodeResult.Known>(DefaultOpCodec.decodeTree(v, src), name).op

    companion object {
        /** Every Re-mint scenario: the three of `remint-scenarios/` and `clock-behind-remint`. */
        val names: List<String> = RemintScenarios.files.keys.sorted() + "clock-behind-remint.json"
    }
}

/** A device with the real apply, plus the [RejectedOpsService] under test (review ids from the device's id script). */
class RemintDevice(id: String, server: TestServer, clockMs: Long, seed: Int = 1) {
    val d = ApplyDevice(id, server, clockMs, seed)
    val service = RejectedOpsService(d.store, d.minter, d.clock, d.ids)

    fun cycle(): CycleResult = d.cycle()

    fun push(): CycleResult = runSync { d.engine.pushAll() }
}

/**
 * The scenario up to the Re-mint, through a [TestServer]: the prefix of the log is pushed by its
 * devices; A puts its window ops in its outbox (local effects applied) and pushes them with its clock
 * at `received_at − offset` while the server answers the scenario's codes (so `remint_offset` is
 * the scenario's); then the clock is fixed and A syncs once (the response shows it correct).
 */
class RemintStage(val sc: RemintScenario) {
    val server = TestServer(sc.prefix.maxOf { DefaultHlcRules.wall(it.hlc) })
    val devices = LinkedHashMap<String, RemintDevice>()
    val a: RemintDevice get() = devices.getValue(sc.deviceA)

    fun device(id: String) = devices.getOrPut(id) { RemintDevice(id, server, server.nowMs, devices.size + 1) }

    init {
        device(sc.deviceA)
        for (op in sc.prefix) {
            if (op.deviceId == Reserved.SERVER_DEVICE_ID) {
                if (op.causedByOpId == null) server.append(op.copy(seq = null, causedByOpId = null), null)
                continue
            }
            val dev = device(op.deviceId)
            dev.d.inject(op)
            assertIs<CycleResult.Completed>(dev.push(), "${sc.name} prefix ${op.seq}")
        }
        for (dev in devices.values) assertIs<CycleResult.Completed>(dev.cycle(), sc.name)
        // the window: A's ops with the wrong clock, refused with the scenario's codes
        val codes = sc.rejected.associate { it.op.opId to it.code }
        server.verdict = { op -> codes[op.opId]?.let { it to "scripted" } }
        val received = sc.rejected.first().receivedAt ?: sc.now
        server.nowMs = received
        a.d.clock.now = received - sc.offset
        for (r in sc.rejected) a.d.inject(r.op)
        assertIs<CycleResult.Completed>(a.push(), sc.name)
        assertEquals(sc.offset.toString(), a.d.state("remint_offset"), "${sc.name}: remint_offset")
        // the user fixes the clock; the next response shows it correct
        server.nowMs = sc.now
        a.d.clock.now = sc.now
        assertIs<CycleResult.Completed>(a.cycle(), sc.name)
    }

    /** Scripts A's id source with the scenario's re-minted op ids (and the new review ids) in mint order. */
    fun scriptIds() {
        val byId = sc.log.associateBy { it.opId }
        for (id in sc.creationsTo) a.d.ids.queue.addLast(id)
        for (id in sc.reviewsTo) {
            a.d.ids.queue.addLast((byId.getValue(id).payload.members.getValue("id") as JsonString).value)
            a.d.ids.queue.addLast(id)
        }
    }
}

private fun tableNamed(name: String): LearningTable = LearningTable.entries.single { it.sqlName == name }

private fun matches(row: Row, partial: Map<String, JsonValue>): Boolean = partial.all { (k, v) -> (row[k] ?: JsonNull) == v }

/** `expected.rows`, `expected.absent`, `expected.replay_sets` of a scenario document against [t]. */
fun assertExpected(expected: JsonObject, t: LearningTables, cache: DeviceCardCache, context: String) {
    (expected.members["rows"] as? JsonObject)?.members?.forEach { (tableName, list) ->
        val table = tableNamed(tableName)
        for (item in (list as JsonArray).items) {
            val partial = (item as JsonObject).members
            val key = table.keyColumns.associateWith { partial[it] ?: fail("$context: expected $tableName row without key column $it") }
            val row = t.all(table).singleOrNull { matches(it, key) }
            assertNotNull(row, "$context: $tableName row $key missing")
            for ((col, v) in partial) assertEquals(v, row[col] ?: JsonNull, "$context: $tableName $key column $col")
        }
    }
    (expected.members["absent"] as? JsonObject)?.members?.forEach { (tableName, list) ->
        val table = tableNamed(tableName)
        for (item in (list as JsonArray).items) {
            val partial = (item as JsonObject).members
            assertNull(t.all(table).firstOrNull { matches(it, partial) }, "$context: $tableName row $partial must be absent")
        }
    }
    (expected.members["replay_sets"] as? JsonObject)?.members?.forEach { (cardId, ids) ->
        val want = (ids as JsonArray).items.map { (it as JsonString).value }
        assertEquals(want, cache.replaySet(t, cardId).map { it.id }, "$context: replay set of $cardId")
    }
}
