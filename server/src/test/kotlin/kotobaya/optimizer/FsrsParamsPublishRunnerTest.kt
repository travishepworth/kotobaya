package kotobaya.optimizer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.SyncFixture
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T606: the `fsrs-params-publish` runner (FSRS_PUBLISH.md 2, 3) against a real Postgres; fixture = the C-16 first example. */
class FsrsParamsPublishRunnerTest : SyncFixture() {
    private val runner by lazy { FsrsParamsPublishRunner(db, sync.opApply, instanceId = "test") }

    private fun parent(status: String = "running", preset: String = PRESET): UUID {
        val id = Uuid7.generate()
        db.txBlocking {
            it.execute(
                "insert into app.job (id, kind, payload, status, started_at, finished_at) values (?, 'fsrs-optimize', ?::jsonb, ?, now(), " +
                    (if (status == "running") "null" else "now()") + ")",
                id, """{"preset_id":"$preset"}""", status,
            )
        }
        return id
    }

    private fun payload(parent: UUID, base: Int = 0, mutate: (ObjectNode) -> Unit = {}): ObjectNode =
        (json.readTree(EXAMPLE) as ObjectNode).also {
            it.put("parent_job_id", parent.toString()).put("base_weights_version", base)
            mutate(it)
        }

    private fun child(p: ObjectNode): UUID {
        val id = Uuid7.generate()
        db.txBlocking {
            it.execute(
                "insert into app.job (id, kind, payload, dedupe_key, max_attempts) values (?, 'fsrs-params-publish', ?::jsonb, ?, 1)",
                id, json.writeValueAsString(p), "fsrs-params-publish:" + p["parent_job_id"].asText() + ":" + id,
            )
        }
        return id
    }

    private fun run(p: ObjectNode): UUID = child(p).also { assertTrue(runner.runOnce()) }

    private fun job(id: UUID): Map<String, Any?> = db.txBlocking {
        it.fetchOne("select status, error, result::text as result, progress from app.job where id = ?", id)!!.intoMap()
    }

    private fun ops(): List<JsonNode> = db.txBlocking { dsl ->
        dsl.fetch("select payload::text from app.change where op_type = 'fsrs_params.published' order by seq").map { json.readTree(it.get(0, String::class.java)) }
    }

    private fun stored(): Int = count("select weights_version from app.fsrs_params where id = ?::uuid", PRESET).toInt()

    private fun assertFailed(code: String, id: UUID) {
        val j = job(id)
        assertEquals("failed", j["status"], "$j")
        assertTrue((j["error"] as String).startsWith("$code:"), "error ${j["error"]} should start with $code")
    }

    @Test
    fun `mints exactly one op with weights_version plus one and the stats keys`() {
        val parent = parent()
        val c = run(payload(parent))
        val j = job(c)
        assertEquals("succeeded", j["status"], "$j")
        val ops = ops()
        assertEquals(1, ops.size)
        assertEquals(1, ops[0]["weights_version"].asInt())
        assertEquals(PRESET, ops[0]["id"].asText())
        assertEquals(0.3412, ops[0]["optimizer_stats"]["log_loss"].asDouble())
        assertEquals(0.3655, ops[0]["optimizer_stats"]["log_loss_before"].asDouble())
        assertEquals(1064, ops[0]["optimizer_stats"]["holdout_review_count"].asInt())
        assertEquals(1, stored())
        val r = json.readTree(j["result"] as String)
        assertEquals(true, r["published"].asBoolean())
        assertEquals(1, r["weights_version"].asInt())
        assertTrue(r["reason"].isNull)
        assertEquals(0.95, (db.txBlocking { it.fetchValue("select progress from app.job where id = ?", parent) as Number }).toDouble(), 0.001)
    }

    @Test
    fun `replay is idempotent - same parent again is a no-op, another parent on the old base is stale`() {
        val parent = parent()
        val first = run(payload(parent))
        val again = run(payload(parent))
        assertEquals("succeeded", job(again)["status"])
        assertEquals(json.readTree(job(first)["result"] as String), json.readTree(job(again)["result"] as String))
        assertEquals(1, ops().size)
        assertEquals(1, stored())
        val stale = run(payload(parent()))
        assertFailed("stale_base", stale)
        assertEquals(1, ops().size)
    }

    @Test
    fun `versions increase by exactly one per publication`() {
        run(payload(parent()))
        run(payload(parent(), base = 1) { it.put("log_loss_before", 0.3412).put("log_loss_after", 0.33) })
        assertEquals(listOf(1, 2), ops().map { it["weights_version"].asInt() })
        assertEquals(2, stored())
    }

    @Test
    fun `out-of-bound weights are rejected and never clamped`() {
        val bad = payload(parent()) { (it["weights"] as com.fasterxml.jackson.databind.node.ArrayNode).set(4, json.nodeFactory.numberNode(99.0)) }
        assertFailed("weights_out_of_bounds", run(bad))
        assertTrue(ops().isEmpty())
        assertEquals(0, stored())
    }

    @Test
    fun `a payload that is not strictly better is refused by the runner too`() {
        assertFailed("not_improved", run(payload(parent()) { it.put("log_loss_after", 0.3655) }))
        assertFailed("invalid_payload", run(payload(parent()) { it.put("holdout_review_count", 5321) }))
        assertFailed("unsupported_format", run(payload(parent()) { it.put("format_version", 2) }))
        assertTrue(ops().isEmpty())
    }

    @Test
    fun `a cancelled parent publishes nothing`() {
        val c = run(payload(parent(status = "cancelled")))
        assertEquals("cancelled", job(c)["status"])
        assertTrue(ops().isEmpty())
        assertNull(job(c)["error"])
        assertEquals(0, stored())
    }

    private companion object {
        const val PRESET = "00000000-0000-7000-8000-000000000010"
        const val EXAMPLE = """{"format_version":1,"parent_job_id":"01931f42-0000-7000-8000-000000000b01","preset_id":"$PRESET","base_weights_version":0,"fsrs_version":"6","weights":[0.212,1.2931,2.3065,8.2956,6.4133,0.8334,3.0194,0.001,1.8722,0.1666,0.796,1.4835,0.0614,0.2629,1.6483,0.6014,1.8729,0.5425,0.0912,0.0658,0.1542],"optimized_at":1733584666631,"review_count":5321,"holdout_review_count":1064,"log_loss_before":0.3655,"log_loss_after":0.3412,"rmse_bins":0.0312}"""
    }
}
