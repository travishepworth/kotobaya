package kotobaya.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType
import kotobaya.server.Database
import kotobaya.server.Limits
import kotobaya.server.SERVER_DEVICE_ID
import kotobaya.server.SYNC_LOCK
import kotobaya.server.ServerStateKeys
import kotobaya.server.Uuid7
import org.jooq.DSLContext
import java.time.Instant
import java.util.UUID

/** Everything the op-apply path is configured with. Defaults are the documented no-ops of [Hooks]. */
data class SyncConfig(
    /** Server wall clock: HLC physical time and the clock checks of SYNC.md 3.4. */
    val clock: () -> Instant = Instant::now,
    val cardCache: CardCacheHook = CardCacheHook.NoOp,
    val autoStatus: AutoStatusHook = AutoStatusHook.NoOp,
    val merge: MergePolicy = MergePolicy.Unavailable,
    val events: SyncEvents = SyncEvents.NoOp,
    /** `limits.max_op_bytes` (SYNC.md 5.4); the server never mints a larger op either. */
    val maxOpBytes: Int = Limits.MAX_OP_BYTES,
    /** `limits.max_push_bytes`: a push body above it is 413; a pull page ends before exceeding it. */
    val maxPushBytes: Int = Limits.MAX_PUSH_BYTES,
)

/**
 * **The** op-apply entry point (CLAUDE.md rule 8, SYNC.md 5.2, openapi §3). Every write to learning
 * data goes through an [OpTx]: `POST /sync/push`, REST handlers (T201b, T204, T206, T210a, …) and
 * jobs (T207b, …).
 *
 * A transaction takes `pg_advisory_xact_lock(7033001)` first and holds it until commit, so `seq`
 * values commit in order and an id-existence check followed by an emit is race-free (openapi §3
 * "Idempotent creation"). The server HLC is loaded after the lock and persisted before commit.
 *
 * REST usage:
 * ```
 * val seq = opApply.transaction(tokenId) { tx ->
 *     if (tx.dsl.fetchExists(...id exists...)) return@transaction null      // 200, nothing emitted
 *     tx.emit(OpType.NOTE_CREATED, payload)                                   // throws OpInputInvalid / OpRefused
 *     cardPayloads.forEach { tx.emit(OpType.CARD_CREATED, it) }
 *     tx.lastSeq                                                              // → Kotobaya-Seq header
 * }
 * ```
 * Letting [OpInputInvalid] or [OpRefused] escape rolls the transaction back: nothing is minted
 * (SYNC.md 10.5 last paragraph). Map them with their `toApiException()`.
 *
 * `Kotobaya-Seq` decisions (FOLLOWUPS T004): the header is the [OpTx.lastSeq] of the call and is
 * absent when nothing was emitted, including an idempotent retry that finds the id already there
 * (openapi §3 literally); a seq is meaningful only within the `log_epoch` it was issued in.
 */
class OpApply(private val db: Database, val config: SyncConfig = SyncConfig()) {
    /** Runs [block] in one op transaction on the IO dispatcher. */
    suspend fun <T> transaction(tokenId: UUID? = null, block: (OpTx) -> T): T =
        withContext(Dispatchers.IO) { transactionBlocking(tokenId, block) }

    fun <T> transactionBlocking(tokenId: UUID? = null, block: (OpTx) -> T): T = db.txBlocking { raw ->
        raw.execute("select pg_advisory_xact_lock(?)", SYNC_LOCK)
        val tx = OpTx(AppendOnlyGuard.guard(raw), config, tokenId)
        val result = block(tx)
        tx.finish()
        result
    }
}

/**
 * One op transaction (see [OpApply]). [dsl] is the transaction's connection with the append-only
 * guard installed: `UPDATE`/`DELETE` on `app.change` or `app.review_log` throws [AppendOnlyViolation].
 */
