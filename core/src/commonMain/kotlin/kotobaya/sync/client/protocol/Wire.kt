package kotobaya.sync.client.protocol

import kotobaya.contracts.Uuid
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.SyncSchemas
import kotobaya.sync.client.store.PendingOpRow

/** The log identity every push and pull response carries (SYNC.md 6.2). */
internal data class LogInfo(
    val highWaterSeq: Long,
    val serverTime: Long,
    val logEpoch: Uuid,
    val durableSeq: Long,
)

/** One `PushResult`. */
internal data class WireResult(
    val opId: Uuid,
    val status: String,
    val seq: Long?,
    val code: String?,
    val message: String?,
)

internal data class WirePushResponse(val results: List<WireResult>, val log: LogInfo)

internal data class WirePullPage(
    val ops: List<JsonValue>,
    val nextSince: Long,
    val hasMore: Boolean,
    val log: LogInfo,
)

/** A response the client cannot use (not JSON, wrong shape): a protocol failure, retried later. */
internal class ProtocolException(message: String) : Exception(message)

/** Push and pull bodies: building and parsing (SYNC.md 5.1, 5.4, 6.1; `batch.schema.json`). */
internal object Wire {
    private const val BATCH = "batch.schema.json#/\$defs/"
    private val pushKeys = setOf("results", "high_water_seq", "server_time", "log_epoch", "durable_seq")

    /** `{"ops":[` + `]}`. */
    const val BODY_OVERHEAD: Long = 10

    /**
     * The push element of [row]: exactly `CanonicalJson.encode` of the envelope (= `OpCodec.encode`
     * of the op, SYNC.md 5.2); `device_id` is the device part of the hlc.
     */
    fun element(row: PendingOpRow): String {
        val payload = DefaultCanonicalJson.decode(row.payload).value
        check(payload is JsonObject) { "pending_op ${row.opId} payload is not a JSON object" }
        val m = LinkedHashMap<String, JsonValue>()
        m["op_id"] = JsonString(row.opId)
        m["op_type"] = JsonString(row.opType)
        m["op_version"] = JsonNumber(row.opVersion.toDouble())
        m["entity_type"] = JsonString(row.entityType)
        m["entity_id"] = JsonString(row.entityId)
        m["device_id"] = JsonString(row.hlc.takeLast(36))
        m["hlc"] = JsonString(row.hlc)
        m["payload"] = payload
        return DefaultCanonicalJson.encode(JsonObject(m))
    }

    fun body(elements: List<String>): String = elements.joinToString(",", prefix = "{\"ops\":[", postfix = "]}")

    fun parsePush(text: String): WirePushResponse {
        val tree = decodeObject(text)
        // unknown top-level members are ignored (a newer server may add some)
        val known = JsonObject(tree.members.filterKeys { it in pushKeys })
        SyncSchemas.validate("${BATCH}PushResponse", known)?.let {
            throw ProtocolException("PushResponse: ${it.message} at '${it.pointer}'")
        }
        val results = (known.members.getValue("results") as JsonArray).items.map { r ->
            val m = (r as JsonObject).members
            val error = m["error"] as? JsonObject
            WireResult(
                opId = str(m.getValue("op_id")),
                status = str(m.getValue("status")),
                seq = m["seq"]?.let(::long),
                code = error?.let { str(it.members.getValue("code")) },
                message = error?.let { str(it.members.getValue("message")) },
            )
        }
        return WirePushResponse(results, logInfo(known))
    }

    fun parsePull(text: String): WirePullPage {
        val tree = decodeObject(text)
        val ops = tree.members["ops"] as? JsonArray ?: throw ProtocolException("PullResponse: ops is not an array")
        // ops are checked one by one (OpCodec.decodeTree, lenient shape included) so that the ops
        // before an unsupported or invalid one are still applied (SYNC.md 6.1)
        val shell = JsonObject(tree.members + ("ops" to JsonArray(emptyList())))
        SyncSchemas.validate("${BATCH}PullResponseLenient", shell)?.let {
            throw ProtocolException("PullResponse: ${it.message} at '${it.pointer}'")
        }
        val m = tree.members
        return WirePullPage(ops.items, long(m.getValue("next_since")), (m.getValue("has_more") as JsonBool).value, logInfo(tree))
    }

    /** The `seq` of a raw pulled op if it is an integer, else null. */
    fun seqOf(raw: JsonValue): Long? {
        val v = (raw as? JsonObject)?.members?.get("seq") as? JsonNumber ?: return null
        val d = v.value
        return if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 9.007199254740992E15) d.toLong() else null
    }

    fun stringMember(raw: JsonValue, name: String): String? = ((raw as? JsonObject)?.members?.get(name) as? JsonString)?.value

    private fun logInfo(o: JsonObject): LogInfo {
        val m = o.members
        return LogInfo(long(m.getValue("high_water_seq")), long(m.getValue("server_time")), str(m.getValue("log_epoch")), long(m.getValue("durable_seq")))
    }

    private fun decodeObject(text: String): JsonObject {
        val r = DefaultCanonicalJson.decode(text)
        val v = r.value ?: throw ProtocolException("not JSON: ${r.error?.message} at ${r.error?.offset}")
        return v as? JsonObject ?: throw ProtocolException("the body is not a JSON object")
    }

    private fun str(v: JsonValue): String = (v as JsonString).value

    private fun long(v: JsonValue): Long = (v as JsonNumber).value.toLong()
}
