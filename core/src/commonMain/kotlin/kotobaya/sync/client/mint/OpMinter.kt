package kotobaya.sync.client.mint

import kotobaya.contracts.HlcString
import kotobaya.contracts.InstantMs
import kotobaya.contracts.sync.ClockGuardResult
import kotobaya.contracts.sync.Emitter
import kotobaya.contracts.sync.HlcRules
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpCodec
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.protocol.SyncLimits
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.OpIdSource
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.bookkeeping

/** One change to mint: an op type and its payload. Envelope fields are filled in by [OpMinter]. */
public data class MintRequest(
    /** The op type; must be client-emitted (`ops/index.json` `emitter`). */
    public val type: OpType,
    /** The payload, validated against `ops/<type>.schema.json` before anything is written. */
    public val payload: JsonObject,
)

/** Result of [OpMinter.mint] / [OpMinter.mintAll]. */
public sealed interface MintResult {
    /** Every op was minted, applied locally and queued, in one transaction. */
    public data class Minted(
        /** The minted ops, in request order (ascending hlc). */
        public val ops: List<Op>,
    ) : MintResult

    /** Nothing was minted, applied or queued. The UI maps [reason] to an i18n message. */
    public data class Refused(
        /** Why. */
        public val reason: MintRefusal,
    ) : MintResult
}

/** Why a change is refused before anything is minted (SYNC.md 3.4, 4, 2.1, 10.3). */
public sealed interface MintRefusal {
    /** `sync_state.device_id` is absent: the device is not paired. */
    public data object NotPaired : MintRefusal

    /** `rebuild_pending` is set (SYNC.md 10.3): reviewing and editing are blocked while rebuilding. */
    public data object RebuildPending : MintRefusal

    /** Device-local guard G1 or G2 holds (SYNC.md 3.4): "fix your device clock". */
    public data class ClockGuard(
        /** Which guard. */
        public val guard: ClockGuardResult,
    ) : MintRefusal

    /**
     * A response showed the clock more than 24 h off the server's, and no later response has shown
     * it within 5 minutes (SYNC.md 3.4 client duties; `client.clock_blocked`).
     */
    public data class ClockFarFromServer(
        /** The latest `server_time − pt`, ms (may already be under 24 h: the block lifts only under 5 min). */
        public val skewMs: Long?,
    ) : MintRefusal

    /** A server-emitted op type (`emitter = server`): a client never mints it. */
    public data class ServerOnly(
        /** Index in the request list. */
        public val index: Int,
        /** The op type. */
        public val type: OpType,
    ) : MintRefusal

    /** The op's size (SYNC.md 5.4) exceeds the stored `max_op_bytes` (SYNC.md 4 "Size"). */
    public data class TooLarge(
        /** Index in the request list. */
        public val index: Int,
        /** `utf8Size(CanonicalJson(op))`. */
        public val sizeBytes: Int,
        /** The limit in force (last `/meta`, else 1 MiB). */
        public val maxOpBytes: Long,
    ) : MintRefusal

    /**
     * The op breaks its schema, an ECMA pattern, a storable-value bound, `NO_NUL` or the 500 depth
     * limit, or its envelope is inconsistent (SYNC.md 2.1, 4): the server would answer
     * `schema_invalid`.
     */
    public data class Invalid(
        /** Index in the request list. */
        public val index: Int,
        /** English diagnostic (logs only). */
        public val message: String,
        /** JSON Pointer into the op, e.g. `/payload/tags/0`. */
        public val pointer: String,
    ) : MintRefusal
}

/**
 * Making a change on a device (SYNC.md 4). In ONE [DeviceStore] transaction, per op: `h = mint()`,
 * build the envelope, apply the local effect through [applier] (the same code path as pulled ops),
 * insert the `pending_op` row, persist `hlc_last`. A crash at any point leaves either all of it or
 * none of it.
 *
 * Before anything is written, every requested op is checked exactly as the server would (refused,
 * nothing minted): device paired; not `rebuild_pending`; clock guards G1/G2 and the stored server
 * skew flag (`client.clock_blocked`: set above 24 h, cleared only under 5 min); client emitter; the op decodes as `Known` with [OpCodec.decodeTree] (schema
 * with ECMA patterns, storable values, `NO_NUL`, depth 500) and has no [OpCodec.envelopeErrors]; its
 * size is within the durable `max_op_bytes` (`meta.max_op_bytes`, default 1 MiB).
 *
 * Not checked here (caller duties of SYNC.md 4 / OPS.md): duplicate avoidance, references, and the
 * server-only variants of client op types.
 */
