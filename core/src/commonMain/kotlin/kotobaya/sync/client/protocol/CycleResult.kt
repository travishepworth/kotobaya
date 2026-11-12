package kotobaya.sync.client.protocol

import kotobaya.contracts.Uuid

/** The step of a sync cycle a result comes from. */
public enum class SyncStep {
    /** `POST /sync/push`. */
    PUSH,

    /** `GET /sync/pull`. */
    PULL,
}

/** Why a step failed. Rows stay `pending` in every case; nothing is lost. */
public enum class FailureKind {
    /** No response (offline, timeout). Retry with backoff (SYNC.md 5.4). */
    OFFLINE,

    /** 5xx, 429 or another unexpected status. Retry with backoff. */
    SERVER_ERROR,

    /** HTTP 400 for the whole push: a client bug; shown in the status, retried with backoff (5.4). */
    BAD_REQUEST,

    /** 401/403: stop syncing and ask the user to re-pair / log in (5.4). */
    AUTH,

    /** A 2xx body the client cannot use (not JSON, wrong shape, results missing, seqs out of order). */
    PROTOCOL,

    /** The local [kotobaya.sync.client.store.OpApplier] threw on a pulled op; the page was not committed. */
    LOCAL_APPLY,
}

/** Outcome of [SyncEngine.cycle] (and of its steps [SyncEngine.pushAll] / [SyncEngine.pullAll]). */
public sealed interface CycleResult {
    /** The step(s) ran to the end. For a full cycle: pushed, pulled, purged, `last_sync_at` set. */
    public data class Completed(
        /** Ops that got a server result (acked or rejected) or a local `op_too_large`. */
        public val pushedOps: Int,
        /** Pulled ops applied or skipped (`device_applies = false`), own ops included. */
        public val pulledOps: Int,
        /** `acked` rows deleted by the purge rule (SYNC.md 5.4). */
        public val purgedRows: Int,
        /**
         * The pull raised `pull_seq` (new ops arrived). After a log reset, T303b's `unknown_reference`
         * auto-retry runs at the end of each cycle where this is true (SYNC.md 6.2).
         */
        public val pullAdvanced: Boolean = false,
    ) : CycleResult

    /**
     * The outbox is pushed and `rebuild_pending` is set: the rebuild (SYNC.md 10.3, T303b) must run
     * before any pull. Nothing was pulled.
     */
    public data class RebuildPending(
        /** Ops pushed in this cycle. */
        public val pushedOps: Int,
    ) : CycleResult

    /**
     * A response showed a different `log_epoch`, or `high_water_seq < pull_seq` (SYNC.md 6.2). Push
     * results were still recorded (they are valid in the new log); a pull page was discarded. The
     * stored `log_epoch`, `durable_seq` and `pull_seq` are unchanged: the log reset (T303b) stores
     * [newEpoch] / [durableSeq] in its own transaction.
     */
    public data class LogChanged(
        /** Which response showed it. */
        public val step: SyncStep,
        /** `sync_state.log_epoch` when it was detected. */
        public val storedEpoch: Uuid,
        /** `sync_state.pull_seq` when it was detected. */
        public val storedPullSeq: Long,
        /** The response's `log_epoch`. */
        public val newEpoch: Uuid,
        /** The response's `high_water_seq`. */
        public val highWaterSeq: Long,
        /** The response's `durable_seq`. */
        public val durableSeq: Long,
    ) : CycleResult

    /**
     * The pull stopped at an op this client does not support or that fails its schema (SYNC.md 6.1).
     * Ops before it were applied and `pull_seq` is the `seq` before it; the op is never skipped. The
     * next cycle retries at the same op. Pushing and the purge still ran.
     */
    public data class UpgradeRequired(
        /** `seq` of the op, if readable. */
        public val seq: Long?,
        /** `op_id` of the op, if readable. */
        public val opId: Uuid?,
        /** `op_type` as received, if readable. */
        public val opType: String?,
        /** `op_version` as received, if readable. */
        public val opVersion: Int?,
        /** `true`: unknown type/version; `false`: a supported type that fails its schema. */
        public val unsupported: Boolean,
        /** The first validation error (diagnostic, English). */
        public val error: String,
    ) : CycleResult

    /** A step failed; see [kind]. Rows that were being pushed stay `pending` (`attempts + 1` unless [kind] is AUTH). */
    public data class Failed(
        /** Category. */
        public val kind: FailureKind,
        /** Which step. */
        public val step: SyncStep,
        /** Diagnostic (English). */
        public val message: String,
        /** HTTP status, when there was one. */
        public val httpStatus: Int? = null,
    ) : CycleResult
}
