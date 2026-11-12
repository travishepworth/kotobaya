package kotobaya.sync

import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.json
import java.util.UUID

/** A built `PullResponse` body ([text] is the exact JSON sent) plus what tests want to look at. */
class PullPage(val text: String, val opCount: Int, val nextSince: Long, val hasMore: Boolean, val highWaterSeq: Long, val logEpoch: String)

/**
 * `GET /sync/pull` minus HTTP (SYNC.md 6.1). Returns the `change` rows with `seq > since` in
 * ascending `seq`, at most [limit], from every origin; ends a page early (always at least one op)
 * before its body would exceed `max_push_bytes`. Reads without the op-apply lock: commits happen
 * in `seq` order under the lock, so no smaller seq can appear later. Each op is rendered as its
 * canonical JSON (`seq` and `caused_by_op_id` included), its payload exactly as logged.
 *
 * `since` above the high-water mark (the log was restored from an older backup) is not an error:
 * an empty page whose `log_epoch` / `high_water_seq` tell the client to reset (6.2).
 */
class PullService(private val db: Database, private val config: SyncConfig) {
    fun pull(since: Long, limit: Int, deviceId: UUID?): PullPage = db.txBlocking { dsl ->
        val budget = config.maxPushBytes - BODY_OVERHEAD
        val ops = ArrayList<String>()
        var used = 0
        var nextSince = since
        var hasMore = false
        dsl.resultQuery(
            "select seq, op_id::text, op_type, op_version, entity_type, entity_id, device_id::text, hlc::text, payload::text, " +
                "caused_by_op_id::text from app.change where seq > ? order by seq limit ?",
            since, limit + 1,
        ).fetchSize(64).fetchLazy().use { cursor ->
            for (r in cursor) {
                if (ops.size == limit) {
                    hasMore = true
                    break
                }
                val text = DefaultCanonicalJson.encode(
                    JsonObject(
                        linkedMapOf<String, JsonValue>(
                            "op_id" to JsonString(r.get(1, String::class.java)),
                            "op_type" to JsonString(r.get(2, String::class.java)),
                            "op_version" to JsonNumber((r.get(3) as Number).toDouble()),
                            "entity_type" to JsonString(r.get(4, String::class.java)),
                            "entity_id" to JsonString(r.get(5, String::class.java)),
                            "device_id" to JsonString(r.get(6, String::class.java)),
                            "hlc" to JsonString(r.get(7, String::class.java)),
                            "payload" to requireNotNull(DefaultCanonicalJson.decode(r.get(8, String::class.java)).value) { "stored payload is not JSON" },
                            "seq" to JsonNumber((r.get(0) as Number).toDouble()),
                            "caused_by_op_id" to (r.get(9, String::class.java)?.let(::JsonString) ?: JsonNull),
                        ),
                    ),
                )
                val size = DefaultCanonicalJson.utf8Size(text) + 1
                if (ops.isNotEmpty() && used + size > budget) {
                    hasMore = true
                    break
                }
                ops += text
                used += size
                nextSince = (r.get(0) as Number).toLong()
            }
        }
        val hw = (dsl.fetchValue("select coalesce(max(seq), 0) from app.change") as Number).toLong()
        val id = logIdentity(dsl, hw) ?: throw Problems.unavailable("The sync log epoch is not initialised yet.")
        deviceId?.let { dsl.execute("update app.device set last_pull_seq = ?, last_seen_at = now() where id = ?", since, it) }
        val tail = linkedMapOf<String, Any?>(
            "next_since" to nextSince,
            "has_more" to hasMore,
            "high_water_seq" to hw,
            "server_time" to config.clock().toEpochMilli(),
            "log_epoch" to id.logEpoch,
            "durable_seq" to id.durableSeq,
        )
        val tailJson = json.writeValueAsString(tail)
        val body = "{\"ops\":[" + ops.joinToString(",") + "]," + tailJson.substring(1)
        PullPage(body, ops.size, nextSince, hasMore, hw, id.logEpoch)
    }

    companion object {
        /** Bytes reserved for everything in a page body except the ops. */
        const val BODY_OVERHEAD = 1024
        const val DEFAULT_LIMIT = 500
        const val MAX_LIMIT = 1000
    }
}
