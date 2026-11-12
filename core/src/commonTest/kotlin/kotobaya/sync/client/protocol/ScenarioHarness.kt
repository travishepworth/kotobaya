package kotobaya.sync.client.protocol

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.store.PendingOpRow
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** One scenario of `contracts/sync/examples/scenarios` (SYNC.md 13), as the client tests use it. */
class Scenario(val name: String) {
    private val doc = DefaultCanonicalJson.decode(ClientScenarios.files.getValue(name)).value as JsonObject

    /** The log, decoded, in seq order. */
    val log: List<Op> = (doc.members.getValue("log") as JsonArray).items.map {
        assertIs<OpDecodeResult.Known>(DefaultOpCodec.decodeTree(it, OpSource.PULLED), name).op
    }

    /** `rejected[]`: the raw op and its code. */
    val rejected: List<Pair<JsonObject, String>> = (doc.members["rejected"] as? JsonArray)?.items.orEmpty().map {
        val m = (it as JsonObject).members
        (m.getValue("op") as JsonObject) to (m.getValue("code") as JsonString).value
    }

    fun bySeq(seq: Long): Op = log.single { it.seq == seq }

    val maxWall: Long get() = log.maxOf { DefaultHlcRules.wall(it.hlc) }
}

/**
 * Plays a scenario through real clients: each device op of the log is minted by its device's
 * [kotobaya.sync.client.mint.OpMinter] (clock = `wall(hlc)`, op id scripted) and pushed by its
 * [SyncEngine] to a [TestServer] that emits the log's server ops after their cause (merges) and
 * rejects the scenario's `rejected` ops with their code.
 */
class ScenarioHarness(val scenario: Scenario) {
    val server = TestServer(scenario.maxWall)
    private val devices = LinkedHashMap<String, TestDevice>()
    private val rejectedCodes: Map<String, String> =
        scenario.rejected.associate { (op, code) -> (op.members.getValue("op_id") as JsonString).value to code }

    init {
        server.emitAfter = { op -> scenario.log.filter { it.causedByOpId == op.opId }.map { it.copy(seq = null, causedByOpId = null) } }
        server.verdict = { op -> rejectedCodes[op.opId]?.let { it to "scripted by ${scenario.name}" } }
    }

    fun device(id: String): TestDevice = devices.getOrPut(id) { TestDevice(id, server, scenario.maxWall, seed = devices.size + 1) }

    fun allDevices(): Collection<TestDevice> = devices.values

    /** The device of log op [seq] mints it (byte-identical to the log) without pushing. */
    fun mint(seq: Long): Op {
        val e = scenario.bySeq(seq)
        val dev = device(e.deviceId)
        dev.clock.now = DefaultHlcRules.wall(e.hlc)
        dev.ids.queue.addLast(e.opId)
        val minted = assertIs<MintResult.Minted>(dev.minter.mint(e.opType, e.payload), "${scenario.name} seq $seq").ops.single()
        assertEquals(e.copy(seq = null, causedByOpId = null), minted, "${scenario.name} seq $seq minted differently")
        return minted
    }

    /** Server op of the log that has no cause (job output), appended at its place. */
    fun emitServerOp(seq: Long) {
        val e = scenario.bySeq(seq)
        server.append(e.copy(seq = null, causedByOpId = null), null)
    }

    /** Every log op in seq order: device ops minted and pushed at once; uncaused server ops emitted. */
    fun playLogInOrder() {
        val end = server.nowMs
        for (e in scenario.log) {
            when {
                e.deviceId != Reserved.SERVER_DEVICE_ID -> {
                    server.nowMs = DefaultHlcRules.wall(e.hlc) // SYNC.md 13: server clock = wall(op.hlc)
                    mint(e.seq!!)
                    assertIs<CycleResult.Completed>(device(e.deviceId).push(), "${scenario.name} push of seq ${e.seq}")
                }
                e.causedByOpId == null -> emitServerOp(e.seq!!)
                else -> Unit // emitted by the server after its cause
            }
        }
        server.nowMs = end
    }

    /** Seeds each `rejected[].op` into the outbox of the device of its hlc, as a buggy or clock-wrong client would have. */
    fun seedRejected() {
        for ((op, _) in scenario.rejected) {
            val m = op.members
            val hlc = (m.getValue("hlc") as JsonString).value
            device(hlc.takeLast(36)).store.transaction { tx ->
                tx.outbox.insert(
                    PendingOpRow(
                        opId = (m.getValue("op_id") as JsonString).value,
                        opType = (m.getValue("op_type") as JsonString).value,
                        opVersion = (m.getValue("op_version") as JsonNumber).value.toInt(),
                        entityType = (m.getValue("entity_type") as JsonString).value,
                        entityId = (m.getValue("entity_id") as JsonString).value,
                        hlc = hlc,
                        payload = DefaultCanonicalJson.encode(m.getValue("payload")),
                        createdAt = DefaultHlcRules.wall(hlc),
                    ),
                )
            }
        }
    }

    /** The server's log equals the scenario's log: same ops, seqs and causes. */
    fun assertServerLogIsTheScenarioLog() {
        assertEquals(scenario.log, server.loggedOps(), "${scenario.name}: server log")
    }

    fun code(opId: String): String? = rejectedCodes[opId]
}
