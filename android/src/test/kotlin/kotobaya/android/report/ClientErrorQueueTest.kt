package kotobaya.android.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotobaya.android.net.ApiError
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [34])
class ClientErrorQueueTest {
    private class Mem : QueueStorage {
        var text: String? = null
        override fun load() = text
        override fun save(text: String) { this.text = text }
    }

    private fun queue(mem: Mem = Mem()) = ClientErrorQueue(mem, ClientErrorJson::serialize, ClientErrorJson::parse)

    private fun item(msg: String, kind: String = "exception") =
        ClientErrorItem("0.2.0 (21)", "2026-11-14T10:00:00Z", "error", kind, msg)

    @Test
    fun identicalReportsFoldIntoCount() {
        val q = queue()
        repeat(3) { q.add(item("boom")) }
        q.add(item("other"))
        assertEquals(2, q.size)
        assertEquals(3, q.snapshot().first { it.message == "boom" }.count)
    }

    @Test
    fun capsAtHundredKeepingNewestAndCountsDropped() {
        val q = queue()
        for (i in 1..105) q.add(item("e$i"))
        assertEquals(100, q.size)
        assertEquals(5, q.droppedCount)
        assertEquals("e6", q.snapshot().first().message)
        assertEquals("e105", q.snapshot().last().message)
    }

    @Test
    fun persistsAndReloads() {
        val mem = Mem()
        val q = queue(mem)
        q.add(item("boom"), durable = true)
        q.add(item("x").copy(occurredAt = "garbage")) // invalid: dropped, not queued
        val again = queue(mem)
        assertEquals(1, again.size)
        assertEquals(1, again.droppedCount)
    }

    @Test
    fun normalizeTruncatesStripsAndRejects() {
        val long = "a".repeat(3000)
        val n = ClientErrorLimits.normalize(item(long).copy(route = "/x?q=secret#f", httpStatus = 99, requestId = "has space"))!!
        assertEquals(2048, n.message.codePointCount(0, n.message.length))
        assertTrue(n.message.endsWith("…"))
        assertEquals("/x", n.route)
        assertNull(n.httpStatus)
        assertNull(n.requestId)
        assertNull(ClientErrorLimits.normalize(item("m", kind = "nope")))
    }

    @Test
    fun flushBatchesOfTwentyAndSendsDroppedOnce() = runBlocking {
        val q = queue()
        for (i in 1..45) q.add(item("e$i"))
        for (i in 1..50) q.add(item("f$i")) // 95 pending
        for (i in 1..8) q.add(item("g$i")) // 103 -> 3 dropped
        val sizes = ArrayList<Int>()
        val droppeds = ArrayList<Int>()
        val r = q.flush { items, d -> sizes += items.size; droppeds += d; SendResult.Accepted }
        assertEquals(listOf(20, 20, 20, 20, 20), sizes)
        assertEquals(listOf(3, 0, 0, 0, 0), droppeds)
        assertEquals(100, r.sent)
        assertEquals(0, q.size)
    }

    @Test
    fun tooLargeSplitsAndSingleOversizeItemIsDropped() = runBlocking {
        val q = queue()
        for (i in 1..4) q.add(item("e$i"))
        val sent = ArrayList<List<String>>()
        q.flush { items, _ ->
            if (items.size > 1 || items[0].message == "e3") SendResult.TooLarge else { sent += items.map { it.message }; SendResult.Accepted }
        }
        assertEquals(listOf(listOf("e1"), listOf("e2"), listOf("e4")), sent)
        assertEquals(0, q.size)
        assertEquals(1, q.droppedCount)
    }

    @Test
    fun rateLimitStopsAndKeeps_retryKeeps_rejectedDrops() = runBlocking {
        val q = queue()
        q.add(item("a"))
        assertEquals(7L, q.flush { _, _ -> SendResult.RateLimited(7) }.backoffSeconds)
        assertEquals(1, q.size)
        q.flush { _, _ -> SendResult.Retry }
        assertEquals(1, q.size)
        q.flush { _, _ -> SendResult.Rejected }
        assertEquals(0, q.size)
        assertEquals(1, q.droppedCount)
    }

    @Test
    fun acceptedClearsDroppedCounter() = runBlocking {
        val q = queue()
        q.add(item("bad").copy(level = "fatal"))
        q.add(item("a"))
        q.flush { _, d -> assertEquals(1, d); SendResult.Accepted }
        assertEquals(0, q.droppedCount)
    }

    @Test
    fun noRecursionFailedReportNeverQueuesAReport() = runBlocking {
        val q = queue()
        ClientErrors.install(q, "0.2.0 (21)")
        try {
            q.add(item("a"))
            val flusher = ClientErrorFlusher({ throw ApiError.Server(500) }, { true }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
            flusher.flush()
            ClientErrors.apiFailure("POST", "/client-errors?x=1", 500, null)
            ClientErrors.exception(ApiError.Server(500), "x")
            assertEquals(1, q.size)
            ClientErrors.apiFailure("GET", "/cards?query=secret", 502, "rid-1")
            assertEquals("/cards", q.snapshot().last().httpPath)
        } finally {
            ClientErrors.install(queue(), "0")
        }
    }

    @Test
    fun exceptionTextIsScrubbed() {
        val e = IllegalStateException("bad note \"私の秘密\" here", RuntimeException("inner 'secret'"))
        val m = ErrorText.message(e)
        assertTrue("秘密" !in m)
        assertTrue("secret" !in ErrorText.stack(e))
    }
}
