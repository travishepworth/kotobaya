package kotobaya.android.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.report.ClientErrors
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/** A successful JSON response. */
class ApiResponse(val status: Int, val body: JSONObject)

/**
 * Minimal JSON/HTTP client over OkHttp (system trust store, bounded timeouts).
 * Base URL and token are read per call so a re-pair or unpair takes effect immediately.
 * Public API only (ADR-002). Errors are [ApiError]s; the token is never logged.
 */
class ApiClient(
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val isOnline: () -> Boolean = { true },
    private val http: OkHttpClient = defaultHttp(),
) {
    suspend fun get(path: String): ApiResponse = withContext(Dispatchers.IO) { call("GET", path, null, auth = true) }

    suspend fun post(path: String, body: JSONObject, auth: Boolean): ApiResponse =
        withContext(Dispatchers.IO) { call("POST", path, body, auth) }

    private fun call(method: String, path: String, body: JSONObject?, auth: Boolean): ApiResponse {
        val base = baseUrl()?.trimEnd('/') ?: throw ApiError.Unreachable("no server configured")
        if (!isOnline()) throw ApiError.Offline
        val builder = try {
            Request.Builder().url(base + path)
        } catch (e: IllegalArgumentException) {
            throw ApiError.Unreachable("invalid server url")
        }
        if (auth) token()?.let { builder.header("Authorization", "Bearer $it") }
        builder.header("Accept", "application/json")
        if (body != null) builder.method(method, body.toString().toRequestBody(JSON)) else builder.method(method, null)
        try {
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val json = try {
                        if (text.isBlank()) JSONObject() else JSONObject(text)
                    } catch (e: JSONException) {
                        throw ApiError.BadBody(e.message ?: "json")
                    }
                    return ApiResponse(resp.code, json)
                }
                // OBS-1c-and: 5xx is reported (handled 4xx are not); /client-errors itself is skipped inside
                if (resp.code >= 500) ClientErrors.apiFailure(method, path, resp.code, resp.header("X-Request-Id"))
                throw mapError(resp.code, text, resp.header("Retry-After")?.toLongOrNull())
            }
        } catch (e: IOException) {
            if (isOnline()) ClientErrors.apiFailure(method, path, null, null)
            throw if (isOnline()) ApiError.Unreachable(e.javaClass.simpleName) else ApiError.Offline
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Maps an RFC 9457 problem response to a typed error. */
        fun mapError(status: Int, text: String, retryAfter: Long?): ApiError {
            val problem = try { JSONObject(text) } catch (_: JSONException) { JSONObject() }
            val code = problem.optString("code").ifEmpty { null }
            return when {
                status == 401 && code == "invalid_pairing_code" -> ApiError.InvalidPairingCode
                status == 401 -> ApiError.Unauthorized(code)
                status == 403 -> ApiError.Forbidden(code)
                status == 422 -> {
                    val first = problem.optJSONArray("errors")?.optJSONObject(0)
                    ApiError.Validation(
                        first?.optString("pointer")?.ifEmpty { null },
                        first?.optString("code")?.ifEmpty { null },
                    )
                }
                status == 429 -> ApiError.RateLimited(retryAfter)
                status >= 500 -> ApiError.Server(status)
                else -> ApiError.Other(status, code)
            }
        }
    }
}
