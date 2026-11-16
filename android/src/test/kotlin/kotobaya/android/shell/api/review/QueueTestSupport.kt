package kotobaya.android.shell.api.review

import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row

/** Helpers of the SHELL-L2 tests: a [ParityDevice] with every registered route, seeded from a scenario. */
internal object QueueTestSupport {
    const val DEVICE = "01931f3e-0000-7000-8000-0000000000d4"
    const val SERVER_DEVICE = "00000000-0000-7000-8000-000000000001"

    fun device(scenario: String): ParityDevice {
        val s = ParityScenario.load(scenario)
        return ParityDevice(s.deviceId, s.clockMs, LocalRoutes.all).also { it.pull(s.seedLog, "seed") }
    }

    fun ParityDevice.queue(query: String = "", at: Long? = null): ShellResponse {
        at?.let { clock.now = it }
        return request("GET", "/api/v1/review/queue", query, null)
    }

    fun ShellResponse.json(): JsonObject = ParityJson.obj(ParityJson.parse(body ?: error("no body (status $status)")))

    fun JsonObject.at(pointer: String): JsonValue? = ParityJson.at(this, pointer)

    fun JsonObject.long(pointer: String): Long = ParityJson.long(at(pointer))

    fun JsonObject.items(): List<JsonObject> = ParityJson.arr(at("/items")).map(ParityJson::obj)

    /** `errors[0]` of a problem body as `pointer code`. */
    fun JsonObject.firstError(): String {
        val e = ParityJson.obj(ParityJson.arr(at("/errors")).first())
        return "${ParityJson.str(e.members["pointer"])} ${ParityJson.str(e.members["code"])}"
    }

    fun ParityDevice.notes(): List<Row> = store.read { it.learning.all(LearningTable.NOTE) }

    /** Live notes (not deleted, not merged away) holding [entrySeq]. */
    fun ParityDevice.liveNotesOf(entrySeq: Long): List<Row> = notes().filter { r ->
        (r["entry_seq"] as? JsonNumber)?.value?.toLong() == entrySeq && (r["deleted_at"] ?: JsonNull) == JsonNull && (r["merged_into_id"] ?: JsonNull) == JsonNull
    }

    fun ParityDevice.cardsOf(noteId: String): List<Row> = store.read { it.learning.where(LearningTable.CARD, "note_id", JsonString(noteId)) }

    fun str(v: JsonValue?): String = ParityJson.str(v)

    /** An op of another replica in pushed form (`hlc` from [wallMs]); [SERVER_DEVICE] by default. */
    fun op(opId: String, type: String, entityType: String, entityId: String, wallMs: Long, payload: JsonObject, device: String = SERVER_DEVICE): JsonObject =
        JsonObject(
            linkedMapOf(
                "op_id" to JsonString(opId),
                "op_type" to JsonString(type),
                "op_version" to JsonNumber(1.0),
                "entity_type" to JsonString(entityType),
                "entity_id" to JsonString(entityId),
                "device_id" to JsonString(device),
                "hlc" to JsonString("%012x-0000-%s".format(wallMs, device)),
                "payload" to payload,
            ),
        )

    fun obj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*members))

    fun arr(vararg items: JsonValue): JsonArray = JsonArray(items.toList())
}
