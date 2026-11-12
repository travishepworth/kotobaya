package kotobaya.sync.client.protocol

import kotobaya.sync.client.store.SyncBookkeeping

/** What came back from one HTTP call (or did not). */
public sealed interface TransportResponse {
    /** HTTP 2xx with this body text. */
    public data class Ok(
        /** The response body. */
        public val body: String,
    ) : TransportResponse

    /** Any non-2xx status (413, 400, 401, 403, 429, 5xx, ...). */
    public data class HttpError(
        /** The HTTP status code. */
        public val status: Int,
        /** Diagnostic (e.g. the problem body's `title`/`detail`); never parsed. */
        public val message: String,
    ) : TransportResponse

    /** No response at all: offline, DNS, TLS, timeout, connection reset. */
    public data class NetworkError(
        /** Diagnostic. */
        public val message: String,
    ) : TransportResponse
}

/**
 * The sync endpoints (T004): `POST /sync/push`, `GET /sync/pull`, `GET /meta`. Implemented over the
 * HTTP client by T303c and in memory by the tests. Bodies are passed as text so the engine itself
 * builds the push body from canonical op texts (SYNC.md 5.2) and parses pull pages with the core's
 * own parser (depth 512, ECMA patterns: SYNC.md 2.1, 6.1). Implementations never throw for HTTP or
 * network failures: they return [TransportResponse.HttpError] / [TransportResponse.NetworkError].
 */
public interface SyncTransport {
    /** `POST /sync/push` with [body] (a `PushRequest`). */
    public suspend fun push(body: String): TransportResponse

    /** `GET /sync/pull?since=[since]` (server default page size). */
    public suspend fun pull(since: Long): TransportResponse

    /** `limits` of `GET /meta`, or null when it could not be read (the stored limits stay in use). */
    public suspend fun limits(): SyncLimits?
}

/** The `/meta` limits the client needs (SYNC.md 5.4, 6.1; openapi `getMeta`). */
public data class SyncLimits(
    /** `limits.max_op_bytes`: no op larger than this is minted (SYNC.md 4). */
    public val maxOpBytes: Long = DEFAULT_MAX_OP_BYTES,
    /** `limits.max_push_bytes`: push bodies are batched to stay within it. */
    public val maxPushBytes: Long = DEFAULT_MAX_PUSH_BYTES,
) {
    /** Defaults and fixed limits. */
    public companion object {
        /** Default `max_op_bytes` (1 MiB), used until `/meta` was read. */
        public const val DEFAULT_MAX_OP_BYTES: Long = 1_048_576L

        /** Default `max_push_bytes` (8 MiB). */
        public const val DEFAULT_MAX_PUSH_BYTES: Long = 8_388_608L

        /** `max_push_ops` (fixed by `PushRequest.ops.maxItems`). */
        public const val MAX_PUSH_OPS: Int = 500

        /** The defaults. */
        public val DEFAULT: SyncLimits = SyncLimits()

        /** The limits stored durably in `sync_state` (`meta.*`), defaults where absent. */
        public fun read(bk: SyncBookkeeping): SyncLimits =
            SyncLimits(bk.maxOpBytes ?: DEFAULT_MAX_OP_BYTES, bk.maxPushBytes ?: DEFAULT_MAX_PUSH_BYTES)

        /** Stores [limits] durably (`meta.*` keys). */
        public fun write(bk: SyncBookkeeping, limits: SyncLimits) {
            bk.maxOpBytes = limits.maxOpBytes
            bk.maxPushBytes = limits.maxPushBytes
        }
    }
}