class OpTx internal constructor(
    val dsl: DSLContext,
    private val config: SyncConfig,
    /** `change.token_id` of every op logged in this transaction (the calling token, or null for jobs). */
    val tokenId: UUID?,
) {
    /** The server HLC; T202c's merge policy mints merge clocks with it. */
    val clock = ServerClock(dsl) { config.clock().toEpochMilli() }
    internal val rules = OpRules(dsl)
    private val applier = OpApplier(dsl, config.cardCache, config.autoStatus) { op, c -> config.merge.collision(this, op, c) }
    private val logged = mutableListOf<LoggedOp>()

    /** Every op this transaction logged so far, in seq order (consequences included). */
    val loggedOps: List<LoggedOp> get() = logged.toList()

    /** `seq` of the last op logged, or null: the `Kotobaya-Seq` header value. */
    val lastSeq: Long? get() = logged.lastOrNull()?.seq

    /**
     * Mints an op with the server device id and a server HLC, validates it, applies it, logs it,
     * runs the merge policy and logs any consequences (with `caused_by_op_id`), all in this
     * transaction.
     *
     * Validation of an op the server mints for itself:
     * - [OpInputInvalid] when the op fails its schema with the ECMA-262 pattern semantics of
     *   SYNC.md 2.1 (a tag with U+3000, an id with a trailing newline, …), nests deeper than
     *   `MAX_OP_DEPTH` (500), or is larger than `max_op_bytes` (`code = too_long`). Nothing is minted.
     * - [OpRefused] for `unknown_reference`, `invalid_value`, `invalid_target`, `immutable`.
     * - Not checked: clock bounds, `server_only`, scopes (SYNC.md 3.4, 5.3).
     *
     * [causedByOpId] is set for ops emitted as a consequence of another op (merge, cascade).
     */
    fun emit(type: OpType, payload: JsonObject, causedByOpId: String? = null): LoggedOp {
        val op = build(type, payload, clock.mint())
        validateOwn(op)?.let { throw it }
        (rules.references(op) ?: rules.values(op, pushed = false))?.let { throw OpRefused(it) }
        return applyAndLog(op, causedByOpId)
    }

    /**
     * Dry run of [emit]'s [OpInputInvalid] checks (schema, patterns, depth, size) without minting:
     * lets a REST handler answer `422` before it starts emitting a multi-op change.
     */
    fun check(type: OpType, payload: JsonObject): OpInputInvalid? = validateOwn(build(type, payload, clock.last()))

    private fun build(type: OpType, payload: JsonObject, hlc: String): Op {
        val entityId = try {
            DefaultOpCodec.entityIdOf(type, payload)
        } catch (e: IllegalArgumentException) {
            throw OpInputInvalid("", e.message ?: "payload lacks an entity id part")
        }
        val now = config.clock().toEpochMilli()
        return Op(Uuid7.generate(now).toString(), type, 1, type.entityType, entityId, SERVER_DEVICE_ID, hlc, payload)
    }

    private fun validateOwn(op: Op): OpInputInvalid? {
        val tree = try {
            DefaultOpCodec.encodeTree(op)
        } catch (e: IllegalArgumentException) {
            return OpInputInvalid("", e.message ?: "the op cannot be encoded") // too deep or a lone surrogate
        }
        when (val d = DefaultOpCodec.decodeTree(tree, OpSource.PUSHED)) {
            is OpDecodeResult.Known -> Unit
            is OpDecodeResult.Invalid -> return OpInputInvalid(d.pointer.removePrefix("/payload"), d.message)
            is OpDecodeResult.Unsupported -> return OpInputInvalid("", "unsupported op ${d.opType} v${d.opVersion}")
        }
        val size = DefaultOpCodec.rawSizeBytes(tree)
        if (size > config.maxOpBytes) return OpInputInvalid("", "the change is $size bytes; max_op_bytes is ${config.maxOpBytes}", "too_long")
        return null
    }

    /** `change.seq` of an already logged op, or null (SYNC.md 5.2 step 1). */
    fun seqOf(opId: String): Long? =
        (dsl.fetchValue("select seq from app.change where op_id = ?::uuid", opId) as Number?)?.toLong()

    /** Applies a validated op, writes its `change` row, then the merge policy and consequences (5.2 step 3). */
    internal fun applyAndLog(op: Op, causedByOpId: String?): LoggedOp {
        val followUps = applier.apply(op)
        val seq = (
            dsl.fetchValue(
                "insert into app.change (op_id, op_type, op_version, entity_type, entity_id, device_id, hlc, payload, caused_by_op_id, token_id) " +
                    "values (?::uuid, ?, ?, ?, ?, ?::uuid, ?, ?::jsonb, ?::uuid, ?::uuid) returning seq",
                op.opId, op.opType.wire, op.opVersion, op.entityType, op.entityId, op.deviceId, op.hlc,
                DefaultCanonicalJson.encode(op.payload), causedByOpId, tokenId,
            ) as Number
            ).toLong()
        val entry = LoggedOp(op.copy(seq = seq, causedByOpId = causedByOpId), seq)
        logged += entry
        config.merge.afterLogged(this, op, seq)
        followUps.forEach { emit(it.type, it.payload, op.opId) }
        return entry
    }

    /** Highest committed-or-own `seq` (0 for an empty log). */
    fun highWaterSeq(): Long = (dsl.fetchValue("select coalesce(max(seq), 0) from app.change") as Number).toLong()

    internal fun finish() {
        clock.persist()
        if (logged.isNotEmpty()) config.events.logged(dsl, highWaterSeq(), logged.toList())
    }

    internal fun now(): Instant = config.clock()
}

/** `log_epoch` and `durable_seq` as reported in push/pull responses (SYNC.md 6.2, 6.3). */
internal data class LogIdentity(val logEpoch: String, val durableSeq: Long)

internal fun logIdentity(dsl: DSLContext, highWater: Long): LogIdentity? {
    val epoch = dsl.fetchOptional("select value from app.server_state where key = ?", ServerStateKeys.LOG_EPOCH)
        .map { it.get(0, String::class.java) }.orElse(null) ?: return null
    val durable = dsl.fetchOptional("select value from app.server_state where key = ?", ServerStateKeys.DURABLE_SEQ)
        .map { it.get(0, String::class.java).toLongOrNull() ?: 0L }.orElse(0L)
    // Never report a durable_seq above the high-water mark (6.3). Backups-disabled reporting is T202c.
    return LogIdentity(epoch, minOf(durable, highWater))
}
