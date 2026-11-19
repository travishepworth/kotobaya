package kotobaya.android.shell.api.actions

import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.actions.routes as actionRoutes
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.routes as reviewRoutes
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row

/**
 * Helpers of the SHELL-L4 tests: [ParityDevice]s over the actions and review routes, seeded with the scenario
 * `answer-retry-same-id` (device A created two vocab notes, 1001010 and 1001150, each with a `meaning` card, one hour
 * before [T0]).
 */
internal object ActionsTestSupport {
    const val T0 = 1_795_168_800_000L
    const val X = "01931f3e-0000-7000-8000-0000000000d4"
    const val Y = "01931f3e-0000-7000-8000-0000000000d5"
    const val Z = "01931f3e-0000-7000-8000-0000000000d6"
    const val B = "01931f3e-0000-7000-8000-0000000000b2"
    const val N1 = "0193a1b2-0001-7000-8000-000000000001"
    const val N2 = "0193a1b2-0001-7000-8000-000000000002"
    const val C1 = "0193a1b2-0002-7000-8000-000000000010"
    const val C2 = "0193a1b2-0002-7000-8000-000000000020"
    const val SEQ1 = 1001010L
    const val UNKNOWN = "0193a1b2-0002-7000-8000-0000000000ff"
    const val HOUR = 3_600_000L

    fun seed(): List<JsonObject> = ParityScenario.load("answer-retry-same-id").seedLog

    fun device(id: String = X, seed: List<JsonObject> = seed(), startMs: Long = T0): ParityDevice {
        Class.forName("org.sqlite.JDBC") // DriverManager may not have loaded it yet when a test runs alone
        return ParityDevice(id, startMs, actionRoutes + reviewRoutes).also { if (seed.isNotEmpty()) it.pull(seed, "seed") }
    }

    fun ParityDevice.call(method: String, path: String, body: String? = null, query: String = "", at: Long? = null): ShellResponse {
        at?.let { clock.now = it }
        return request(method, path, query, body?.let { ParityJson.parse(it) })
    }

    fun ParityDevice.bulk(body: String, at: Long? = null): ShellResponse = call("POST", "/api/v1/cards/bulk", body, at = at)

    fun ParityDevice.patchNote(id: String, body: String, at: Long? = null): ShellResponse = call("PATCH", "/api/v1/notes/$id", body, at = at)

    fun ShellResponse.json(): JsonObject = ParityJson.obj(ParityJson.parse(body ?: error("no body (status $status)")))

    /** `status pointer code` of a problem (`errors[0]`), or `status code` when it has no `errors`. */
    fun ShellResponse.problem(): String {
        val b = json()
        val first = (b.members["errors"] as? JsonArray)?.items?.firstOrNull()?.let(ParityJson::obj)
        return if (first != null) {
            "$status ${ParityJson.str(first.members["pointer"])} ${ParityJson.str(first.members["code"])}"
        } else {
            "$status ${ParityJson.str(b.members["code"])}"
        }
    }

    fun ParityDevice.rows(table: LearningTable): List<Row> =
        store.read { it.learning.all(table) }.sortedBy { r -> table.keyColumns.joinToString("|") { ParityJson.show(r[it]) } }

    fun ParityDevice.card(id: String): Row = rows(LearningTable.CARD).single { it["id"] == JsonString(id) }

    fun ParityDevice.note(id: String): Row = rows(LearningTable.NOTE).single { it["id"] == JsonString(id) }

    fun ParityDevice.mintedTypes(): List<String> = minted().map { ParityJson.str(it.members["op_type"]) }

    fun iso(ms: Long): String = kotobaya.android.shell.api.identity.timestamp(ms)

    fun hlc(wallMs: Long, device: String, counter: Int = 0): String = "%012x-%04x-%s".format(wallMs, counter, device)

    /** An op of another replica ([device]) in pushed form; [n] makes its `op_id` unique within a test. */
    fun remote(n: Int, type: String, entityType: String, entityId: String, wallMs: Long, payload: JsonObject, device: String = B): JsonObject =
        JsonObject(
            linkedMapOf(
                "op_id" to JsonString("0193c000-00ff-7000-8000-%012x".format(n)),
                "op_type" to JsonString(type),
                "op_version" to JsonNumber(1.0),
                "entity_type" to JsonString(entityType),
                "entity_id" to JsonString(entityId),
                "device_id" to JsonString(device),
                "hlc" to JsonString(hlc(wallMs, device)),
                "payload" to payload,
            ),
        )

    fun obj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*members))

    /** `fields.<key>` of a note row. */
    fun Row.field(key: String): String? = ((this["fields"] as? JsonObject)?.members?.get(key) as? JsonString)?.value

    /**
     * A replica (device [id], seeded like the others) that receives [ops] one at a time in the order [order] gives:
     * the out-of-order oracle of SHELL.md 8.4 (c).
     */
    fun replica(id: String, ops: List<JsonObject>, at: Long, order: (List<JsonObject>) -> List<JsonObject>): ParityDevice {
        val r = device(id)
        r.clock.now = at
        order(ops).forEach { r.pull(listOf(it), "oracle") }
        return r
    }
}
