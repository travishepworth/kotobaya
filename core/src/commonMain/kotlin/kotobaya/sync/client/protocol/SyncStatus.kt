package kotobaya.sync.client.protocol

import kotobaya.contracts.HlcString
import kotobaya.contracts.InstantMs
import kotobaya.contracts.Uuid
import kotobaya.contracts.sync.ClockGuardResult
import kotobaya.contracts.sync.HlcRules
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.bookkeeping
import kotlin.math.abs

/** What the sync status shows first (the UI maps each to an i18n string). */
public sealed interface SyncPhase {
    /** Nothing to report. */
    public data object Idle : SyncPhase

    /** A cycle is running. */
    public data object Syncing : SyncPhase

    /** The last cycle got no response. */
    public data object Offline : SyncPhase

    /** The last cycle got a 5xx/429/unexpected status or an unusable body. */
    public data class ServerUnreachable(
        /** HTTP status, if any. */
        public val httpStatus: Int?,
    ) : SyncPhase

    /** 401/403: re-pair or log in (SYNC.md 5.4). */
    public data object AuthRequired : SyncPhase

    /** A client bug: whole-push 400 or a local apply failure (SYNC.md 5.4). */
    public data class ClientError(
        /** HTTP status, if any. */
        public val httpStatus: Int?,
    ) : SyncPhase

    /** *Update required* (SYNC.md 6.1): the pull stopped at an op this client cannot apply. */
    public data class UpgradeRequired(
        /** Its `seq`. */
        public val seq: Long?,
        /** Its `op_id`. */
        public val opId: Uuid?,
        /** `true` = unknown type/version; `false` = the server sent an invalid op. */
        public val unsupported: Boolean,
        /** The first validation error (diagnostic). */
        public val firstError: String,
    ) : SyncPhase

    /**
     * "Fix your device clock" (SYNC.md 3.4): guard G1/G2 holds, or the last response showed more than
     * 24 h difference. Reviewing and editing are blocked.
     */
    public data class ClockWrong(
        /** The guard that holds, or null when only the server comparison failed. */
        public val guard: ClockGuardResult?,
        /** The last `server_time − pt`, if known. */
        public val skewMs: Long?,
    ) : SyncPhase

    /** `rebuild_pending` is set (SYNC.md 10.3): reviewing is blocked until the rebuild finishes. */
    public data object Rebuilding : SyncPhase
}

/** What the status needs from the store, read in one transaction with [read]. */
public data class SyncSnapshot(
    /** Rows `pending`. */
    public val pending: Int,
    /** Rows `acked` (not yet purged). */
    public val acked: Int,
    /** Rows `rejected` (SYNC.md 10.1: the count MUST be shown). */
    public val rejected: Int,
    /** `last_sync_at`. */
    public val lastSyncAt: InstantMs?,
    /** `rebuild_pending`. */
    public val rebuildPending: Boolean,
    /** `hlc_last`. */
    public val hlcLast: HlcString?,
    /** Last `server_time − pt`. */
    public val serverSkewMs: Long?,
    /** `client.clock_blocked` (SYNC.md 3.4 hysteresis: set above 24 h, cleared only under 5 min). */
    public val clockBlocked: Boolean = false,
    /** The persisted *upgrade required* stop, if the last pull stopped at an op (survives restarts). */
    public val upgradeRequired: UpgradeRequiredState? = null,
    /** Cards whose cache is the refused cache (SYNC.md 9.4), as reported by the learning layer. */
    public val refusedCacheCards: Int = 0,
) {
    /** Reader. */
    public companion object {
        /**
         * Reads the snapshot inside [tx]. [refusedCacheCards] counts refused caches in the learning
         * tables (T303b's diagnostics); the default reports none.
         */
        public fun <L> read(tx: StoreTransaction<L>, refusedCacheCards: (L) -> Int = { 0 }): SyncSnapshot {
            val bk = tx.bookkeeping
            return SyncSnapshot(
                pending = tx.outbox.count(PendingState.PENDING),
                acked = tx.outbox.count(PendingState.ACKED),
                rejected = tx.outbox.count(PendingState.REJECTED),
                lastSyncAt = bk.lastSyncAt,
                rebuildPending = bk.rebuildPending,
                hlcLast = bk.hlcLast,
                serverSkewMs = bk.serverSkewMs,
                clockBlocked = bk.clockBlocked,
                upgradeRequired = bk.upgradeRequired?.let { UpgradeRequiredState.decode(it) },
                refusedCacheCards = refusedCacheCards(tx.learning),
            )
        }
    }
}

