package kotobaya.acceptance.offline

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.Emitter
import kotobaya.contracts.sync.HlcState
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.PushBody
import kotobaya.sync.PushBodyParser
import kotobaya.sync.PushElement
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.protocol.SyncLimits
import kotobaya.sync.client.protocol.TransportResponse
import kotobaya.sync.client.store.UuidV7Source
import kotlin.random.Random

/**
 * In-memory reference server (used when Docker is unavailable, or with `-Pserver=memory`): SYNC.md 5.2/5.3
 * for what a simulation meets (duplicate, decode, envelope, `server_only`, `device_mismatch`,
 * `unknown_reference` from a server-side replica, the clock codes incl. `reviewed_at`), the merges of 8.1
 * (note collision by `entry_seq` / `kanji_literal`, cards of a merged note, card collision), paging (6.1),
 * and backups / restores with epochs and the 6.2 step 3 clock raise. The replica is the device apply
 * function over the log, so this server cannot disagree with a correct device about apply semantics;
 * it is the weaker oracle, and the report says when it was used.
 */
class MemSimServer(override var nowMs: Long, private val pageSize: Int = 500, seed: Int = 1) : SimServer {
    override val kind: String = "memory"

    private class State(
        val log: MutableList<Op>,
        val seqByOpId: MutableMap<String, Long>,
        var hlc: HlcState,
        val tables: InMemoryLearningTables,
    ) {
        fun copy() = State(ArrayList(log), HashMap(seqByOpId), hlc, tables.copy())
    }

    private var s = State(ArrayList(), HashMap(), HlcState.INITIAL, InMemoryLearningTables.seeded())
    private val applier = DeviceOpApplier<InMemoryLearningTables>()
    private val ids = UuidV7Source(Random(seed))
    private var epochN = 1
    override var logEpoch: String = epoch(1)
        private set
    private var durableSeq = 0L
    private var backupState: State? = null

    private fun epoch(n: Int) = "0193a1b2-00ee-7000-8000-%012x".format(n)

    private val highWaterSeq: Long get() = s.log.lastOrNull()?.seq ?: 0L

    override fun limits(): SyncLimits = SyncLimits.DEFAULT

    override fun push(deviceId: String, body: String): TransportResponse {
        if (DefaultCanonicalJson.utf8Size(body) > SyncLimits.DEFAULT_MAX_PUSH_BYTES) return TransportResponse.HttpError(413, "payload_too_large")
        val parsed = PushBodyParser.parse(body)
        if (parsed is PushBody.BadRequest) return TransportResponse.HttpError(400, parsed.message)
        val results = (parsed as PushBody.Ops).elements.map { result(deviceId, it) }
        return TransportResponse.Ok(DefaultCanonicalJson.encode(JsonObject(mapOf("results" to JsonArray(results)) + logInfo())))
    }

    private fun result(deviceId: String, el: PushElement): JsonValue {
        s.seqByOpId[el.opId]?.let { return ok(el.opId, "duplicate", it) }
        val op = when (el) {
            is PushElement.DuplicateKey, is PushElement.TooDeep -> return rejected(el.opId, "schema_invalid", "duplicate key or too deep")
            is PushElement.Parsed -> when (val d = DefaultOpCodec.decodeTree(el.tree, OpSource.PUSHED)) {
                is OpDecodeResult.Unsupported -> return rejected(el.opId, "unsupported_op", d.opType)
                is OpDecodeResult.Invalid -> return rejected(el.opId, "schema_invalid", d.message)
                is OpDecodeResult.Known -> d.op
            }
        }
        if (DefaultOpCodec.envelopeErrors(op, OpSource.PUSHED).isNotEmpty()) return rejected(op.opId, "schema_invalid", "envelope")
        if (op.deviceId != deviceId) return rejected(op.opId, "device_mismatch", op.deviceId)
        if (op.opType.emitter == Emitter.SERVER || op.deviceId == Reserved.SERVER_DEVICE_ID) return rejected(op.opId, "server_only", op.opType.wire)
        DeviceOpApplier.missingReference(s.tables, op)?.let { return rejected(op.opId, "unknown_reference", "no $it") }
        val wall = DefaultHlcRules.wall(op.hlc)
        val reviewedAt = if (op.opType == OpType.REVIEW_LOGGED) (op.payload.members["reviewed_at"] as JsonNumber).value.toLong() else null
        if (wall > nowMs + Reserved.CLOCK_TOLERANCE_MS + Reserved.CLOCK_EPSILON_MS) return rejected(op.opId, "clock_ahead", "hlc ahead")
        if (reviewedAt != null && reviewedAt > nowMs + Reserved.CLOCK_TOLERANCE_MS) return rejected(op.opId, "clock_ahead", "reviewed_at ahead")
        if (wall < Reserved.CLOCK_FLOOR_MS || (reviewedAt != null && reviewedAt < Reserved.CLOCK_FLOOR_MS)) return rejected(op.opId, "clock_behind", "before 2020")
        val seq = append(op, null)
        mergesAfter(op)
        return ok(op.opId, "accepted", seq)
    }

    private fun append(op: Op, cause: String?): Long {
        val seq = highWaterSeq + 1
        s.hlc = DefaultHlcRules.observe(s.hlc, op.hlc)
        val logged = op.copy(seq = seq, causedByOpId = cause)
        s.log += logged
        s.seqByOpId[op.opId] = seq
        if (op.opType.deviceApplies) applier.applyTo(s.tables, logged)
        return seq
    }

