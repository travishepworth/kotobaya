package kotobaya.android.shell.api.device

import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import kotobaya.android.pairing.PairResult
import kotobaya.android.pairing.PairingTarget
import kotobaya.android.shell.api.AlwaysOpenGate
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.sync.SyncStatusView
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.protocol.SyncPhase
import kotobaya.sync.client.protocol.SyncStatus

/** A [DeviceOps] over plain fields; subclasses override what a test drives. */
open class FakeDeviceOps : DeviceOps {
    var paired = true
    var authRequired = false
    var online = true
    var pending = 0
    var phase: SyncPhase = SyncPhase.Idle
    var lastSyncAt: Long? = 1_795_168_800_000L
    var pullSeq = 48213L
    var failures = 0
    var unpairs = 0
    var syncsStarted = 0
    var pairs = 0

    override fun info() = ShellInfoData(
        shellVersion = "0.2.0", shellVersionCode = 29, paired = paired, authRequired = authRequired,
        serverUrl = if (paired) "https://kotobaya.example.net/api/v1" else null,
        deviceId = if (paired) "01931f3e-0000-7000-8000-0000000000d4" else null,
        deviceName = if (paired) "Pixel 9" else null,
        online = online, capabilities = CoreDeviceOps.CAPABILITIES, webVersion = "0.1.26", webSource = "bundled",
    )

    override fun syncStatus() = SyncStatusData(
        SyncStatusView(
            status = SyncStatus(
                phase = phase, pendingCount = pending, ackedCount = 0, rejectedCount = 2, lastSyncAt = lastSyncAt,
                lastError = null, clockWarning = false, mintingBlocked = false,
            ),
            paired = paired, staleOverTwoDays = false, upgradeRequired = null, consecutiveFailures = failures,
        ),
        online, pullSeq,
    )

    override suspend fun pair(target: PairingTarget, name: String?): PairResult {
        pairs++
        return PairResult.Paired("device", "server")
    }

    override fun unpair() {
        unpairs++
        paired = false
    }

    override fun startSync() {
        syncsStarted++
    }
}

internal class MapServices(private vararg val entries: Pair<KClass<*>, Any>) : ShellServices {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(type: KClass<T>): T =
        entries.firstOrNull { it.first == type }?.second as T? ?: error("no service ${type.simpleName}")
}

internal fun deviceApi(ops: DeviceOps, paired: () -> Boolean = { true }): LocalApi = LocalApi(
    routes = routes,
    gate = AlwaysOpenGate(paired = paired()),
    services = MapServices(DeviceOps::class to ops),
    newRequestId = { "req" },
)

private val JSON_HEADERS = mapOf("content-type" to "application/json")

internal fun LocalApi.post(path: String, body: String? = null): ShellResponse =
    runBlocking { handle(ShellRequest("POST", path, headers = if (body != null) JSON_HEADERS else emptyMap(), body = body)) }

internal fun LocalApi.get(path: String): ShellResponse = runBlocking { handle(ShellRequest("GET", path)) }

internal fun ShellResponse.json(): JsonObject =
    DefaultCanonicalJson.decode(body ?: error("no body (status $status)")).value as JsonObject

internal fun JsonObject.str(key: String): String? = (members[key] as? JsonString)?.value

internal fun JsonObject.bool(key: String): Boolean? = (members[key] as? JsonBool)?.value

internal fun JsonObject.at(key: String): JsonValue? = members[key]
