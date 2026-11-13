package kotobaya.sync.client.remint

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.store.UuidV7Source
import kotlin.random.Random
import kotlin.test.assertIs

const val DAY: Long = 86_400_000L
const val HOUR: Long = 3_600_000L
const val MINUTE: Long = 60_000L

/** Row ids for story data (UUIDv7 from a seeded random). */
class RowIds(seed: Int) {
    private val src = UuidV7Source(Random(seed))

    fun next(at: Long): String = src.newOpId(at)
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.filter { it.second != null }.associate { (k, v) ->
        k to when (v) {
            is JsonValue -> v
            is String -> JsonString(v)
            is Long -> JsonNumber(v.toDouble())
            is Int -> JsonNumber(v.toDouble())
            is Double -> JsonNumber(v)
            else -> error("unsupported $v")
        }
    },
)

/** Mints through the device's [kotobaya.sync.client.mint.OpMinter] (section 4, guards included). */
fun RemintDevice.mint(type: OpType, payload: JsonObject): Op = assertIs<MintResult.Minted>(d.minter.mint(type, payload), "${type.wire} $payload").ops.single()

/**
 * Mints like a client **without** the guards G1/G2 (SYNC.md 12.6: the HLC still counts on `last`,
 * payload times come from the wrong clock), applying the local effect and queuing the op.
 */
fun RemintDevice.mintUnguarded(type: OpType, payload: JsonObject): Op {
    val pt = d.clock.now
    val state = DefaultHlcRules.stateOf(d.state("hlc_last"))
    val hlc = DefaultHlcRules.mint(state, pt, d.deviceId).hlc
    val op = Op(d.ids.newOpId(pt), type, 1, type.entityType, DefaultOpCodec.entityIdOf(type, payload), d.deviceId, hlc, payload)
    d.inject(op)
    return op
}

fun vocabNote(id: String, entry: Long, vararg fields: Pair<String, String>): JsonObject = obj(
    "id" to id,
    "note_type_id" to Reserved.NOTE_TYPE_VOCAB,
    "entry_seq" to entry,
    "fields" to (if (fields.isEmpty()) null else JsonObject(fields.associate { it.first to JsonString(it.second) })),
)

fun review(id: String, card: String, at: Long, rating: Int = 3): JsonObject = obj(
    "id" to id,
    "card_id" to card,
    "reviewed_at" to at,
    "utc_offset_min" to 540,
    "review_type" to "scheduled",
    "rating" to rating,
    "button" to (if (rating == 1) "again" else "good"),
)
