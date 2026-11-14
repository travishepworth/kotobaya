package kotobaya.sync

import kotobaya.auth.Principal
import kotobaya.auth.PushOpAuth
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.EnvelopeError
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType
import kotobaya.server.Problems
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory

private val opLog = LoggerFactory.getLogger("kotobaya.sync.ops")

/** Outcome of [PushService.push]. */
sealed interface PushOutcome {
    /** Whole-request failure (HTTP 400 `invalid_request`): nothing was applied. */
    data class BadRequest(val message: String) : PushOutcome

    /** HTTP 200 with `PushResponse` ([body] serializes as `batch.schema.json#/$defs/PushResponse`). */
    data class Ok(val body: Map<String, Any?>, val results: List<PushResult>) : PushOutcome
}

/** One `PushResult`. */
data class PushResult(val opId: String, val status: String, val seq: Long? = null, val error: Rejection? = null) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("op_id", opId)
        put("status", status)
        seq?.let { put("seq", it) }
        error?.let { put("error", mapOf("code" to it.code, "message" to it.message)) }
    }

    companion object {
        const val ACCEPTED = "accepted"
        const val DUPLICATE = "duplicate"
        const val REJECTED = "rejected"
    }
}

/**
 * `POST /sync/push` minus HTTP (SYNC.md 5.2–5.4). The body is parsed with the core's
 * duplicate-tolerant [PushBodyParser] (`PushRequestLenient` only); everything else is decided per
 * op, in array order, in **one** transaction holding the op-apply lock for the whole batch
 * (5.2 allows this; it equals one transaction per op because a rejected op touches nothing and an
 * unexpected failure rolls back the batch, which the client simply retries).
 *
 * Per op (first failure decides, 5.2/5.3 order):
 * 1. `op_id` already in `change` → `duplicate` with the original seq.
 * 2. duplicate key / deeper than 500 → `schema_invalid` (C-2, C-5).
 * 3. `rawSizeBytes > max_op_bytes` → `op_too_large`.
 * 4. decode: unknown type/version → `unsupported_op`; schema (ECMA patterns) → `schema_invalid`;
 *    envelope rules → `schema_invalid`.
 * 5. `device_mismatch`, `insufficient_scope` ([PushOpAuth]).
 * 6. `server_only`; 7. `unknown_reference`; 8. `clock_ahead`, `clock_behind`;
 *    9. `invalid_value`, `invalid_target`, `immutable`.
 * 10. observe(hlc), apply, log, merge policy → `accepted` with the new seq.
 */
class PushService(private val opApply: OpApply) {
    private val config get() = opApply.config

    fun push(text: String, principal: Principal): PushOutcome {
        val parsed = PushBodyParser.parse(text)
        if (parsed is PushBody.BadRequest) return PushOutcome.BadRequest(parsed.message)
        val elements = (parsed as PushBody.Ops).elements
        val outcome = apply(elements, principal)
        // Logged after commit only: a batch that rolled back reported nothing to the client.
        elements.zip(outcome.results).forEach { (el, r) -> logOutcome(el, r) }
        return outcome
    }

    private fun apply(elements: List<PushElement>, principal: Principal): PushOutcome.Ok =
        opApply.transactionBlocking(principal.tokenId) { tx ->
            // The log has no identity until the startup sequence ran: nothing may be applied before it (6.3).
            logIdentity(tx.dsl, 0) ?: throw Problems.unavailable("The sync log epoch is not initialised yet.")
            val results = elements.map { one(tx, it, principal) }
            principal.deviceId?.let {
                tx.dsl.execute("update app.device set last_push_at = now(), last_seen_at = now() where id = ?", it)
            }
            tx.persistDurableSeq() // 6.2 (C-8): the reported value is the persisted row
            val hw = tx.highWaterSeq()
            val id = checkNotNull(logIdentity(tx.dsl, hw)) { "log_epoch vanished inside the push transaction" }
            val body = linkedMapOf<String, Any?>(
                "results" to results.map { it.toMap() },
                "high_water_seq" to hw,
                "server_time" to config.clock().toEpochMilli(),
                "log_epoch" to id.logEpoch,
                "durable_seq" to id.durableSeq,
            )
            PushOutcome.Ok(body, results)
        }

    /**
     * OBS-1s: a duplicate is INFO (a client retry), a rejection WARN, each with `op_id`, `op_type` and the reason.
     * The caller's `request_id`, `token.*` and `device_id` come from the MDC. Accepted ops are not logged one by one.
     */
    private fun logOutcome(el: PushElement, r: PushResult) {
        val type = (el as? PushElement.Parsed)?.tree?.str("op_type") ?: "unknown"
        when (r.status) {
            PushResult.DUPLICATE -> opLog.info(
                "op duplicate", kv("op_id", r.opId), kv("op_type", type), kv("outcome", r.status), kv("seq", r.seq),
            )
            PushResult.REJECTED -> opLog.warn(
                "op rejected: {}", r.error?.code, kv("op_id", r.opId), kv("op_type", type), kv("outcome", r.status),
                kv("reason", r.error?.code), kv("reason_message", logSafeReason(r.error)),
            )
        }
    }