/** The *upgrade required* stop as persisted in `client.upgrade_required` (SYNC.md 6.1). */
public data class UpgradeRequiredState(
    /** `seq` of the op, if readable. */
    public val seq: Long?,
    /** `op_id` of the op, if readable. */
    public val opId: Uuid?,
    /** `true` = unknown type/version; `false` = the server sent an invalid op. */
    public val unsupported: Boolean,
    /** The first validation error (diagnostic). */
    public val error: String,
) {
    /** Canonical JSON text for the key. */
    public fun encode(): String {
        val m = LinkedHashMap<String, JsonValue>()
        m["seq"] = seq?.let { JsonNumber(it.toDouble()) } ?: JsonNull
        m["op_id"] = opId?.let { JsonString(it) } ?: JsonNull
        m["unsupported"] = JsonBool(unsupported)
        m["error"] = JsonString(error)
        return DefaultCanonicalJson.encode(JsonObject(m))
    }

    /** Conversions. */
    public companion object {
        /** From a pull stop. */
        public fun of(r: CycleResult.UpgradeRequired): UpgradeRequiredState =
            UpgradeRequiredState(r.seq, r.opId, r.unsupported, r.error)

        /** Parses [encode] output; null for anything else (an unreadable key is ignored). */
        public fun decode(text: String): UpgradeRequiredState? {
            val m = (DefaultCanonicalJson.decode(text).value as? JsonObject)?.members ?: return null
            val error = (m["error"] as? JsonString)?.value ?: return null
            return UpgradeRequiredState(
                seq = (m["seq"] as? JsonNumber)?.value?.toLong(),
                opId = (m["op_id"] as? JsonString)?.value,
                unsupported = (m["unsupported"] as? JsonBool)?.value ?: false,
                error = error,
            )
        }
    }
}

