package kotobaya.android.shell.api.identity

import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.identity.routes as identityRoutes
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.store.PendingState

/** Helpers of the SHELL-L1 tests: a [ParityDevice] over the identity routes, and the device's own ops in pushed form. */
internal object IdentityTestSupport {
    const val DEVICE = "01931f3e-0000-7000-8000-0000000000d4"
    const val OTHER_DEVICE = "01931f3e-0000-7000-8000-0000000000d5"
    const val START_MS = 1_795_168_800_000L

    fun device(id: String = DEVICE, seed: List<JsonObject> = emptyList()): ParityDevice =
        ParityDevice(id, START_MS, identityRoutes).also { if (seed.isNotEmpty()) it.pull(seed, "seed") }

    fun seedOf(scenario: String): List<JsonObject> = ParityScenario.load(scenario).seedLog

    fun ParityDevice.call(method: String, path: String, body: String? = null, query: String = "", at: Long? = null): ShellResponse {
        at?.let { clock.now = it }
        return request(method, path, query, body?.let { ParityJson.parse(it) })
    }

    fun ShellResponse.json(): JsonValue = ParityJson.parse(body ?: error("no body (status $status)"))

    /** The ops the device minted, in pushed form (what another device receives by pull). */
    fun ParityDevice.minted(): List<JsonObject> = store.read { tx ->
        tx.outbox.inState(PendingState.PENDING).map { r ->
            JsonObject(
                linkedMapOf(
                    "op_id" to JsonString(r.opId),
                    "op_type" to JsonString(r.opType),
                    "op_version" to kotobaya.contracts.sync.JsonNumber(r.opVersion.toDouble()),
                    "entity_type" to JsonString(r.entityType),
                    "entity_id" to JsonString(r.entityId),
                    "device_id" to JsonString(r.hlc.takeLast(36)),
                    "hlc" to JsonString(r.hlc),
                    "payload" to ParityJson.parse(r.payload),
                ),
            )
        }
    }

    /** `op.schema.json` + `ops/<type>.schema.json` validation of a pushed op. */
    fun isValidOp(op: JsonObject): Boolean = DefaultOpCodec.decodeTree(op, OpSource.PUSHED) is OpDecodeResult.Known

    fun ParityDevice.settingRows(): Map<String, JsonValue> =
        store.read { it.learning.all(LearningTable.SETTINGS) }.associate { ParityJson.str(it["key"]) to (it["value"] ?: kotobaya.contracts.sync.JsonNull) }
}
