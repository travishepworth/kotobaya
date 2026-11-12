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

    /**
     * Device-local: `"1"` once a response showed `|server_time − pt| > 24 h`; deleted only when a
     * later response shows it below 5 minutes (SYNC.md 3.4 hysteresis). Minting is blocked while set.
     */
    public const val CLOCK_BLOCKED: String = "client.clock_blocked"

    /**
     * Device-local: the pull stopped in *upgrade required* (SYNC.md 6.1); canonical JSON
     * `{"error":…,"op_id":…,"seq":…,"unsupported":…}`. Deleted when a pull completes.
     */
    public const val UPGRADE_REQUIRED: String = "client.upgrade_required"

    /**
     * Device-local: the `log_epoch` stored by the last log reset (SYNC.md 6.2). Its presence means
     * "a log reset happened in the current epoch", which scopes the `unknown_reference` auto-retry
     * (T303b). Absent = no reset since the device first learned its epoch.
     */
    public const val LOG_RESET_EPOCH: String = "client.log_reset_epoch"
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

    /** `rebuild_pending` is set; setting `false` deletes the key, `true` writes `"1"`. */
    public var rebuildPending: Boolean
        get() = table.get(SyncStateKeys.REBUILD_PENDING) != null
        set(v) = putOrRemove(SyncStateKeys.REBUILD_PENDING, if (v) "1" else null)

    /** The 3.4 hysteresis flag ([SyncStateKeys.CLOCK_BLOCKED]). */
    public var clockBlocked: Boolean
        get() = table.get(SyncStateKeys.CLOCK_BLOCKED) != null
        set(v) = putOrRemove(SyncStateKeys.CLOCK_BLOCKED, if (v) "1" else null)

    /** [SyncStateKeys.UPGRADE_REQUIRED] raw value, or null. */
    public var upgradeRequired: String?
        get() = table.get(SyncStateKeys.UPGRADE_REQUIRED)
        set(v) = putOrRemove(SyncStateKeys.UPGRADE_REQUIRED, v)

    /** [SyncStateKeys.LOG_RESET_EPOCH], or null. */
    public var logResetEpoch: String?
        get() = table.get(SyncStateKeys.LOG_RESET_EPOCH)
        set(v) = putOrRemove(SyncStateKeys.LOG_RESET_EPOCH, v)

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

    /**
     * Records `server_time − pt` of a push or pull response (SYNC.md 3.4 client duties) with
     * hysteresis: `|skew| > 24 h` sets [clockBlocked]; only `|skew| < 5 min` clears it. A skew in
     * between leaves the flag as it was, so a clock fixed only partly (e.g. still 3 h off) stays
     * blocked.
     */
    public fun recordServerSkew(skewMs: Long) {
        serverSkewMs = skewMs
        val magnitude = if (skewMs < 0) -skewMs else skewMs
        if (magnitude > CLOCK_BLOCK_MS) clockBlocked = true else if (magnitude < CLOCK_WARN_MS) clockBlocked = false
    }

    /** Thresholds of SYNC.md 3.4. */
    public companion object {
        /** Above this `|server_time − pt|` minting is blocked: 24 h. */
        public const val CLOCK_BLOCK_MS: Long = 86_400_000L

        /** Above this the status warns; below it the block lifts: 5 minutes. */
        public const val CLOCK_WARN_MS: Long = 300_000L
    }

    private fun long(key: String): Long? = table.get(key)?.toLongOrNull()

    private fun putOrRemove(key: String, value: String?) {
        if (value == null) table.remove(key) else table.put(key, value)
    }
}

/** The typed bookkeeping view of this transaction's `sync_state`. */
public val StoreTransaction<*>.bookkeeping: SyncBookkeeping get() = SyncBookkeeping(syncState)
