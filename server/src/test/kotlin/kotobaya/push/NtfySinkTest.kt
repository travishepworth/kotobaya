package kotobaya.push

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotobaya.jobs.EventHub
import kotobaya.server.TestPg
import kotobaya.server.Uuid7
import kotobaya.server.json
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NtfySinkTest {
    private class Posted(val path: String, val title: String?, val auth: String?, val body: String)

    /** A fake ntfy: records POSTs; the first [failFirst] answer 500. */
    private class FakeNtfy(failFirst: Int = 0) : AutoCloseable {
        val posts = CopyOnWriteArrayList<Posted>()
        private val seen = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                posts += Posted(ex.requestURI.path, ex.requestHeaders.getFirst("Title"), ex.requestHeaders.getFirst("Authorization"), body)
                val code = if (seen.incrementAndGet() <= failFirst) 500 else 200
                ex.sendResponseHeaders(code, -1)
                ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private fun job(id: String, status: String, kind: String = "media-import") =
        mapOf("id" to id, "kind" to kind, "status" to status)

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
    }

    private fun withSink(
        cfg: NtfyConfig?,
        topics: List<String>,
        backoff: List<Long> = listOf(10, 10),
        clock: () -> Long = System::currentTimeMillis,
        block: (EventHub) -> Unit,
    ) {
        val hub = EventHub()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            if (cfg != null) NtfySink(cfg, hub, { topics }, backoffMs = backoff, clock = clock).start(scope)
            waitFor { cfg == null || hub.subscriberCount > 0 }
            block(hub)
        } finally {
            scope.cancel()
        }
    }

    private fun cfg(ntfy: FakeNtfy, token: String? = null) = NtfyConfig("https://ntfy.example.invalid", ntfy.url, token)
    private val topicA = "https://ntfy.example.invalid/up-kotobaya-a"
    private val topicB = "https://ntfy.example.invalid/up-kotobaya-b"

    @Test
    fun aSucceededJobProducesExactlyOnePostToTheDevicesTopicWithTheEventAsBody() = FakeNtfy().use { ntfy ->
        withSink(cfg(ntfy, "secret-token"), listOf(topicA)) { hub ->
            hub.dispatch("job.updated", Instant.now(), job("j1", "running"))
            hub.dispatch("job.updated", Instant.now(), job("j1", "succeeded"))
            hub.dispatch("job.updated", Instant.now(), job("j1", "succeeded")) // a repeat of the same terminal state
            waitFor { ntfy.posts.isNotEmpty() }
            Thread.sleep(300)
            val p = ntfy.posts.single()
            assertEquals("/up-kotobaya-a", p.path)
            assertEquals("media-import finished", p.title)
            assertEquals("Bearer secret-token", p.auth)
            val body = json.readTree(p.body)
            assertEquals("job.updated", body.path("type").asText())
            assertEquals("succeeded", body.path("data").path("status").asText())
            assertEquals("j1", body.path("data").path("id").asText())
        }
    }

    @Test
    fun aFailedJobIsForwardedAndACancelledOneIsNot() = FakeNtfy().use { ntfy ->
        withSink(cfg(ntfy), listOf(topicA)) { hub ->
            hub.dispatch("job.updated", Instant.now(), job("j2", "cancelled"))
            hub.dispatch("job.updated", Instant.now(), job("j3", "failed", "dict-refresh"))
            waitFor { ntfy.posts.isNotEmpty() }
            Thread.sleep(200)
            val p = ntfy.posts.single()
            assertEquals("dict-refresh failed", p.title)
            assertNull(p.auth)
        }
    }

    @Test
    fun everyDeviceTopicGetsItAndForeignOrNoTopicsGetNothing() = FakeNtfy().use { ntfy ->
        withSink(cfg(ntfy), listOf(topicA, topicB, "https://evil.example.invalid/x", "https://ntfy.example.invalid/")) { hub ->
            hub.dispatch("job.updated", Instant.now(), job("j4", "succeeded"))
            waitFor { ntfy.posts.size >= 2 }
            Thread.sleep(200)
            assertEquals(setOf("/up-kotobaya-a", "/up-kotobaya-b"), ntfy.posts.map { it.path }.toSet())
            assertEquals(2, ntfy.posts.size)
        }
    }

    @Test
    fun syncChangedIsSentAtMostOncePerMinutePerTopic() = FakeNtfy().use { ntfy ->
        var now = 1_000_000L
        withSink(cfg(ntfy), listOf(topicA), clock = { now }) { hub ->
            hub.dispatch("sync.changed", Instant.now(), mapOf("high_water_seq" to 1))
            waitFor { ntfy.posts.size >= 1 }
            now += 30_000
            hub.dispatch("sync.changed", Instant.now(), mapOf("high_water_seq" to 2))
            Thread.sleep(300)
            assertEquals(1, ntfy.posts.size)
            now += 31_000
            hub.dispatch("sync.changed", Instant.now(), mapOf("high_water_seq" to 3))
            waitFor { ntfy.posts.size >= 2 }
            assertEquals(2, ntfy.posts.size)
            assertEquals(3, json.readTree(ntfy.posts.last().body).path("data").path("high_water_seq").asInt())
        }
    }

    @Test
    fun unsetBaseUrlMeansNoSink() {
        assertNull(NtfyConfig.fromEnv(emptyMap()))
        assertNull(NtfyConfig.fromEnv(mapOf("NTFY_BASE_URL" to "  ")))
        val c = NtfyConfig.fromEnv(mapOf("NTFY_BASE_URL" to "https://n.example/ntfy/", "NTFY_POST_BASE_URL" to "http://ntfy:8080", "NTFY_TOKEN" to "tk"))!!
        assertEquals("http://ntfy:8080/up1", c.postUrl("https://n.example/ntfy/up1"))
        assertNull(c.postUrl("https://n.example/other/up1"))
        assertTrue("tk" !in c.toString())
        FakeNtfy().use { ntfy -> // with no sink started, events reach nobody
            withSink(null, listOf(topicA)) { hub ->
                hub.dispatch("job.updated", Instant.now(), job("j5", "succeeded"))
                Thread.sleep(200)
                assertTrue(ntfy.posts.isEmpty())
            }
        }
    }

    @Test
    fun failuresAreRetriedAndNeverBlockOrBreakTheEventPath() = FakeNtfy(failFirst = 2).use { ntfy ->
        withSink(cfg(ntfy), listOf(topicA)) { hub ->
            val started = System.nanoTime()
            hub.dispatch("job.updated", Instant.now(), job("j6", "succeeded"))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 500) // dispatch returned without waiting for ntfy
            waitFor { ntfy.posts.size >= 3 }
            assertEquals(3, ntfy.posts.size) // two 500s, then success
            hub.dispatch("job.updated", Instant.now(), job("j7", "succeeded")) // the sink is still alive
            waitFor { ntfy.posts.size >= 4 }
            assertEquals(4, ntfy.posts.size)
        }
    }

    @Test
    fun anUnreachableNtfyIsGivenUpOnAfterTheBoundedRetries() {
        withSink(NtfyConfig("https://ntfy.example.invalid", "http://127.0.0.1:1", null), listOf(topicA), backoff = listOf(5, 5)) { hub ->
            hub.dispatch("job.updated", Instant.now(), job("j8", "succeeded"))
            Thread.sleep(300)
            assertEquals(1, hub.subscriberCount) // still subscribed, nothing thrown
        }
    }

    @Test
    fun startupStartsTheSinkOnlyWhenTheBaseUrlIsSet() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val off = EventHub()
            assertNull(startNtfySink(scope, off, { emptyList() }, emptyMap()))
            Thread.sleep(100)
            assertEquals(0, off.subscriberCount)
            val on = EventHub()
            assertTrue(startNtfySink(scope, on, { emptyList() }, mapOf("NTFY_BASE_URL" to "https://n.example.invalid")) != null)
            waitFor { on.subscriberCount == 1 }
            assertEquals(1, on.subscriberCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theDeviceTableQuerySkipsRevokedDevicesAndEmptyTopics() {
        val db = TestPg.migratedDb()
        fun device(topic: String?, revoked: Boolean) = db.txBlocking {
            it.execute(
                "insert into app.device (id, name, kind, push_topic, revoked_at) values (?, 'd', 'android', ?, ${if (revoked) "now()" else "null"})",
                Uuid7.generate(), topic,
            )
        }
        device(topicA, false)
        device(topicB, true)
        device(null, false)
        device("", false)
        assertEquals(listOf(topicA), runBlocking { PushTopics.fromDb(db).all() })
        db.close()
    }
}