public class OpMinter<L>(
    private val store: DeviceStore<L>,
    private val applier: OpApplier<L>,
    private val clock: Clock,
    private val ids: OpIdSource,
    private val hlcRules: HlcRules = DefaultHlcRules,
    private val codec: OpCodec = DefaultOpCodec,
) {
    /** Mints one op. */
    public fun mint(type: OpType, payload: JsonObject): MintResult = mintAll(listOf(MintRequest(type, payload)))

    /**
     * Mints [requests] in order, in one transaction (e.g. a note and its cards): all of them or
     * none. Ops for a new row must come before ops that reference it (SYNC.md 4). Called inside an
     * outer [DeviceStore.transaction] it joins it (Re-mint discards rows and mints in one transaction).
     *
     * [at] is for **Re-mint only** (SYNC.md 10.4 step 6a): when non-null every HLC is minted with
     * `mint_at(at)` ([HlcRules.mintAt]) instead of `mint()`, so a re-minted creation gets the time
     * it really had; values still increase strictly above `hlc_last`. Every other check (guards
     * against the device's current clock, rebuild, clock block, schema, size) is unchanged. Never
     * pass [at] for an ordinary change.
     */
    public fun mintAll(requests: List<MintRequest>, at: InstantMs? = null): MintResult {
        require(requests.isNotEmpty()) { "nothing to mint" }
        return store.transaction { tx ->
            val bk = tx.bookkeeping
            val deviceId = bk.deviceId ?: return@transaction MintResult.Refused(MintRefusal.NotPaired)
            if (bk.rebuildPending) return@transaction MintResult.Refused(MintRefusal.RebuildPending)
            val now = clock.nowMs()
            val guard = hlcRules.guard(now, bk.hlcLast)
            if (guard != ClockGuardResult.OK) return@transaction MintResult.Refused(MintRefusal.ClockGuard(guard))
            if (bk.clockBlocked) return@transaction MintResult.Refused(MintRefusal.ClockFarFromServer(bk.serverSkewMs))
            val limits = SyncLimits.read(bk)
            var state = hlcRules.stateOf(bk.hlcLast)
            val ops = ArrayList<Op>(requests.size)
            for ((i, r) in requests.withIndex()) {
                if (r.type.emitter != Emitter.CLIENT) return@transaction MintResult.Refused(MintRefusal.ServerOnly(i, r.type))
                val minted = if (at == null) hlcRules.mint(state, now, deviceId) else hlcRules.mintAt(state, at, deviceId)
                state = minted.state
                val built = build(i, r, minted.hlc, deviceId, ids.newOpId(now), limits)
                when (built) {
                    is Built.Ok -> ops.add(built.op)
                    is Built.No -> return@transaction MintResult.Refused(built.reason)
                }
            }
            // every check passed: nothing was written before this point
            for (op in ops) {
                applier.apply(tx, op, ApplyOrigin.LOCAL)
                tx.outbox.insert(
                    PendingOpRow(
                        opId = op.opId,
                        opType = op.opType.wire,
                        opVersion = op.opVersion,
                        entityType = op.entityType,
                        entityId = op.entityId,
                        hlc = op.hlc,
                        payload = DefaultCanonicalJson.encode(op.payload),
                        createdAt = now,
                    ),
                )
            }
            bk.hlcLast = ops.last().hlc
            MintResult.Minted(ops)
        }
    }

    private sealed interface Built {
        class Ok(val op: Op) : Built

        class No(val reason: MintRefusal) : Built
    }

    private fun build(i: Int, r: MintRequest, hlc: HlcString, deviceId: String, opId: String, limits: SyncLimits): Built {
        val entityId = try {
            codec.entityIdOf(r.type, r.payload)
        } catch (e: IllegalArgumentException) {
            return Built.No(MintRefusal.Invalid(i, e.message ?: "entity_id cannot be derived", "/payload"))
        }
        val tree = LinkedHashMap<String, JsonValue>()
        tree["op_id"] = JsonString(opId)
        tree["op_type"] = JsonString(r.type.wire)
        tree["op_version"] = JsonNumber(OpType.MAX_SUPPORTED_VERSION.toDouble())
        tree["entity_type"] = JsonString(r.type.entityType)
        tree["entity_id"] = JsonString(entityId)
        tree["device_id"] = JsonString(deviceId)
        tree["hlc"] = JsonString(hlc)
        tree["payload"] = r.payload
        // the same decode the server runs (5.3 schema_invalid step): depth, NO_NUL, schema, ECMA patterns
        val op = when (val d = codec.decodeTree(JsonObject(tree), OpSource.PUSHED)) {
            is OpDecodeResult.Known -> d.op
            is OpDecodeResult.Invalid -> return Built.No(MintRefusal.Invalid(i, d.message, d.pointer))
            is OpDecodeResult.Unsupported -> return Built.No(MintRefusal.Invalid(i, "unsupported ${d.opType} v${d.opVersion}", "/op_type"))
        }
        val envelope = codec.envelopeErrors(op, OpSource.PUSHED)
        if (envelope.isNotEmpty()) return Built.No(MintRefusal.Invalid(i, "envelope: $envelope", ""))
        val size = codec.sizeBytes(op)
        if (size > limits.maxOpBytes) return Built.No(MintRefusal.TooLarge(i, size, limits.maxOpBytes))
        return Built.Ok(op)
    }
}
