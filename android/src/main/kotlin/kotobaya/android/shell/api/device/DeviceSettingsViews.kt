package kotobaya.android.shell.api.device

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue

private fun n(v: Long): JsonValue = JsonNumber(v.toDouble())

private fun nullable(v: String?): JsonValue = if (v == null) JsonNull else JsonString(v)

/** The JSON of `ShellDictInstall`, also the payload of `shell.dict`. */
fun DictInstallData.toJson(): JsonObject {
    val members = linkedMapOf<String, JsonValue>(
        "installed" to if (installedReleaseId == null) {
            JsonNull
        } else {
            obj("release_id" to n(installedReleaseId), "scope" to JsonString(installedScope ?: "other"), "bytes" to n(installedBytes))
        },
        "scope" to JsonString(scope),
        "state" to JsonString(state),
        "done_bytes" to n(doneBytes),
        "total_bytes" to n(totalBytes),
        "last_result" to nullable(lastResult),
        "last_error" to nullable(lastError),
        "server_release_id" to (serverReleaseId?.let(::n) ?: JsonNull),
    )
    gapFill?.let { members["gap_fill"] = obj("cached" to n(it.cached.toLong()), "missing" to n(it.missing.toLong())) }
    return JsonObject(members)
}

/** The JSON of `ShellStorage`. */
fun StorageData.toJson(): JsonObject = obj(
    "card_audio_bytes" to n(breakdown.cardAudioBytes),
    "card_audio_count" to n(breakdown.cardAudioCount.toLong()),
    "media_bytes" to n(breakdown.mediaBytes),
    "cover_bytes" to n(breakdown.coverBytes),
    "dictionary_bytes" to n(breakdown.dictionaryBytes),
    "total_bytes" to n(breakdown.totalBytes),
    "prefetch_days" to n(prefetchDays.toLong()),
    "budget_mb" to n(budgetMb.toLong()),
    "allow_metered" to JsonBool(allowMetered),
)

/** The JSON of `ShellNotificationState`. */
fun NotifyData.toJson(): JsonObject = obj(
    "permission" to JsonString(permission),
    "can_request" to JsonBool(canRequest),
    "nudges_enabled" to JsonBool(nudgesEnabled),
    "nudge_threshold" to n(nudgeThreshold.toLong()),
    "evening_hour" to (eveningHour?.let { n(it.toLong()) } ?: JsonNull),
)

/** The JSON of `ShellPushState`. */
fun PushData.toJson(): JsonObject = obj("state" to JsonString(state), "has_endpoint" to JsonBool(hasEndpoint))

/** The JSON of `ShellAnkiConnectState`. */
fun AnkiConnectData.toJson(): JsonObject = obj(
    "enabled" to JsonBool(enabled),
    "port" to n(port.toLong()),
    "api_key" to JsonString(apiKey),
    "origins" to JsonArray(origins.map(::JsonString)),
    "status" to JsonString(status),
    "status_message" to nullable(statusMessage),
)
