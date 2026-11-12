package kotobaya.sync

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.server.json
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `GET /sync/pull` (SYNC.md 6.1) and the push limits that only show with a small configuration. */
class PullTest : SyncFixture() {
    /** Small enough that a page holds only a few ops of ~600 bytes, and a push body of a few KiB is 413. */
    override fun syncConfig() = super.syncConfig().copy(maxPushBytes = 4096, maxOpBytes = 2048)

    private val base = 0x0193a1b2c000L

    private fun settingOps(n: Int, pad: Int = 0): List<com.fasterxml.jackson.databind.JsonNode> = (1..n).map { i ->
        op(OpType.SETTINGS_SET, A, base + i, """{"key":"k.n$i","value":"${"x".repeat(pad)}"}""")
    }

    private fun page(since: Long, limit: Int = 500): com.fasterxml.jackson.databind.JsonNode =
        json.readTree(sync.pull.pull(since, limit, null).text)

    @Test
    fun pullReturnsExactlyTheOpsAfterTheCursorInOrderWithTheEpoch() {
        val ops = settingOps(7)
        push(principal(A), *ops.toTypedArray()).forEach(::assertAccepted)
        val epoch = db.txBlocking { it.fetchValue("select value from app.server_state where key = 'log_epoch'") as String }
        val p = page(since = 2, limit = 3)
        assertEquals(listOf(3L, 4L, 5L), p["ops"].map { it["seq"].asLong() })
        assertEquals(ops.subList(2, 5).map { it["op_id"].asText() }, p["ops"].map { it["op_id"].asText() })
        assertEquals(5, p["next_since"].asLong())
        assertTrue(p["has_more"].asBoolean())
        assertEquals(7, p["high_water_seq"].asLong())
        assertEquals(epoch, p["log_epoch"].asText())
        val rest = page(since = 5)
        assertEquals(listOf(6L, 7L), rest["ops"].map { it["seq"].asLong() })
        assertEquals(false, rest["has_more"].asBoolean())
        // Each pulled op is the pushed op plus seq and caused_by_op_id, in canonical form.
        val pulled = rest["ops"][1] as com.fasterxml.jackson.databind.node.ObjectNode
        assertEquals(ops[6], pushed(pulled))
        // Empty pull and a cursor beyond the high-water mark (restored server): 200, empty, cursor unchanged.
        for (since in listOf(7L, 50L)) {
            val e = page(since)
            assertEquals(0, e["ops"].size())
            assertEquals(since, e["next_since"].asLong())
            assertEquals(false, e["has_more"].asBoolean())
            assertEquals(7, e["high_water_seq"].asLong())
        }
    }

    @Test
    fun aPageEndsBeforeItsBodyWouldExceedMaxPushBytesButHoldsAtLeastOneOp() {
        val ops = settingOps(6, pad = 900) // ~1.2 KB each in canonical form
        for (o in ops) assertAccepted(push(principal(A), o).single())
        var since = 0L
        val seen = mutableListOf<Long>()
        var pages = 0
        while (true) {
            val text = sync.pull.pull(since, 500, null).text
            assertTrue(text.toByteArray().size <= 4096, "page of ${text.length} bytes")
            val p = json.readTree(text)
            assertTrue(p["ops"].size() >= 1)
            seen += p["ops"].map { it["seq"].asLong() }
            since = p["next_since"].asLong()
            pages++
            if (!p["has_more"].asBoolean()) break
        }
        assertEquals((1L..6L).toList(), seen)
        assertTrue(pages >= 3, "$pages pages")
    }

    @Test
    fun seqGapsFromRolledBackTransactionsAreSkippedSilently() {
        assertAccepted(push(principal(A), settingOps(1)[0]).single())
        // A rolled-back transaction consumes identity values: the log gets a gap.
        runCatching { sync.opApply.transactionBlocking { it.emit(OpType.SETTINGS_SET, obj("""{"key":"g.g","value":1}""")); error("rollback") } }
        assertAccepted(push(principal(A), op(OpType.SETTINGS_SET, A, base + 50, """{"key":"k.z","value":1}""")).single())
        val p = page(0)
        assertEquals(listOf(1L, 3L), p["ops"].map { it["seq"].asLong() })
        assertEquals(3, p["next_since"].asLong())
    }

    @Test
    fun anOpOverMaxOpBytesIsRejectedAloneAndABodyOverMaxPushBytesIs413() = app(extraHandlers = sync.handlers) {
        ensureDevice(A)
        val token = mint(TokenKind.DEVICE, listOf("read", "write"), UUID.fromString(A)).second
        val big = op(OpType.SETTINGS_SET, A, base + 1, """{"key":"k.big","value":"${"x".repeat(2100)}"}""")
        val small = op(OpType.SETTINGS_SET, A, base + 2, """{"key":"k.small","value":1}""")
        val r = client.call(HttpMethod.Post, "/sync/push", bearer = token, body = json.writeValueAsString(mapOf("ops" to listOf(big, small))))
        val res = json.readTree(r.bodyAsText())["results"]
        assertEquals("op_too_large", res[0]["error"]["code"].asText())
        assertEquals("accepted", res[1]["status"].asText())
        val huge = (1..4).map { op(OpType.SETTINGS_SET, A, base + 10 + it, """{"key":"k.h$it","value":"${"x".repeat(1500)}"}""") }
        assertProblem(client.call(HttpMethod.Post, "/sync/push", bearer = token, body = json.writeValueAsString(mapOf("ops" to huge))), 413, "payload_too_large")
    }

    @Test
    fun theSameBatchPushedTwiceConcurrentlyIsAppliedOnce() {
        val ops = settingOps(5).toTypedArray()
        val a = principal(A)
        sync // initialise before the threads race
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val futures = (1..2).map { pool.submit<List<PushResult>> { barrier.await(); push(a, *ops) } }
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        val statuses = results.map { r -> r.map { it.status }.distinct().single() }.sorted()
        assertEquals(listOf(PushResult.ACCEPTED, PushResult.DUPLICATE), statuses)
        assertEquals(results[0].map { it.seq }, results[1].map { it.seq })
        assertEquals(5, count("select count(*) from app.change"))
    }
}
