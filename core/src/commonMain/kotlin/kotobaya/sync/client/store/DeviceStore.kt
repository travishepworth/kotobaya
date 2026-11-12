package kotobaya.sync.client.store

import kotobaya.contracts.HlcString
import kotobaya.contracts.InstantMs
import kotobaya.contracts.Uuid

/**
 * The device's local database as the sync client sees it (SYNC.md 4, 5.4, 6.1): the outbox
 * (`pending_op`), the bookkeeping key/value table (`sync_state`) and the learning tables, typed as
 * [L] so that the apply code (T303b) chooses how it reads and writes them.
 *
 * Implemented over the contract's `Local.sq` / `Learning.sq` by SQLDelight (T303c) and in memory by
 * [InMemoryDeviceStore] (tests, the T313 simulation).
 *
 * Contract every implementation keeps:
 * - [transaction] is atomic: if [block] throws, nothing it wrote (outbox, sync state, learning
 *   tables) is visible afterwards, and the exception propagates. Otherwise everything commits.
 * - A [transaction] called inside another one joins it (SQLDelight semantics): the inner block is
 *   part of the outer transaction and commits or rolls back with it.
 * - [block] never suspends: no network I/O happens inside a transaction.
 */
public interface DeviceStore<L> {
    /** Runs [block] in one local transaction and returns its result. */
    public fun <R> transaction(block: (StoreTransaction<L>) -> R): R
}

/** Everything one transaction can touch. Only valid inside the [DeviceStore.transaction] block. */
public interface StoreTransaction<L> {
    /** `pending_op`. */
    public val outbox: OutboxTable

    /** `sync_state`. */
    public val syncState: SyncStateTable

    /** The learning tables (`Learning.sq`, `Media.sq`), as the apply code (T303b) defines them. */
    public val learning: L
}

/** `pending_op.state` (Local.sq). */
public enum class PendingState(
    /** The column value. */
    public val wire: String,
) {
    /** Not yet accepted by the server. */
    PENDING("pending"),

    /** The server answered `accepted` or `duplicate`; [PendingOpRow.ackedSeq] is set. */
    ACKED("acked"),

    /** The server refused it permanently (SYNC.md 10.1); never purged automatically. */
    REJECTED("rejected"),
}

/**
 * One `pending_op` row (Local.sq). The op's `device_id` is not stored: it is the device part of
 * [hlc] (the last 36 characters).
 */
public data class PendingOpRow(
    /** `op_id`. */
    public val opId: Uuid,
    /** `op_type` (wire name, e.g. `note.created`). */
    public val opType: String,
    /** `op_version`. */
    public val opVersion: Int,
    /** `entity_type`. */
    public val entityType: String,
    /** `entity_id`. */
    public val entityId: String,
    /** `hlc`; push order is ascending hlc. */
    public val hlc: HlcString,
    /** `payload`: the payload object as canonical compact JSON (`CanonicalJson.encode`). */
    public val payload: String,
    /** `created_at`: device wall clock when the op was minted. */
    public val createdAt: InstantMs,
    /** `state`. */
    public val state: PendingState = PendingState.PENDING,
    /** `attempts`: failed push attempts (transient errors), reset by a log reset. */
    public val attempts: Int = 0,
    /** `last_error`: `"<code>: <message>"` once rejected. */
    public val lastError: String? = null,
    /** `acked_seq`: the server's `seq`, once acked. */
    public val ackedSeq: Long? = null,
)

/**
 * Typed access to `pending_op`. Each method is one statement; call them inside a transaction.
 * Methods addressing an `op_id` that does not exist do nothing.
 */
public interface OutboxTable {
    /** Inserts [row]. Throws [IllegalStateException] if its `op_id` already exists. */
    public fun insert(row: PendingOpRow)

    /** The row with [opId], or null. */
    public fun get(opId: Uuid): PendingOpRow?

    /** Rows in [state], ascending `hlc`, at most [limit] (index `pending_op_state_idx`). */
    public fun inState(state: PendingState, limit: Int = Int.MAX_VALUE): List<PendingOpRow>

    /** `state = 'acked'`, `acked_seq = seq` (SYNC.md 5.4). */
    public fun markAcked(opId: Uuid, seq: Long)

    /** `state = 'rejected'`, `last_error = lastError`, `acked_seq = NULL` (SYNC.md 5.4, 10.1). */
    public fun markRejected(opId: Uuid, lastError: String)

    /**
     * `state = 'pending'`, `acked_seq = NULL`, `attempts = attempts`; `last_error` unchanged. Used by
     * the log reset (SYNC.md 6.2) and Retry (10.2), which are T303b / T303d.
     */
    public fun markPending(opId: Uuid, attempts: Int)

    /** `attempts = attempts + 1` for each of [opIds] (a transient push failure, SYNC.md 5.4). */
    public fun incrementAttempts(opIds: Collection<Uuid>)

    /** Deletes the row (Discard, T303d). */
    public fun delete(opId: Uuid)

    /** Deletes every `acked` row with `acked_seq <= maxSeq` and returns how many (SYNC.md 5.4 purge). */
    public fun deleteAckedThrough(maxSeq: Long): Int

    /** Number of rows in [state]. */
    public fun count(state: PendingState): Int
}

/** Typed access to `sync_state` (Local.sq; keys in [SyncStateKeys]). Values are text. */
public interface SyncStateTable {
    /** The value of [key], or null when absent. */
    public fun get(key: String): String?

    /** Inserts or replaces [key]. */
    public fun put(key: String, value: String)

    /** Deletes [key] (no-op when absent). */
    public fun remove(key: String)
}
