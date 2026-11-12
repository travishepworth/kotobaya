package kotobaya.sync

import kotobaya.contracts.sync.EnvelopeError
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpCodec
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType

/**
 * [OpCodec] of the shared core (contracts/sync/OpCodec.kt; SYNC.md 2, 5.3, 6.1, 15).
 *
 * Lenient envelope shape: for [OpSource.PULLED] exactly
 * `batch.schema.json#/$defs/PullResponseLenient/properties/ops/items` (so `seq` and
 * `caused_by_op_id` are required); for [OpSource.PUSHED] the same item schema without those two in
 * `required`. A failure there is [OpDecodeResult.Invalid] even for an unknown `op_type`. A pushed op
 * of an unknown type/version that carries `seq` is therefore [OpDecodeResult.Unsupported] (SYNC.md
 * 5.3 order: `unsupported_op` before `schema_invalid`); a pushed op of a known type that carries
 * `seq` or `caused_by_op_id` is [OpDecodeResult.Invalid] at `/seq` / `/caused_by_op_id`
 * (`batch.schema.json#/$defs/PushRequest`).
 *
 * [OpDecodeResult.Unsupported.opVersion] is the received `op_version` capped at `Int.MAX_VALUE`
 * (the lenient shape allows any integer up to 2^53 - 1).
 *
 * A tree nested deeper than [DefaultCanonicalJson.MAX_DEPTH] is [OpDecodeResult.Invalid] at `""`,
 * and [encode] / [sizeBytes] of such an op throw IllegalArgumentException: no replica mints, accepts
 * or applies an op it could not parse back.
 */
public object DefaultOpCodec : OpCodec {
    private const val TWO_POW_53 = 9007199254740992.0
    private const val BATCH = "batch.schema.json"
    private const val PULLED_ITEM = "$BATCH#/\$defs/PullResponseLenient/properties/ops/items"

    private val byWire: Map<String, OpType> = OpType.entries.associateBy { it.wire }

    private val pushedLenient: JsonObject by lazy {
        val pulled = SyncSchemas.resolveRef(PULLED_ITEM) as JsonObject
        val m = LinkedHashMap(pulled.members)
        m["required"] = JsonArray(
            (m.getValue("required") as JsonArray).items.filter {
                it != JsonString("seq") && it != JsonString("caused_by_op_id")
            },
        )
        JsonObject(m)
    }

    override fun encode(op: Op): String = DefaultCanonicalJson.encode(encodeTree(op))

    override fun encodeTree(op: Op): JsonObject {
        val m = LinkedHashMap<String, JsonValue>()
        m["op_id"] = JsonString(op.opId)
        m["op_type"] = JsonString(op.opType.wire)
        m["op_version"] = JsonNumber(op.opVersion.toDouble())
        m["entity_type"] = JsonString(op.entityType)
        m["entity_id"] = JsonString(op.entityId)
        m["device_id"] = JsonString(op.deviceId)
        m["hlc"] = JsonString(op.hlc)
        m["payload"] = op.payload
        val seq = op.seq
        if (seq != null) {
            m["seq"] = JsonNumber(seq.toDouble())
            m["caused_by_op_id"] = op.causedByOpId?.let { JsonString(it) } ?: JsonNull
        }
        return JsonObject(m)
    }

    override fun decode(json: String, source: OpSource): OpDecodeResult {
        val r = DefaultCanonicalJson.decode(json)
        val v = r.value ?: return OpDecodeResult.Invalid("not JSON: ${r.error?.message} at ${r.error?.offset}", "", null)
        return decodeTree(v, source)
    }

