package kotobaya.sync.client.protocol

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
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.PushBody
import kotobaya.sync.PushBodyParser
import kotobaya.sync.PushElement

/**
 * In-memory stand-in for the server's sync endpoints (SYNC.md 5.2/5.3 order for the parts a test
 * server can judge, 6.1 paging, 6.2 epochs). Uses the real core [PushBodyParser] and [DefaultOpCodec].
 * References and conflict rules are not checked: [verdict] scripts rejections, [emitAfter] scripts
 * server-emitted ops (merges), [onAccept] lets a test keep a server-side replica.
 */
class TestServer(
    var nowMs: Long,
    var maxOpBytes: Long = SyncLimits.DEFAULT_MAX_OP_BYTES,
    var maxPushBytes: Long = SyncLimits.DEFAULT_MAX_PUSH_BYTES,
) {
    var logEpoch: String = "0193a1b2-00ee-7000-8000-000000000001"

    /** The log, pulled form, in seq order. Raw trees so tests can plant unknown or invalid ops. */
    val log: MutableList<JsonObject> = ArrayList()
    private val seqByOpId = HashMap<String, Long>()
    var nextSeq: Long = 1

    /** null = `durable_seq` follows `high_water_seq` (backups disabled); else the reported value. */
    var durableSeq: Long? = null
    var pageSize: Int = 500

    /** If set, a page ends before its body would exceed it (SYNC.md 6.1 SHOULD); null = no byte bound. */
    var pageByteBound: Long? = null
    var hlc: HlcState = HlcState.INITIAL

    var verdict: (Op) -> Pair<String, String>? = { null }
    var emitAfter: (Op) -> List<Op> = { emptyList() }
    var onAccept: (Op) -> Unit = {}

    /** Bodies the server received (accepted at the HTTP level). */
    val pushBodies: MutableList<String> = ArrayList()

    val highWaterSeq: Long get() = nextSeq - 1
    private val reportedDurable: Long get() = minOf(durableSeq ?: highWaterSeq, highWaterSeq)

    fun limits(): SyncLimits = SyncLimits(maxOpBytes, maxPushBytes)

    fun push(body: String): TransportResponse {
        if (DefaultCanonicalJson.utf8Size(body) > maxPushBytes) return TransportResponse.HttpError(413, "payload_too_large")
        val parsed = PushBodyParser.parse(body)
        if (parsed is PushBody.BadRequest) return TransportResponse.HttpError(400, parsed.message)
        pushBodies.add(body)
        val results = (parsed as PushBody.Ops).elements.map { result(it) }
        return TransportResponse.Ok(
            DefaultCanonicalJson.encode(JsonObject(mapOf("results" to JsonArray(results)) + logInfo())),
        )
    }

    private fun result(el: PushElement): JsonValue {
        seqByOpId[el.opId]?.let { return ok(el.opId, "duplicate", it) }
        val op = when (el) {
            is PushElement.DuplicateKey, is PushElement.TooDeep -> return rejected(el.opId, "schema_invalid", "duplicate key or too deep")
            is PushElement.Parsed -> {
                if (DefaultOpCodec.rawSizeBytes(el.tree) > maxOpBytes) return rejected(el.opId, "op_too_large", "over max_op_bytes")
                when (val d = DefaultOpCodec.decodeTree(el.tree, OpSource.PUSHED)) {
                    is OpDecodeResult.Unsupported -> return rejected(el.opId, "unsupported_op", d.opType)
                    is OpDecodeResult.Invalid -> return rejected(el.opId, "schema_invalid", d.message)
                    is OpDecodeResult.Known -> d.op
                }
            }
        }
        if (DefaultOpCodec.envelopeErrors(op, OpSource.PUSHED).isNotEmpty()) return rejected(op.opId, "schema_invalid", "envelope")
        verdict(op)?.let { (code, message) -> return rejected(op.opId, code, message) }
        if (op.opType.emitter == Emitter.SERVER || op.deviceId == Reserved.SERVER_DEVICE_ID) {
            return rejected(op.opId, "server_only", op.opType.wire)
        }
        val wall = DefaultHlcRules.wall(op.hlc)
        if (wall > nowMs + 86_400_000L + 60_000L) return rejected(op.opId, "clock_ahead", "hlc ahead")
        if (wall < 1_577_836_800_000L) return rejected(op.opId, "clock_behind", "hlc behind")
        val seq = append(op, null)
        for (emitted in emitAfter(op)) append(emitted, op.opId)
        return ok(op.opId, "accepted", seq)
    }

    /** Appends an op to the log (server-minted when [cause] is null and the device is the server's). */
    fun append(op: Op, cause: String?): Long {
        val seq = nextSeq++
        hlc = DefaultHlcRules.observe(hlc, op.hlc)
        val logged = op.copy(seq = seq, causedByOpId = cause)
        log.add(DefaultOpCodec.encodeTree(logged))
        seqByOpId[op.opId] = seq
        onAccept(logged)
        return seq
    }

    /** Plants a raw log entry (unknown or invalid op) with the next seq. */
    fun plantRaw(tree: JsonObject): Long {
        val seq = nextSeq++
        log.add(JsonObject(tree.members + ("seq" to JsonNumber(seq.toDouble()))))
        return seq
    }

    fun pull(since: Long): TransportResponse {
        val out = ArrayList<JsonValue>()
        var bytes = 200L
        for (e in log) {
            val s = (e.members.getValue("seq") as JsonNumber).value.toLong()
            if (s <= since) continue
            if (out.size >= pageSize) break
            val size = DefaultCanonicalJson.utf8Size(DefaultCanonicalJson.encode(e)).toLong() + 1
            val bound = pageByteBound
            if (bound != null && out.isNotEmpty() && bytes + size > bound) break
            out.add(e)
            bytes += size
        }
        val next = if (out.isEmpty()) since else ((out.last() as JsonObject).members.getValue("seq") as JsonNumber).value.toLong()
        val hasMore = log.any { (it.members.getValue("seq") as JsonNumber).value.toLong() > next }
        val page = mapOf(
            "ops" to JsonArray(out),
            "next_since" to num(next),
            "has_more" to JsonBool(hasMore),
        ) + logInfo()
        return TransportResponse.Ok(DefaultCanonicalJson.encode(JsonObject(page)))
    }

    /** SYNC.md 6.2 restore: the log keeps seq <= [toSeq], a new epoch, durable = that seq. */
    fun restore(toSeq: Long, newEpoch: String) {
        log.removeAll { (it.members.getValue("seq") as JsonNumber).value.toLong() > toSeq }
        seqByOpId.entries.removeAll { it.value > toSeq }
        nextSeq = toSeq + 1
        logEpoch = newEpoch
        durableSeq = toSeq
    }

    fun loggedOps(): List<Op> = log.map { (DefaultOpCodec.decodeTree(it, OpSource.PULLED) as OpDecodeResult.Known).op }

    private fun logInfo(): Map<String, JsonValue> = mapOf(
        "high_water_seq" to num(highWaterSeq),
        "server_time" to num(nowMs),
        "log_epoch" to JsonString(logEpoch),
        "durable_seq" to num(reportedDurable),
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
}

/**
 * [SyncTransport] onto a [TestServer] with fault injection: offline, a push lost before or after
 * the server applied it, a replayed pull page, a substituted push body.
 */
class TestTransport(val server: TestServer) : SyncTransport {
    var offline: Boolean = false

    /** The next push fails before reaching the server. */
    var failNextPushBeforeServer: Boolean = false

    /** The next push is applied by the server but its response is lost. */
    var loseNextPushResponse: Boolean = false

    /** The next pull returns the previous pull's response again (a replayed page). */
    var replayNextPull: Boolean = false

    /** If set, the next push sends this text instead of the client's body. */
    var substituteNextPushBody: String? = null

    /** What `/meta` answers; null = unreadable. */
    var meta: () -> SyncLimits? = { server.limits() }

    val pushedBodies: MutableList<String> = ArrayList()
    val pullSinces: MutableList<Long> = ArrayList()
    private var lastPull: TransportResponse? = null

    override suspend fun push(body: String): TransportResponse {
        if (offline) return TransportResponse.NetworkError("offline")
        if (failNextPushBeforeServer) {
            failNextPushBeforeServer = false
            return TransportResponse.NetworkError("connection reset before the request was sent")
        }
        val sent = substituteNextPushBody ?: body
        substituteNextPushBody = null
        pushedBodies.add(sent)
        val r = server.push(sent)
        if (loseNextPushResponse) {
            loseNextPushResponse = false
            return TransportResponse.NetworkError("response lost")
        }
        return r
    }

    override suspend fun pull(since: Long): TransportResponse {
        if (offline) return TransportResponse.NetworkError("offline")
        pullSinces.add(since)
        val previous = lastPull
        if (replayNextPull && previous != null) {
            replayNextPull = false
            return previous
        }
        return server.pull(since).also { lastPull = it }
    }

    override suspend fun limits(): SyncLimits? = if (offline) null else meta()
}