/** The sync status model for the UI (T303e), a pure function of the store and the last result: [of]. */
public data class SyncStatus(
    /** What to show first. */
    public val phase: SyncPhase,
    /** Rows `pending`. */
    public val pendingCount: Int,
    /** Rows `acked` not yet purged. */
    public val ackedCount: Int,
    /** Rows `rejected`. */
    public val rejectedCount: Int,
    /** `last_sync_at`. */
    public val lastSyncAt: InstantMs?,
    /** Diagnostic of the last failure or stop, or null. */
    public val lastError: String?,
    /** `|server_time − pt| > 5 min` (SYNC.md 3.4: MUST show a clock warning). */
    public val clockWarning: Boolean,
    /** Reviewing and editing are blocked (clock wrong or rebuilding). */
    public val mintingBlocked: Boolean,
    /** Cards with the refused cache (SYNC.md 9.4 SHOULD surface them), from [SyncSnapshot.refusedCacheCards]. */
    public val refusedCacheCards: Int = 0,
) {
    /** The derivation. */
    public companion object {
        /** 5 minutes: above this skew the status warns (SYNC.md 3.4). */
        public const val CLOCK_WARN_MS: Long = 300_000L

        /** 24 hours: above this skew minting is blocked (SYNC.md 3.4). */
        public const val CLOCK_BLOCK_MS: Long = 86_400_000L

        /**
         * Precedence: [SyncPhase.Syncing] (when [running]) > [SyncPhase.ClockWrong] > [SyncPhase.Rebuilding]
         * > what [last] says > [SyncPhase.Idle]. [nowMs] is the device clock (for guards G1/G2).
         */
        public fun of(
            snapshot: SyncSnapshot,
            last: CycleResult?,
            running: Boolean,
            nowMs: InstantMs,
            hlcRules: HlcRules = DefaultHlcRules,
        ): SyncStatus {
            val skew = snapshot.serverSkewMs
            val guard = hlcRules.guard(nowMs, snapshot.hlcLast)
            val clockBlocked = guard != ClockGuardResult.OK || snapshot.clockBlocked || (skew != null && abs(skew) > CLOCK_BLOCK_MS)
            val persisted = snapshot.upgradeRequired?.let { SyncPhase.UpgradeRequired(it.seq, it.opId, it.unsupported, it.error) }
            val fromLast: SyncPhase? = when (last) {
                is CycleResult.UpgradeRequired -> SyncPhase.UpgradeRequired(last.seq, last.opId, last.unsupported, last.error)
                is CycleResult.Failed -> when (last.kind) {
                    FailureKind.OFFLINE -> SyncPhase.Offline
                    FailureKind.SERVER_ERROR, FailureKind.PROTOCOL -> SyncPhase.ServerUnreachable(last.httpStatus)
                    FailureKind.AUTH -> SyncPhase.AuthRequired
                    FailureKind.BAD_REQUEST, FailureKind.LOCAL_APPLY -> SyncPhase.ClientError(last.httpStatus)
                }
                else -> null
            }
            val phase = when {
                running -> SyncPhase.Syncing
                clockBlocked -> SyncPhase.ClockWrong(guard.takeIf { it != ClockGuardResult.OK }, skew)
                snapshot.rebuildPending -> SyncPhase.Rebuilding
                fromLast != null -> fromLast
                persisted != null -> persisted
                else -> SyncPhase.Idle
            }
            val lastError = when (last) {
                is CycleResult.Failed -> last.message
                is CycleResult.UpgradeRequired -> last.error
                else -> snapshot.upgradeRequired?.error
            }
            return SyncStatus(
                phase = phase,
                pendingCount = snapshot.pending,
                ackedCount = snapshot.acked,
                rejectedCount = snapshot.rejected,
                lastSyncAt = snapshot.lastSyncAt,
                lastError = lastError,
                clockWarning = skew != null && abs(skew) > CLOCK_WARN_MS,
                mintingBlocked = clockBlocked || snapshot.rebuildPending,
                refusedCacheCards = snapshot.refusedCacheCards,
            )
        }
    }
}

/**
 * Retry delay after failed cycles (SYNC.md 5.4: exponential, 5 s doubling to a cap of 15 min, reset on
 * success), with jitter. Pure: the caller (T303c's WorkManager wrapper) supplies the failure count and
 * a jitter value.
 */
public object SyncBackoff {
    /** First delay. */
    public const val BASE_MS: Long = 5_000L

    /** Cap. */
    public const val CAP_MS: Long = 900_000L

    /** Jitter removes up to this fraction of the delay (so the delay never exceeds the cap). */
    public const val JITTER_FRACTION: Double = 0.2

    /**
     * Delay before the next attempt after [consecutiveFailures] failed cycles (0 = no delay):
     * `d = min(CAP, BASE · 2^(n−1))`, result `d − floor(d · 0.2 · jitter)` for [jitter] in `[0, 1)`.
     */
    public fun delayMs(consecutiveFailures: Int, jitter: Double): Long {
        if (consecutiveFailures <= 0) return 0L
        require(jitter >= 0.0 && jitter < 1.0) { "jitter must be in [0, 1)" }
        var d = BASE_MS
        var i = 1
        while (i < consecutiveFailures && d < CAP_MS) {
            d *= 2
            i++
        }
        if (d > CAP_MS) d = CAP_MS
        return d - (d * JITTER_FRACTION * jitter).toLong()
    }

    /**
     * Whether [result] counts as a failure for the backoff. Offline, server, protocol, 400 and local
     * apply failures do; AUTH stops syncing (no retry until the user acts); successes, upgrade
     * required, log changes and rebuilds reset the count. A 413 never counts (handled inside push).
     */
    public fun isFailure(result: CycleResult): Boolean =
        result is CycleResult.Failed && result.kind != FailureKind.AUTH
}
