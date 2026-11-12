package kotobaya.sync.client.store

import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpCodec
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultOpCodec

/**
 * The op envelope of this row as a tree, in pushed form (no `seq`): the eight properties of SYNC.md 2,
 * `device_id` being the device part of the hlc. `CanonicalJson.encode` of it is the push element,
 * identical to `OpCodec.encode` of the minted op.
 *
 * @throws IllegalStateException if the stored payload is not a JSON object.
 */
public fun PendingOpRow.toTree(): JsonObject {
    val payload = DefaultCanonicalJson.decode(payload).value
    check(payload is JsonObject) { "pending_op $opId payload is not a JSON object" }
    val m = LinkedHashMap<String, JsonValue>()
    m["op_id"] = JsonString(opId)
    m["op_type"] = JsonString(opType)
    m["op_version"] = JsonNumber(opVersion.toDouble())
    m["entity_type"] = JsonString(entityType)
    m["entity_id"] = JsonString(entityId)
    m["device_id"] = JsonString(hlc.takeLast(36))
    m["hlc"] = JsonString(hlc)
    m["payload"] = payload
    return JsonObject(m)
}

/**
 * The row as a decoded [Op] (e.g. to re-apply kept rows in a rebuild, SYNC.md 10.3 step 3).
 *
 * @throws IllegalStateException if the row does not decode as a known, valid op with [codec] (a row
 *   minted by [kotobaya.sync.client.mint.OpMinter] always does).
 */
public fun PendingOpRow.toOp(codec: OpCodec = DefaultOpCodec): Op =
    when (val d = codec.decodeTree(toTree(), OpSource.PUSHED)) {
        is OpDecodeResult.Known -> d.op
        is OpDecodeResult.Invalid -> throw IllegalStateException("pending_op $opId is invalid: ${d.message} at '${d.pointer}'")
        is OpDecodeResult.Unsupported -> throw IllegalStateException("pending_op $opId has unsupported ${d.opType} v${d.opVersion}")
    }
