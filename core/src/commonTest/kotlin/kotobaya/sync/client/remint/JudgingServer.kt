package kotobaya.sync.client.remint

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.protocol.TestServer
import kotobaya.sync.client.store.UuidV7Source
import kotlin.random.Random

/**
 * A [TestServer] that judges like the real one for what Re-mint meets: `unknown_reference` from a
 * server-side replica (the device apply function over the log), the `review.logged` clock checks on
 * `reviewed_at` (SYNC.md 3.4; the hlc checks are the TestServer's own, after these, as in 5.3), and
 * the merges of SYNC.md 8.1 (note collision on `note.created`, cards of a merged note, card
 * collision), emitted with the server's clock after the triggering op.
 */
class JudgingServer(now: Long, seed: Int = 7) {
    val server = TestServer(now)
    val tables: InMemoryLearningTables = InMemoryLearningTables.seeded()
    private val applier = DeviceOpApplier<InMemoryLearningTables>()
    private val ids = UuidV7Source(Random(seed))

    init {
        server.onAccept = { applier.applyTo(tables, it) }
        server.verdict = { op -> judge(op) }
        server.emitAfter = { op -> merges(op) }
    }

    var now: Long
        get() = server.nowMs
        set(v) {
            server.nowMs = v
        }

    private fun judge(op: Op): Pair<String, String>? {
        DeviceOpApplier.missingReference(tables, op)?.let { return "unknown_reference" to "no $it" }
        if (op.opType == OpType.REVIEW_LOGGED) {
            val at = (op.payload.members["reviewed_at"] as JsonNumber).value.toLong()
            if (at > server.nowMs + Reserved.CLOCK_TOLERANCE_MS) return "clock_ahead" to "reviewed_at ahead"
            if (at < Reserved.CLOCK_FLOOR_MS) return "clock_behind" to "reviewed_at before 2020"
        }
        return null
    }

    private fun s(row: Row, k: String): String? = (row[k] as? JsonString)?.value

    private fun live(row: Row): Boolean = (row["deleted_at"] ?: JsonNull) == JsonNull && (row["merged_into_id"] ?: JsonNull) == JsonNull

    private fun merges(op: Op): List<Op> {
        val out = ArrayList<Op>()
        var state = server.hlc
        fun emit(type: OpType, payload: Map<String, JsonValue>) {
            val m = DefaultHlcRules.mint(state, server.nowMs, Reserved.SERVER_DEVICE_ID)
            state = m.state
            val p = JsonObject(payload)
            out += Op(ids.newOpId(server.nowMs), type, 1, type.entityType, DefaultOpCodec.entityIdOf(type, p), Reserved.SERVER_DEVICE_ID, m.hlc, p)
        }
        fun mergeCard(card: Row, survivorNote: String) {
            val kind = s(card, "kind")
            val existing = tables.where(LearningTable.CARD, "note_id", JsonString(survivorNote))
                .filter { (it["deleted_at"] ?: JsonNull) == JsonNull && (it["merged_into_id"] ?: JsonNull) == JsonNull && s(it, "kind") == kind }
                .minByOrNull { s(it, "id")!! }
            val survivorCard = existing?.let { s(it, "id")!! } ?: ids.newOpId(server.nowMs).also { id ->
                emit(OpType.CARD_CREATED, mapOf("id" to JsonString(id), "note_id" to JsonString(survivorNote), "kind" to JsonString(kind!!)))
            }
            emit(OpType.CARD_MERGED, mapOf("loser_id" to card["id"]!!, "survivor_id" to JsonString(survivorCard)))
        }
        when (op.opType) {
            OpType.NOTE_CREATED -> {
                val id = s(op.payload.members, "id")!!
                val seq = op.payload.members["entry_seq"] as? JsonNumber ?: return out
                val n = tables.get(LearningTable.NOTE, listOf(JsonString(id))) ?: return out
                if (!live(n)) return out
                val w = tables.where(LearningTable.NOTE, "entry_seq", seq).filter { s(it, "id") != id && live(it) }
                    .minByOrNull { (it["created_at"] as JsonNumber).value } ?: return out
                val survivor = s(w, "id")!!
                emit(OpType.NOTE_MERGED, mapOf("loser_id" to JsonString(id), "survivor_id" to JsonString(survivor)))
                // the emitted ops are applied in order by append; cards of N are merged after the note merge
                val cards = tables.where(LearningTable.CARD, "note_id", JsonString(id))
                    .filter { (it["deleted_at"] ?: JsonNull) == JsonNull && (it["merged_into_id"] ?: JsonNull) == JsonNull }
                    .sortedWith(compareBy({ s(it, "kind") }, { s(it, "id") }))
                for (c in cards) mergeCard(c, survivor)
            }
            OpType.CARD_CREATED -> {
                val id = s(op.payload.members, "id")!!
                val card = tables.get(LearningTable.CARD, listOf(JsonString(id))) ?: return out
                val note = tables.get(LearningTable.NOTE, listOf(card["note_id"]!!)) ?: return out
                val into = s(note, "merged_into_id")
                if (into != null) {
                    mergeCard(card, into)
                } else {
                    val other = tables.where(LearningTable.CARD, "note_id", card["note_id"]!!)
                        .firstOrNull { s(it, "id") != id && s(it, "kind") == s(card, "kind") && (it["deleted_at"] ?: JsonNull) == JsonNull && (it["merged_into_id"] ?: JsonNull) == JsonNull }
                    if (other != null) emit(OpType.CARD_MERGED, mapOf("loser_id" to JsonString(id), "survivor_id" to other["id"]!!))
                }
            }
            else -> Unit
        }
        return out
    }
}