    /**
     * The rejection message as it may be logged. A `schema_invalid` message can quote the rejected value (note text
     * must never reach logs), so only its JSON pointer is kept; other codes carry fixed or numeric messages.
     */
    private fun logSafeReason(e: Rejection?): String? {
        if (e == null) return null
        if (e.code != RejectCode.SCHEMA_INVALID) return e.message.take(300)
        val pointer = Regex(" at '([^']{0,200})'$").find(e.message)?.groupValues?.get(1)
        return if (pointer != null) "invalid value at '$pointer'" else "schema invalid"
    }

    private fun reject(opId: String, code: String, message: String) = PushResult(opId, PushResult.REJECTED, error = Rejection(code, message))

    private fun one(tx: OpTx, el: PushElement, principal: Principal): PushResult {
        val opId = el.opId
        tx.seqOf(opId)?.let { return PushResult(opId, PushResult.DUPLICATE, seq = it) }
        val tree = when (el) {
            is PushElement.DuplicateKey -> return reject(opId, RejectCode.SCHEMA_INVALID, "the op repeats a key in one of its objects")
            is PushElement.TooDeep -> return reject(opId, RejectCode.SCHEMA_INVALID, "the op is nested deeper than ${DefaultCanonicalJson.MAX_OP_DEPTH}")
            is PushElement.Parsed -> el.tree
        }
        val size = DefaultOpCodec.rawSizeBytes(tree)
        if (size > config.maxOpBytes) return reject(opId, RejectCode.OP_TOO_LARGE, "op is $size bytes; max_op_bytes is ${config.maxOpBytes}")
        val op = when (val d = DefaultOpCodec.decodeTree(tree, OpSource.PUSHED)) {
            is OpDecodeResult.Unsupported ->
                return reject(opId, RejectCode.UNSUPPORTED_OP, "${d.opType} version ${d.opVersion} is not supported by this server")
            is OpDecodeResult.Invalid -> return reject(opId, RejectCode.SCHEMA_INVALID, "${d.message} at '${d.pointer}'")
            is OpDecodeResult.Known -> d.op
        }
        DefaultOpCodec.envelopeErrors(op, OpSource.PUSHED).firstOrNull()?.let { return reject(opId, RejectCode.SCHEMA_INVALID, describe(it)) }
        PushOpAuth.check(principal, op.deviceId, op.opType.wire)?.let { code ->
            val msg = if (code == PushOpAuth.INSUFFICIENT_SCOPE) {
                "${op.opType.wire} needs the ${PushOpAuth.requiredScope(op.opType.wire)} scope"
            } else {
                "device_id ${op.deviceId} is not the device this credential belongs to"
            }
            return reject(opId, code, msg)
        }
        val rejection = tx.rules.serverOnly(op)
            ?: tx.rules.references(op)
            ?: clockCheck(op, tx.now().toEpochMilli())
            ?: tx.rules.values(op, pushed = true)
        if (rejection != null) return PushResult(opId, PushResult.REJECTED, error = rejection)
        tx.clock.observe(op.hlc)
        val logged = tx.applyAndLog(op, causedByOpId = null)
        return PushResult(opId, PushResult.ACCEPTED, seq = logged.seq)
    }

    private fun describe(e: EnvelopeError): String = when (e) {
        EnvelopeError.ENTITY_TYPE_MISMATCH -> "entity_type does not match op_type"
        EnvelopeError.ENTITY_ID_MISMATCH -> "entity_id does not match the payload"
        EnvelopeError.DEVICE_ID_MISMATCH -> "device_id differs from the device part of hlc"
        EnvelopeError.SEQ_PRESENCE -> "seq and caused_by_op_id must be absent on a pushed op"
    }

    companion object {
        /**
         * The clock bounds of SYNC.md 3.4 for a pushed op at server time [nowMs]: `clock_ahead` when
         * `wall(hlc) > now + 24 h + 60 s` or a review's `reviewed_at > now + 24 h`; then `clock_behind`
         * when either is before 2020-01-01.
         */
        fun clockCheck(op: Op, nowMs: Long): Rejection? {
            val w = wall(op.hlc)
            val reviewedAt = if (op.opType == OpType.REVIEW_LOGGED) op.payload.long("reviewed_at") else null
            return when {
                w > nowMs + Reserved.CLOCK_TOLERANCE_MS + Reserved.CLOCK_EPSILON_MS ->
                    Rejection(RejectCode.CLOCK_AHEAD, "hlc wall time is more than 24 h after server time")
                reviewedAt != null && reviewedAt > nowMs + Reserved.CLOCK_TOLERANCE_MS ->
                    Rejection(RejectCode.CLOCK_AHEAD, "reviewed_at is more than 24 h after server time")
                w < Reserved.CLOCK_FLOOR_MS -> Rejection(RejectCode.CLOCK_BEHIND, "hlc wall time is before 2020-01-01")
                reviewedAt != null && reviewedAt < Reserved.CLOCK_FLOOR_MS -> Rejection(RejectCode.CLOCK_BEHIND, "reviewed_at is before 2020-01-01")
                else -> null
            }
        }
    }
}
