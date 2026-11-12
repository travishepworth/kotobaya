package kotobaya.sync.client.store

import kotobaya.contracts.HlcString
import kotobaya.contracts.InstantMs
import kotobaya.contracts.Uuid

/**
 * Keys of `sync_state`. The contract keys are those of `Local.sq` and SYNC.md 10.5; the
 * `meta.*` and `client.*` keys are device-local bookkeeping of this client (not part of the
 * contract, never synced).
 */
public object SyncStateKeys {
    /** This device's uuid (set at pairing). */
    public const val DEVICE_ID: String = "device_id"

    /** Highest `seq` fully applied locally (decimal; absent = 0). */
    public const val PULL_SEQ: String = "pull_seq"

    /** Last HLC minted or observed (full 54-character string; absent = `(0, 0)`). */
    public const val HLC_LAST: String = "hlc_last"

    /** Epoch ms of the last complete sync cycle. */
    public const val LAST_SYNC_AT: String = "last_sync_at"

    /** `"1"` while a rebuild (SYNC.md 10.3) is in progress. */
    public const val REBUILD_PENDING: String = "rebuild_pending"

    /** The server `log_epoch` that `pull_seq` and `acked_seq` belong to. */
    public const val LOG_EPOCH: String = "log_epoch"

    /** Latest `durable_seq` of that epoch (decimal; absent = 0). */
    public const val DURABLE_SEQ: String = "durable_seq"

    /** Greatest hlc among this device's acknowledged ops. */
    public const val HLC_ACKED: String = "hlc_acked"

    /** `server_time − pt` of the first clock rejection (signed decimal ms). */
    public const val REMINT_OFFSET: String = "remint_offset"

    /** Device-local: `limits.max_op_bytes` of the last `GET /meta` (decimal). Absent = default. */
    public const val META_MAX_OP_BYTES: String = "meta.max_op_bytes"

    /** Device-local: `limits.max_push_bytes` of the last `GET /meta` (decimal). Absent = default. */
    public const val META_MAX_PUSH_BYTES: String = "meta.max_push_bytes"

    /**
     * Device-local: `server_time − pt` (signed ms) of the latest push or pull response (SYNC.md 3.4
     * client duties). Absent = never measured.
     */
    public const val SERVER_SKEW_MS: String = "client.server_skew_ms"
}

/** Typed view of [SyncStateTable] with the defaults of SYNC.md 10.5 ("absent means"). */
public class SyncBookkeeping(
    /** The underlying table. */
    public val table: SyncStateTable,
) {
    /** `device_id`, or null before pairing. */
    public val deviceId: Uuid? get() = table.get(SyncStateKeys.DEVICE_ID)

    /** `pull_seq` (absent = 0). */
    public var pullSeq: Long
        get() = long(SyncStateKeys.PULL_SEQ) ?: 0L
        set(v) = table.put(SyncStateKeys.PULL_SEQ, v.toString())

    /** `hlc_last`, or null. */
    public var hlcLast: HlcString?
        get() = table.get(SyncStateKeys.HLC_LAST)
        set(v) = putOrRemove(SyncStateKeys.HLC_LAST, v)

    /** `last_sync_at`, or null. */
    public var lastSyncAt: InstantMs?
        get() = long(SyncStateKeys.LAST_SYNC_AT)
        set(v) = putOrRemove(SyncStateKeys.LAST_SYNC_AT, v?.toString())

    /** `rebuild_pending` is set. */
    public val rebuildPending: Boolean get() = table.get(SyncStateKeys.REBUILD_PENDING) != null

    /** `log_epoch`, or null when not yet known. */
    public var logEpoch: Uuid?
        get() = table.get(SyncStateKeys.LOG_EPOCH)
        set(v) = putOrRemove(SyncStateKeys.LOG_EPOCH, v)

    /** `durable_seq` (absent = 0). */
    public var durableSeq: Long
        get() = long(SyncStateKeys.DURABLE_SEQ) ?: 0L
        set(v) = table.put(SyncStateKeys.DURABLE_SEQ, v.toString())

    /** `hlc_acked`, or null. */
    public var hlcAcked: HlcString?
        get() = table.get(SyncStateKeys.HLC_ACKED)
        set(v) = putOrRemove(SyncStateKeys.HLC_ACKED, v)

    /** `remint_offset`, or null. */
    public var remintOffset: Long?
        get() = long(SyncStateKeys.REMINT_OFFSET)
        set(v) = putOrRemove(SyncStateKeys.REMINT_OFFSET, v?.toString())

    /** Stored `max_op_bytes`, or null when `/meta` was never read. */
    public var maxOpBytes: Long?
        get() = long(SyncStateKeys.META_MAX_OP_BYTES)
        set(v) = putOrRemove(SyncStateKeys.META_MAX_OP_BYTES, v?.toString())

    /** Stored `max_push_bytes`, or null when `/meta` was never read. */
    public var maxPushBytes: Long?
        get() = long(SyncStateKeys.META_MAX_PUSH_BYTES)
        set(v) = putOrRemove(SyncStateKeys.META_MAX_PUSH_BYTES, v?.toString())

    /** Latest measured `server_time − pt`, or null. */
    public var serverSkewMs: Long?
        get() = long(SyncStateKeys.SERVER_SKEW_MS)
        set(v) = putOrRemove(SyncStateKeys.SERVER_SKEW_MS, v?.toString())

    private fun long(key: String): Long? = table.get(key)?.toLongOrNull()

    private fun putOrRemove(key: String, value: String?) {
        if (value == null) table.remove(key) else table.put(key, value)
    }
}

/** The typed bookkeeping view of this transaction's `sync_state`. */
public val StoreTransaction<*>.bookkeeping: SyncBookkeeping get() = SyncBookkeeping(syncState)
