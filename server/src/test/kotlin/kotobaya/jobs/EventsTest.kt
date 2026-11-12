package kotobaya.jobs

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotobaya.server.AppDeps
import kotobaya.server.Database
import kotobaya.server.TestPg
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.server.kotobayaModule
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One parsed SSE record; `ping` is the `: ping` comment. */
private class Sse(val id: Int?, val type: String, val data: JsonNode?)

/** Reads a real HTTP stream on a thread; records are queued so that reads can time out. */
private class SseReader(input: java.io.InputStream) {
    private val queue = LinkedBlockingQueue<Sse>()

    init {
        Thread {
            runCatching {
                val br = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                var id: Int? = null
                var type: String? = null
                var data: JsonNode? = null
                while (true) {
                    val line = br.readLine() ?: break
                    when {
                        line == ": ping" -> queue.put(Sse(null, "ping", null))
                        line.startsWith("id: ") -> id = line.removePrefix("id: ").toInt()
                        line.startsWith("event: ") -> type = line.removePrefix("event: ")
                        line.startsWith("data: ") -> data = json.readTree(line.removePrefix("data: "))
                        line.isEmpty() && type != null -> {
                            queue.put(Sse(id, type, data))
                            type = null
                        }
                    }
                }
            }
            queue.put(Sse(null, "eof", null))
        }.also { it.isDaemon = true }.start()
    }

    /** Next event or ping within [ms]; null on timeout; type "eof" when the stream ended. */
    fun next(ms: Long): Sse? = queue.poll(ms, TimeUnit.MILLISECONDS)

    /** Skips pings and other types until [type] arrives within [ms]; "eof" is returned as is. */
    fun until(type: String, ms: Long): Sse? {
        val deadline = System.currentTimeMillis() + ms
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val e = next(left) ?: return null
            if (e.type == type || e.type == "eof") return e
        }
    }
}

class EventsTest {
    private class Env(val db: Database, val jobs: JobsModule, val scope: CoroutineScope, val server: io.ktor.server.engine.EmbeddedServer<*, *>, val port: Int)

    private fun env(
        pingMs: Long = 25_000,
        guardMs: Long = 5_000,
        valid: suspend (kotobaya.server.TokenInfo?) -> Boolean = { true },
        before: (Database) -> Unit = {},
    ): Env {
        val url = TestPg.freshDatabaseUrl()
        val db = Database.connect(url)
        kotobaya.server.Migrations.migrate(db.dataSource)
        db.txBlocking {
            it.execute("insert into app.server_state (key, value, updated_at) values ('log_epoch', ?, now())", Uuid7.generate().toString())
        }
        before(db)
        val jobs = JobsModule(db, url, credentialValid = valid, pingIntervalMs = pingMs, guardIntervalMs = guardMs)
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { kotobayaModule(AppDeps(db, handlers = jobs.handlers)) }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        jobs.start(scope)
        return Env(db, jobs, scope, server, port)
    }

    private fun Env.finish() {
        jobs.close()
        scope.cancel()
        server.stop(100, 500)
        db.close()
    }