    private fun mint(type: OpType, payload: JsonObject, cause: String?): Op {
        val m = DefaultHlcRules.mint(s.hlc, nowMs, Reserved.SERVER_DEVICE_ID)
        val op = Op(ids.newOpId(nowMs), type, 1, type.entityType, DefaultOpCodec.entityIdOf(type, payload), Reserved.SERVER_DEVICE_ID, m.hlc, payload)
        val seq = append(op, cause)
        return op.copy(seq = seq, causedByOpId = cause)
    }

    override fun emit(type: OpType, payload: JsonObject): Op {
        DeviceOpApplier.missingReference(s.tables, Op("x", type, 1, type.entityType, DefaultOpCodec.entityIdOf(type, payload), Reserved.SERVER_DEVICE_ID, "", payload))
            ?.let { error("server refused its own ${type.wire}: no $it") }
        val op = mint(type, payload, null)
        mergesAfter(op)
        return op
    }

    private fun live(row: Row): Boolean = row.isNull("deleted_at") && row.isNull("merged_into_id")

    /** SYNC.md 8.1 after an applied op. */
    private fun mergesAfter(op: Op) {
        val t = s.tables
        fun mergeCard(card: Row, survivorNote: String) {
            val kind = card.str("kind")
            val existing = t.where(LearningTable.CARD, "note_id", JsonString(survivorNote))
                .filter { live(it) && it.str("kind") == kind }.minByOrNull { it.str("id")!! }
            val survivorCard = existing?.str("id") ?: ids.newOpId(nowMs).also { id ->
                mint(OpType.CARD_CREATED, obj("id" to id, "note_id" to survivorNote, "kind" to kind), op.opId)
            }
            mint(OpType.CARD_MERGED, obj("loser_id" to card.str("id"), "survivor_id" to survivorCard), op.opId)
        }
        when (op.opType) {
            OpType.NOTE_CREATED -> {
                val id = op.p("id")!!
                val n = t.get(LearningTable.NOTE, listOf(JsonString(id))) ?: return
                if (!live(n)) return
                val keyCol = listOf("entry_seq", "kanji_literal", "grammar_point_id").firstOrNull { !n.isNull(it) } ?: return
                val w = t.where(LearningTable.NOTE, keyCol, n.getValue(keyCol)).filter { it.str("id") != id && live(it) }
                    .minWithOrNull(compareBy({ it.num("created_at") }, { it.str("id") })) ?: return
                val survivor = w.str("id")!!
                mint(OpType.NOTE_MERGED, obj("loser_id" to id, "survivor_id" to survivor), op.opId)
                val cards = t.where(LearningTable.CARD, "note_id", JsonString(id)).filter(::live).sortedWith(compareBy({ it.str("kind") }, { it.str("id") }))
                for (c in cards) mergeCard(c, survivor)
            }
            OpType.CARD_CREATED -> {
                val id = op.p("id")!!
                val card = t.get(LearningTable.CARD, listOf(JsonString(id))) ?: return
                if (!card.isNull("merged_into_id") || !card.isNull("deleted_at")) return
                val note = t.get(LearningTable.NOTE, listOf(card.getValue("note_id"))) ?: return
                val into = note.str("merged_into_id")
                if (into != null) {
                    mergeCard(card, into)
                } else {
                    val other = t.where(LearningTable.CARD, "note_id", card.getValue("note_id"))
                        .filter { it.str("id") != id && it.str("kind") == card.str("kind") && live(it) }.minByOrNull { it.str("id")!! }
                    if (other != null) mint(OpType.CARD_MERGED, obj("loser_id" to id, "survivor_id" to other.str("id")), op.opId)
                }
            }
            else -> Unit
        }
    }

    override fun pull(since: Long): TransportResponse {
        val out = s.log.filter { it.seq!! > since }.take(pageSize)
        val next = out.lastOrNull()?.seq ?: since
        val page = mapOf(
            "ops" to JsonArray(out.map { DefaultOpCodec.encodeTree(it) }),
            "next_since" to num(next),
            "has_more" to JsonBool(highWaterSeq > next),
        ) + logInfo()
        return TransportResponse.Ok(DefaultCanonicalJson.encode(JsonObject(page)))
    }

    override fun backup() {
        backupState = s.copy()
        durableSeq = maxOf(durableSeq, highWaterSeq)
    }

    override fun restoreLatestBackup() {
        val b = backupState ?: error("no backup to restore")
        val raisedFloor = HlcState(nowMs + Reserved.DAY_MS, 0)
        s = b.copy()
        s.hlc = if (raisedFloor > s.hlc) raisedFloor else s.hlc
        logEpoch = epoch(++epochN)
        durableSeq = highWaterSeq
    }

    private operator fun HlcState.compareTo(o: HlcState): Int = compareValuesBy(this, o, { it.ms }, { it.counter })

    override fun log(): List<Op> = s.log.toList()

    override fun projection(reference: Projection): Projection = projection(s.tables)

    private fun logInfo(): Map<String, JsonValue> = mapOf(
        "high_water_seq" to num(highWaterSeq),
        "server_time" to num(nowMs),
        "log_epoch" to JsonString(logEpoch),
        "durable_seq" to num(minOf(durableSeq, highWaterSeq)),
    )

    private fun ok(opId: String, status: String, seq: Long): JsonValue =
        JsonObject(mapOf("op_id" to JsonString(opId), "status" to JsonString(status), "seq" to num(seq)))

    private fun rejected(opId: String, code: String, message: String): JsonValue = JsonObject(
        mapOf(
            "op_id" to JsonString(opId),
            "status" to JsonString("rejected"),
            "error" to JsonObject(mapOf("code" to JsonString(code), "message" to JsonString(message))),
        ),
    )

    private fun num(v: Long) = JsonNumber(v.toDouble())

    override fun close() = Unit
}
