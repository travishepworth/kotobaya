package kotobaya.android.push

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotobaya.android.net.ApiClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import java.io.IOException

/** Push registration state kept on the device. The endpoint URL is a secret-ish topic path: never logged. */
class PushPrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("kotobaya-push", Context.MODE_PRIVATE))

    /** The UnifiedPush endpoint the distributor gave us (an ntfy topic URL), null when push is off. */
    var endpoint: String?
        get() = prefs.getString(K_ENDPOINT, null)
        set(v) = prefs.edit().apply { if (v == null) remove(K_ENDPOINT) else putString(K_ENDPOINT, v) }.apply()

    /** True once the server has stored [endpoint] as this device's `push_topic`. */
    var registeredWithServer: Boolean
        get() = prefs.getBoolean(K_SERVER_OK, false)
        set(v) = prefs.edit().putBoolean(K_SERVER_OK, v).apply()

    private companion object {
        const val K_ENDPOINT = "endpoint"
        const val K_SERVER_OK = "server_ok"
    }
}

/** `PATCH /devices/self` with `{push_topic}`: how the phone hands its UnifiedPush endpoint to the server. */
class DevicePushApi(
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val http: OkHttpClient = ApiClient.defaultHttp(),
) {
    /** True on 2xx. Any failure (offline, unpaired, rejected) is false; the caller keeps the state to retry. */
    suspend fun setPushTopic(topic: String?): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl()?.trimEnd('/') ?: return@withContext false
        val bearer = token() ?: return@withContext false
        val body = JSONObject().put("push_topic", topic ?: JSONObject.NULL).toString()
        try {
            val request = Request.Builder().url("$base/devices/self")
                .header("Authorization", "Bearer $bearer")
                .header("Accept", "application/json")
                .patch(body.toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** What the connector can do on this phone. */
sealed class EnableResult {
    /** No UnifiedPush distributor app (for example ntfy) is installed. */
    data object NoDistributor : EnableResult()

    /** Registration was requested; the endpoint arrives later in the push service. */
    data object Requested : EnableResult()
}

/**
 * Connects the UnifiedPush distributor (the ntfy app) and tells the server the resulting endpoint. The distributor
 * creates the topic; the endpoint is stored as this device's `push_topic`, which the server forwards events to
 * (OpenAPI `/events`, "ntfy forwarding").
 */
class PushRegistrar(
    private val context: Context,
    private val prefs: PushPrefs,
    private val api: DevicePushApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    fun enable(): EnableResult {
        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.isEmpty()) return EnableResult.NoDistributor
        if (UnifiedPush.getSavedDistributor(context) == null) UnifiedPush.saveDistributor(context, distributors.first())
        UnifiedPush.register(context)
        return EnableResult.Requested
    }

    fun disable() {
        prefs.endpoint = null
        prefs.registeredWithServer = false
        runCatching { UnifiedPush.unregister(context) }
        runCatching { UnifiedPush.removeDistributor(context) }
        scope.launch { api.setPushTopic(null) }
    }

    /** The distributor gave us (or changed) the endpoint. */
    fun onEndpoint(url: String) {
        prefs.endpoint = url
        prefs.registeredWithServer = false
        retryServerRegistration()
    }

    /** The distributor dropped us: forget the endpoint locally and clear it on the server. */
    fun onUnregistered() {
        prefs.endpoint = null
        prefs.registeredWithServer = false
        scope.launch { api.setPushTopic(null) }
    }

    /** Pushes the stored endpoint to the server if that has not succeeded yet (also called from Settings and app open). */
    fun retryServerRegistration() {
        val endpoint = prefs.endpoint ?: return
        if (prefs.registeredWithServer) return
        scope.launch {
            if (api.setPushTopic(endpoint)) prefs.registeredWithServer = true
        }
    }
}
