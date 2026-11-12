package kotobaya.android.pairing

import java.security.SecureRandom
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import kotobaya.android.settings.SettingsStore
import kotobaya.db.app.AppDatabase
import org.json.JSONObject

/** Result of a pairing attempt; failures are typed [ApiError]s. */
sealed class PairResult {
    data class Paired(val deviceId: String, val serverDeviceId: String) : PairResult()

    data class Failed(val error: ApiError) : PairResult()
}

/**
 * Pairs this install with the server and owns the device identity (`sync_state.device_id`),
 * the token slot and the base URL. Unpairing clears only the token: local data, the outbox
 * and the device id are kept so the same intact install can re-pair.
 */
class PairingRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val deviceName: String,
    private val clientFor: (baseUrl: String) -> ApiClient,
    private val newDeviceId: () -> String = ::uuidV7,
) {
    private val q get() = db.syncOpsQueries

    val deviceId: String? get() = q.getSyncState(KEY_DEVICE_ID).executeAsOneOrNull()
    val isPaired: Boolean get() = tokens.token != null && settings.serverBaseUrl != null
    val serverBaseUrl: String? get() = settings.serverBaseUrl

    suspend fun pair(target: PairingTarget): PairResult {
        val client = clientFor(target.serverBaseUrl)
        var id = deviceId ?: newDeviceId().also { q.putSyncState(KEY_DEVICE_ID, it) }
        var regressedOnce = false
        while (true) {
            val body = JSONObject()
                .put("code", target.code)
                .put("device_id", id)
                .put("name", deviceName)
                .put("kind", "android")
            q.getSyncState(KEY_HLC_LAST).executeAsOneOrNull()?.let { body.put("hlc_last", it) }
            try {
                val resp = client.post("/devices/pair", body, auth = false).body
                val token = resp.optString("token")
                if (token.isEmpty()) return PairResult.Failed(ApiError.BadBody("token missing"))
                tokens.token = token
                settings.deviceToken = null // never keep a token in the plain prefs
                settings.serverBaseUrl = target.serverBaseUrl
                q.putSyncState(KEY_DEVICE_ID, id)
                return PairResult.Paired(id, resp.optString("server_device_id"))
            } catch (e: ApiError.Validation) {
                // Local DB went backwards: mint a fresh device id and pair again with the same
                // code (the server rolled back, the code is not consumed). Resetting replica
                // state for the new id is the sync layer's job (T303).
                if (e.isHlcRegressed && !regressedOnce) {
                    regressedOnce = true
                    id = newDeviceId()
                    q.putSyncState(KEY_DEVICE_ID, id)
                    db.metaLimitsQueries.deleteSyncState(KEY_HLC_LAST)
                    continue
                }
                return PairResult.Failed(e)
            } catch (e: ApiError) {
                return PairResult.Failed(e)
            }
        }
    }

    /** Clears the token only. Local data, the outbox and the device id stay. */
    fun unpair() {
        tokens.token = null
    }

    companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_HLC_LAST = "hlc_last"
    }
}

/** A fresh UUIDv7 in canonical lowercase text. */
fun uuidV7(now: Long = System.currentTimeMillis(), random: SecureRandom = SecureRandom()): String {
    val b = ByteArray(16)
    random.nextBytes(b)
    for (i in 0..5) b[i] = (now shr (8 * (5 - i))).toByte()
    b[6] = ((b[6].toInt() and 0x0f) or 0x70).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = b.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