    override fun decodeTree(json: JsonValue, source: OpSource): OpDecodeResult {
        if (json !is JsonObject) return OpDecodeResult.Invalid("an op must be a JSON object", "", null)
        if (DefaultCanonicalJson.exceedsMaxDepth(json)) {
            return OpDecodeResult.Invalid("nesting deeper than ${DefaultCanonicalJson.MAX_DEPTH}", "", null)
        }
        val m = json.members
        val seq = m["seq"]?.let { asLong(it) }?.takeIf { it >= 1 }
        val lenient = when (source) {
            OpSource.PULLED -> SyncSchemas.validate(PULLED_ITEM, json)
            OpSource.PUSHED -> SyncSchemas.validateWith(pushedLenient, BATCH, json)
        }
        if (lenient != null) return OpDecodeResult.Invalid(lenient.message, lenient.pointer, seq)
        val wire = (m.getValue("op_type") as JsonString).value
        val version = (m.getValue("op_version") as JsonNumber).value
        val type = byWire[wire]
        if (type == null || version > OpType.MAX_SUPPORTED_VERSION) {
            // lenient shape held, so version is an integer in 1 until 2^53; clamp for the Int field
            val v = if (version > Int.MAX_VALUE) Int.MAX_VALUE else version.toInt()
            return OpDecodeResult.Unsupported(wire, v, seq, json)
        }
        if (source == OpSource.PUSHED) {
            for (name in listOf("seq", "caused_by_op_id")) {
                if (name in m) return OpDecodeResult.Invalid("a pushed op carries no $name", "/$name", seq)
            }
        }
        SyncSchemas.validate("op.schema.json", json)?.let { return OpDecodeResult.Invalid(it.message, it.pointer, seq) }
        val op = Op(
            opId = (m.getValue("op_id") as JsonString).value,
            opType = type,
            opVersion = version.toInt(),
            entityType = (m.getValue("entity_type") as JsonString).value,
            entityId = (m.getValue("entity_id") as JsonString).value,
            deviceId = (m.getValue("device_id") as JsonString).value,
            hlc = (m.getValue("hlc") as JsonString).value,
            payload = m.getValue("payload") as JsonObject,
            seq = seq,
            causedByOpId = if (source == OpSource.PULLED) (m["caused_by_op_id"] as? JsonString)?.value else null,
        )
        return OpDecodeResult.Known(op)
    }

    override fun entityIdOf(type: OpType, payload: JsonObject): String = type.entityIdParts.joinToString("|") { part ->
        if (part.length >= 2 && part.startsWith("'") && part.endsWith("'")) {
            part.substring(1, part.length - 1)
        } else {
            when (val v = payload.members[part]) {
                is JsonString -> v.value
                is JsonNumber -> requireNotNull(asLong(v)) { "'$part' is not an integer" }.toString()
                null -> throw IllegalArgumentException("payload lacks '$part'")
                else -> throw IllegalArgumentException("'$part' is not a string or integer")
            }
        }
    }

    override fun envelopeErrors(op: Op, source: OpSource): List<EnvelopeError> {
        val out = ArrayList<EnvelopeError>()
        if (op.entityType != op.opType.entityType) out.add(EnvelopeError.ENTITY_TYPE_MISMATCH)
        val derived = try {
            entityIdOf(op.opType, op.payload)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (derived != op.entityId) out.add(EnvelopeError.ENTITY_ID_MISMATCH)
        if (op.hlc.length < 36 || op.hlc.substring(op.hlc.length - 36) != op.deviceId) out.add(EnvelopeError.DEVICE_ID_MISMATCH)
        val seqOk = when (source) {
            OpSource.PUSHED -> op.seq == null && op.causedByOpId == null
            OpSource.PULLED -> op.seq != null
        }
        if (!seqOk) out.add(EnvelopeError.SEQ_PRESENCE)
        return out
    }

    override fun sizeBytes(op: Op): Int = DefaultCanonicalJson.utf8Size(encode(op))

    override fun rawSizeBytes(raw: JsonValue): Int = DefaultCanonicalJson.sizeOf(raw)

    /** The value of an integer-typed JSON number (integral, magnitude < 2^53), else null. */
    private fun asLong(v: JsonValue): Long? {
        val d = (v as? JsonNumber)?.value ?: return null
        if (d != kotlin.math.floor(d) || kotlin.math.abs(d) >= TWO_POW_53) return null
        return d.toLong()
    }
}
