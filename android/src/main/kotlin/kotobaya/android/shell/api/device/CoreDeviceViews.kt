package kotobaya.android.shell.api.device

import kotobaya.android.shell.web.BridgeCore
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.protocol.SyncPhase

/** `ShellInfo` (shell.yaml) before it is encoded. Never carries the token. */
data class ShellInfoData(
    val shellVersion: String,
    val shellVersionCode: Int,
    val paired: Boolean,
    val authRequired: Boolean,
    val serverUrl: String?,
    val deviceId: String?,
    val deviceName: String?,
    val online: Boolean,
    val capabilities: List<String>,
    val webVersion: String,
    val webSource: String,
)

/** The members of `ShellSyncStatus` that are not in `SyncStatusView`: the network and the pull cursor. */
data class SyncStatusData(
    val view: kotobaya.android.sync.SyncStatusView,
    val online: Boolean,
    val pullSeq: Long,
)

internal fun obj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*members))

private fun n(v: Long): JsonValue = JsonNumber(v.toDouble())

private fun nullable(v: String?): JsonValue = if (v == null) JsonNull else JsonString(v)

private const val MAX_LAST_ERROR = 500

/** The JSON of `ShellInfo`. */
fun ShellInfoData.toJson(): JsonObject = obj(
    "shell_version" to JsonString(shellVersion),
    "shell_version_code" to n(shellVersionCode.toLong()),
    "local_api_version" to n(kotobaya.android.shell.api.LocalApi.LOCAL_API_VERSION.toLong()),
    "paired" to JsonBool(paired),
    "auth_required" to JsonBool(authRequired),
    "server_url" to nullable(serverUrl),
    "device_id" to nullable(deviceId),
    "device_name" to nullable(deviceName),
    "online" to JsonBool(online),
    "capabilities" to JsonArray(capabilities.map(::JsonString)),
    "web_version" to JsonString(webVersion),
    "web_source" to JsonString(webSource),
)

/** `ShellSyncStatus.phase` for [SyncPhase] (the network wins over a stale "idle" / "unreachable"). */
internal fun phaseName(phase: SyncPhase, online: Boolean): String = when (phase) {
    SyncPhase.Idle -> if (online) "idle" else "offline"
    SyncPhase.Syncing -> "syncing"
    SyncPhase.Offline -> "offline"
    is SyncPhase.ServerUnreachable -> if (online) "unreachable" else "offline"
    SyncPhase.AuthRequired -> "auth_required"
    is SyncPhase.ClientError -> "error"
    is SyncPhase.UpgradeRequired -> "upgrade_required"
    is SyncPhase.ClockWrong -> "clock_wrong"
    SyncPhase.Rebuilding -> "rebuilding"
}

/**
 * The JSON of `ShellSyncStatus`, also the payload of `shell.sync`. [forceSyncing] reports a cycle just started
 * by `syncNow` as `syncing` when the status has not caught up yet (only over phases a new cycle supersedes).
 */
fun SyncStatusData.toJson(forceSyncing: Boolean = false): JsonObject {
    val s = view.status
    var phase = phaseName(s.phase, online)
    if (forceSyncing && phase in SUPERSEDED_BY_SYNCING) phase = "syncing"
    val progress = view.progress
    return obj(
        "phase" to JsonString(phase),
        "paired" to JsonBool(view.paired),
        "online" to JsonBool(online),
        "pending_ops" to n(s.pendingCount.toLong()),
        "rejected_ops" to n(s.rejectedCount.toLong()),
        "last_sync_at" to (s.lastSyncAt?.let { JsonString(BridgeCore.formatAt(it)) } ?: JsonNull),
        "stale" to JsonBool(view.staleOverTwoDays || (view.paired && s.lastSyncAt == null)),
        "pull_seq" to n(pullSeq),
        "clock_warning" to JsonBool(s.clockWarning),
        "minting_blocked" to JsonBool(s.mintingBlocked),
        "progress" to if (progress == null) JsonNull else obj("done" to n(progress.done), "total" to n(maxOf(progress.total, progress.done))),
        "last_error" to nullable(s.lastError?.take(MAX_LAST_ERROR)),
        "consecutive_failures" to n(view.consecutiveFailures.toLong()),
    )
}

private val SUPERSEDED_BY_SYNCING = setOf("idle", "unreachable", "error")
