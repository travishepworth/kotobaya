package kotobaya.android.sync

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.net.ApiClient
import kotobaya.android.net.MetaLimitsStore
import kotobaya.sync.client.protocol.SyncLimits
import kotobaya.sync.client.protocol.SyncTransport
import kotobaya.sync.client.protocol.TransportResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * T303a's [SyncTransport] over OkHttp, with the same base URL, token and timeouts as T301b's
 * [ApiClient]. Push and pull bodies travel as text: the push body is sent byte-for-byte as the engine
 * built it from canonical op texts (SYNC.md 5.2; `ApiClient` re-serializes through `org.json`, which
 * would escape `/` and change number formatting), and the pull page is parsed by the core.
 *
 * Never throws for HTTP or network failures: they become [TransportResponse.HttpError] /
 * [TransportResponse.NetworkError]. The log identity (`log_epoch`, `high_water_seq`, `durable_seq`,
 * `server_time`) is in the push/pull bodies and handled by the engine; `Kotobaya-Seq` belongs to the
 * REST mutation endpoints, not to the sync endpoints, so nothing is read from headers here.
 *
 * [limits] returns the two `/meta` limits the engine needs (see [storedLimits]).
 */
class HttpSyncTransport(
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val metaLimits: suspend () -> SyncLimits?,
    private val http: OkHttpClient = ApiClient.defaultHttp(),
) : SyncTransport {
    override suspend fun push(body: String): TransportResponse = call("/sync/push", body)

    override suspend fun pull(since: Long): TransportResponse = call("/sync/pull?since=$since", null)

    override suspend fun limits(): SyncLimits? = metaLimits()

    private suspend fun call(path: String, body: String?): TransportResponse = withContext(Dispatchers.IO) {
        val base = baseUrl()?.trimEnd('/') ?: return@withContext TransportResponse.NetworkError("no server configured")
        val builder = try {
            Request.Builder().url(base + path)
        } catch (e: IllegalArgumentException) {
            return@withContext TransportResponse.NetworkError("invalid server url")
        }
        token()?.let { builder.header("Authorization", "Bearer $it") }
        builder.header("Accept", "application/json")
        if (body != null) builder.post(body.toRequestBody(JSON)) else builder.get()
        try {
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    TransportResponse.Ok(text)
                } else {
                    val err = ApiClient.mapError(resp.code, text, resp.header("Retry-After")?.toLongOrNull())
                    TransportResponse.HttpError(resp.code, err.message ?: "http ${resp.code}")
                }
            }
        } catch (e: IOException) {
            TransportResponse.NetworkError(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * The `limits` reader over T301b's [MetaLimitsStore]: the durable `meta.*` values, or null
         * when `/meta` was never read. The coordinator's reachability probe ([kotobaya.android.net.ServerReachability])
         * refreshes them with one `GET /meta` right before every cycle, so the cycle does not fetch it twice.
         */
        fun storedLimits(store: MetaLimitsStore): suspend () -> SyncLimits? = {
            if (store.hasFetched()) store.current().let { SyncLimits(maxOpBytes = it.maxOpBytes, maxPushBytes = it.maxPushBytes) } else null
        }
    }
}
