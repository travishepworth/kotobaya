package kotobaya.android.shell.api.confusables

import java.util.concurrent.atomic.AtomicLong
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonObject

/**
 * Ops of another replica in pushed form (what this device receives by pull), for the LF-A8b tests: seeds the device
 * cannot mint itself (`media.created`, `note.merged`) and remote ops interleaved with the device's own (SYNC.md 7.3).
 */
internal object RemoteOps {
    const val SERVER = "00000000-0000-7000-8000-000000000001"
    const val OTHER = "01931f3e-0000-7000-8000-0000000000a1"

    private val ids = AtomicLong(0x0a8b_0000_0000L)

    fun op(type: String, entityType: String, entityId: String, payload: String, atMs: Long, device: String = OTHER, counter: Int = 0): JsonObject {
        val opId = "0193a1b2-00ff-7a8b-8000-%012x".format(ids.incrementAndGet())
        val hlc = "%012x-%04x-%s".format(atMs, counter, device)
        return ParityJson.obj(
            ParityJson.parse(
                """{"op_id":"$opId","op_type":"$type","op_version":1,"entity_type":"$entityType","entity_id":"$entityId",""" +
                    """"device_id":"$device","hlc":"$hlc","payload":$payload}""",
            ),
        )
    }
}