    private fun Env.stream(query: String = "", block: (SseReader) -> Unit) {
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/events$query")).build()
        val resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofInputStream())
        assertEquals("text/event-stream", resp.headers().firstValue("content-type").get().substringBefore(';'))
        resp.body().use { block(SseReader(it)) }
    }

    @Test
    fun helloIsFirstWithHighWaterSeqAndJobProgressArrivesWithinASecond() {
        val jobId = Uuid7.generate()
        val e = env(before = { db ->
            db.txBlocking {
                it.execute("insert into app.job (id, kind, payload) values (?, 'media-parse', '{}'::jsonb)", jobId)
            }
        })
        e.stream { r ->
            val hello = assertNotNull(r.next(5_000))
            assertEquals("hello", hello.type)
            assertEquals(1, hello.id)
            assertEquals(0, hello.data!!["data"]["high_water_seq"].asInt())
            assertEquals(36, hello.data["data"]["log_epoch"].asText().length)

            // another connection updates the job; no NOTIFY involved
            val t0 = System.currentTimeMillis()
            e.db.txBlocking {
                it.execute(
                    "update app.job set status='running', progress=0.5, progress_message='half', started_at=now(), attempts=1 where id=?", jobId,
                )
            }
            val upd = assertNotNull(r.until("job.updated", 3_000))
            assertTrue(System.currentTimeMillis() - t0 < 1_000, "job.updated took too long")
            assertEquals(2, upd.id)
            assertEquals(jobId.toString(), upd.data!!["data"]["id"].asText())
            assertEquals(0.5, upd.data["data"]["progress"].asDouble())
            assertEquals("running", upd.data["data"]["status"].asText())
        }
        e.finish()
    }

    @Test
    fun terminalJobStateIsAlwaysSentEvenRightAfterAProgressEvent() {
        val jobId = Uuid7.generate()
        val e = env(before = { db ->
            db.txBlocking { it.execute("insert into app.job (id, kind, payload, status, started_at) values (?, 'tts', '{}'::jsonb, 'running', now())", jobId) }
        })
        e.stream { r ->
            r.next(5_000)
            e.db.txBlocking { it.execute("update app.job set progress = 0.3 where id = ?", jobId) }
            assertEquals(0.3, r.until("job.updated", 3_000)!!.data!!["data"]["progress"].asDouble())
            e.db.txBlocking { it.execute("update app.job set status='succeeded', progress=1, finished_at=now(), result='{}'::jsonb where id = ?", jobId) }
            val fin = r.until("job.updated", 3_000)!!
            assertEquals("succeeded", fin.data!!["data"]["status"].asText())
        }
        e.finish()
    }

    @Test
    fun pingIsSentWhenIdleAndTypesFilterApplies() {
        val e = env(pingMs = 200)
        e.stream("?types=note.created") { r ->
            assertEquals("hello", r.next(5_000)!!.type)
            assertEquals("ping", r.next(3_000)!!.type)
            // a type outside the filter is not delivered; a listed one is
            runBlocking { e.jobs.bus.publish("device.paired", mapOf("device_id" to Uuid7.generate().toString(), "name" to "phone")) }
            val noteId = Uuid7.generate().toString()
            runBlocking { e.jobs.bus.publish("note.created", mapOf("note_id" to noteId, "origin_device_id" to noteId, "origin" to "x", "seq" to 7)) }
            val got = r.until("note.created", 3_000)!!
            assertEquals(noteId, got.data!!["data"]["note_id"].asText())
        }
        e.finish()
    }

    @Test
    fun streamEndsWhenTheCredentialIsNoLongerValid() {
        val valid = java.util.concurrent.atomic.AtomicBoolean(true)
        val e = env(guardMs = 100, valid = { valid.get() })
        e.stream { r ->
            assertEquals("hello", r.next(5_000)!!.type)
            valid.set(false)
            assertEquals("eof", r.until("never", 3_000)!!.type)
        }
        e.finish()
    }

    @Test
    fun slowConsumerIsDroppedAboveTheBufferLimit() {
        val hub = EventHub()
        val slow = hub.subscribe(null)
        val fast = hub.subscribe(null)
        repeat(MAX_BUFFERED_EVENTS) {
            hub.dispatch("note.created", Instant.now(), mapOf("n" to it))
            assertTrue(fast.channel.tryReceive().isSuccess)
        }
        assertFalse(slow.dropped) // exactly 1000 buffered is still fine
        hub.dispatch("note.created", Instant.now(), mapOf("n" to "overflow"))
        assertTrue(slow.dropped)
        assertEquals(1, hub.subscriberCount)
        assertFalse(fast.dropped)
        assertTrue(fast.channel.tryReceive().isSuccess)
    }

    @Test
    fun syncChangedIsCoalescedToOnePerSecond() {
        var now = 10_000L
        val hub = EventHub(clock = { now })
        val s = hub.subscribe(null)
        hub.offerSyncChanged(1)
        hub.offerSyncChanged(2)
        hub.offerSyncChanged(3)
        val first = json.readTree(s.channel.tryReceive().getOrNull()!!.json)
        assertEquals(1, first["data"]["high_water_seq"].asInt())
        assertNull(s.channel.tryReceive().getOrNull())
        now += 500
        hub.flushSync()
        assertNull(s.channel.tryReceive().getOrNull())
        now += 600
        hub.flushSync()
        assertEquals(3, json.readTree(s.channel.tryReceive().getOrNull()!!.json)["data"]["high_water_seq"].asInt())
        hub.flushSync()
        assertNull(s.channel.tryReceive().getOrNull())
    }
}
