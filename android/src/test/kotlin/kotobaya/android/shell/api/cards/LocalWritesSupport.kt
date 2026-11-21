package kotobaya.android.shell.api.cards

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.identity.IdentityTestSupport.isValidOp
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityRunner
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row

/**
 * Helpers of the LF-A8 tests: [ParityDevice]s over every local route ([LocalRoutes.all]), the parity scenarios run with
 * an upstream that is a finding when called, and the op checks of SHELL.md 8.4 (schema, the device's own, out-of-order
 * convergence on fresh replicas).
 */
internal object LocalWritesSupport {
    fun device(id: String, startMs: Long, seed: List<JsonObject>): ParityDevice {
        Class.forName("org.sqlite.JDBC")
        return ParityDevice(id, startMs, LocalRoutes.all).also { if (seed.isNotEmpty()) it.pull(seed, "seed") }
    }

    fun ParityDevice.call(method: String, path: String, body: String? = null, query: String = "", at: Long? = null): ShellResponse {
        at?.let { clock.now = it }
        return request(method, path, query, body?.let { ParityJson.parse(it) })
    }

    fun ShellResponse.json(): JsonObject = ParityJson.obj(ParityJson.parse(body ?: error("no body (status $status)")))

    fun ShellResponse.items(): List<JsonObject> = (json().members["items"] as JsonArray).items.map(ParityJson::obj)

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

    fun ParityDevice.mintedTypes(): List<String> = minted().map { ParityJson.str(it.members["op_type"]) }

    fun ParityDevice.rows(table: LearningTable): List<Row> =
        store.read { it.learning.all(table) }.sortedBy { r -> table.keyColumns.joinToString("|") { ParityJson.show(r[it]) } }

    /**
     * Runs scenario [id] over every local route and checks each step's `expect` (status, `problem_code`,
     * `field_error`, body pointers, lengths, absent members) and that nothing reached the upstream. Returns the device.
     */
    fun runScenario(id: String): ParityDevice {
        val scenario = ParityScenario.load(id)
        val run = ParityRunner.execute(scenario, LocalRoutes.all)
        val problems = ArrayList<String>()
        scenario.steps.indices.forEach { i ->
            val expect = (scenario.steps[i].members["expect"] as? JsonObject)?.members ?: return@forEach
            val raw = run.raw[i] ?: return@forEach
            val body: JsonValue? = raw.body?.let { runCatching { ParityJson.parse(it) }.getOrNull() }
            fun bad(what: String) = problems.add("step $i: $what")
            if (ParityJson.long(expect["status"]) != raw.status.toLong()) bad("status ${ParityJson.show(expect["status"])} != ${raw.status} ${raw.body}")
            expect["problem_code"]?.let { if (ParityJson.at(body, "/code") != it) bad("code ${ParityJson.show(it)} != ${ParityJson.show(ParityJson.at(body, "/code"))}") }
            (expect["field_error"] as? JsonObject)?.members?.forEach { (k, want) ->
                val got = ParityJson.at(body, "/errors/0/$k")
                if (got != want) bad("errors/0/$k ${ParityJson.show(want)} != ${ParityJson.show(got)}")
            }
            (expect["body"] as? JsonObject)?.members?.forEach { (pointer, want) ->
                val got = ParityJson.at(body, pointer)
                if (got != want) bad("$pointer ${ParityJson.show(want)} != ${ParityJson.show(got)}")
            }
            (expect["lengths"] as? JsonObject)?.members?.forEach { (pointer, want) ->
                val got = (ParityJson.at(body, pointer) as? JsonArray)?.items?.size
                if (got?.toLong() != ParityJson.long(want)) bad("$pointer length ${ParityJson.show(want)} != $got")
            }
        }
        assertEquals(emptyList(), problems, id)
        assertEquals(emptyList(), run.forwarded, "$id: reached the upstream (rule L-9)")
        return run.device
    }

    /**
     * SHELL.md 8.4 (a)-(c): every op [d] minted validates against `op.schema.json` and is the device's own, with
     * increasing hlcs; [seed] + the minted ops applied one at a time in several orders on fresh replicas give the same
     * [tables] as [d]. [first] = the op types that name a new row and so precede every other op on it in any log.
     */
    fun assertOpsValidAndConverge(d: ParityDevice, seed: List<JsonObject>, tables: List<LearningTable>, at: Long, first: Set<String>) {
        val ops = d.minted()
        assertTrue(ops.isNotEmpty())
        ops.forEach { op ->
            assertTrue(isValidOp(op), "invalid op ${ParityJson.encode(op)}")
            assertEquals(d.deviceId, ParityJson.str(op.members["device_id"]))
        }
        val hlcs = ops.map { ParityJson.str(it.members["hlc"]) }
        assertEquals(hlcs.sorted(), hlcs)
        val (creates, rest) = ops.partition { ParityJson.str(it.members["op_type"]) in first }
        val orders = listOf<(List<JsonObject>) -> List<JsonObject>>(
            { it },
            { it.reversed() },
            { it.shuffled(Random(81)) },
            { it.shuffled(Random(82)) },
        )
        for ((i, order) in orders.withIndex()) {
            val r = device(ORACLE, at, seed)
            (creates + order(rest)).forEach { r.pull(listOf(it), "oracle") }
            for (t in tables) assertEquals(d.rows(t), r.rows(t), "order $i: $t")
        }
    }

    const val ORACLE = "01931f3e-0000-7000-8000-0000000000e9"
}
