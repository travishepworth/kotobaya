package kotobaya.android.net

/** Typed failure of a server call. Never carries the token. */
sealed class ApiError(message: String) : Exception(message) {
    /** The phone has no network. */
    object Offline : ApiError("offline")

    /** Network is up but the server did not answer (DNS, refused, TLS, timeout). */
    class Unreachable(val reason: String) : ApiError("unreachable: $reason")

    /** 401 on an authenticated call. */
    class Unauthorized(val code: String?) : ApiError("401 ${code ?: ""}")

    /** 403: the token lacks a scope (or `device_required`). */
    class Forbidden(val code: String?) : ApiError("403 ${code ?: ""}")

    /** 401 `invalid_pairing_code`: unknown, expired or used; also after a server restart. */
    object InvalidPairingCode : ApiError("invalid_pairing_code")

    /** 422 with the first error pointer/code. */
    class Validation(val pointer: String?, val code: String?) : ApiError("422 ${pointer ?: ""} ${code ?: ""}") {
        val isKindConflict: Boolean get() = pointer == "/kind"
        val isHlcRegressed: Boolean get() = pointer == "/hlc_last" && code == "hlc_regressed"
    }

    class RateLimited(val retryAfterSeconds: Long?) : ApiError("429")

    class Server(val status: Int) : ApiError("server $status")

    /** Any other non-2xx. */
    class Other(val status: Int, val code: String?) : ApiError("http $status ${code ?: ""}")

    /** 2xx whose body could not be parsed. */
    class BadBody(val reason: String) : ApiError("bad body: $reason")
}
