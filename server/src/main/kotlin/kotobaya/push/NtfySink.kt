package kotobaya.push

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotobaya.jobs.EventHub
import kotobaya.server.Database
import kotobaya.server.json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * ntfy forwarding settings (env). Off unless `NTFY_BASE_URL` is set.
 *
 * - [baseUrl]: the public ntfy base URL. Only device `push_topic` URLs under it are ever posted to (a device can set
 *   its own `push_topic`, so anything else would let a paired device aim the server at an arbitrary URL).
 * - [postBaseUrl]: where to actually post (an in-cluster address); the [baseUrl] prefix of a topic is replaced by it.
 * - [token]: an ntfy access token (from a Secret) sent as a Bearer header; never logged.
 */
class NtfyConfig(val baseUrl: String, val postBaseUrl: String, val token: String?) {
    override fun toString() = "NtfyConfig(configured)" // no URL credentials, no token

    /** The URL to post to for a device's [topic], or null if the topic is not under [baseUrl]. */
    fun postUrl(topic: String): String? {
        if (!topic.startsWith("$baseUrl/") || topic.length == baseUrl.length + 1) return null
        return postBaseUrl + topic.substring(baseUrl.length)
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): NtfyConfig? {
            val base = env["NTFY_BASE_URL"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val post = env["NTFY_POST_BASE_URL"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: base
            return NtfyConfig(base, post, env["NTFY_TOKEN"]?.takeIf { it.isNotBlank() })
        }
    }
}

/** `push_topic` of every non-revoked device that has one. */
fun interface PushTopics {
    suspend fun all(): List<String>

    companion object {
        fun fromDb(db: Database) = PushTopics {
            db.tx { dsl ->
                dsl.fetch("select push_topic from app.device where revoked_at is null and push_topic is not null and push_topic <> ''")
                    .map { it.get(0, String::class.java) }
            }
        }
    }
}

/** Startup hook: starts the sink only when `NTFY_BASE_URL` is set; returns its job, or null when forwarding is off. */
fun startNtfySink(scope: CoroutineScope, hub: EventHub, topics: PushTopics, env: Map<String, String> = System.getenv()): Job? =
    NtfyConfig.fromEnv(env)?.let { NtfySink(it, hub, topics).start(scope) }

/**
 * Forwards events to ntfy as the OpenAPI `/events` section says: the `Event` JSON is the message body, posted to the
 * `push_topic` of every non-revoked device, for `job.updated` with a terminal status (`succeeded`/`failed`; a
 * cancelled job is not news) and `sync.changed` (at most one per minute per topic).
 *
 * It is an ordinary [EventHub] subscriber, so the event path never waits for it: delivery runs on its own coroutine,
 * a failure is retried with a bounded backoff and then dropped, and a hub that disconnects a slow sink is subscribed
 * to again. Not done: skipping the device that originated a `sync.changed` (the event does not say who; a redundant
 * pull is harmless). Backup failures are reported by the backup job itself (`backup.notify.url`), not through here.
 */
class NtfySink(
    private val config: NtfyConfig,
    private val hub: EventHub,
    private val topics: PushTopics,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val backoffMs: List<Long> = listOf(1_000, 4_000, 16_000),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger("kotobaya.push")
    private val deliveredJobs = LinkedHashSet<String>()
    private val lastSyncSent = HashMap<String, Long>()

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val sub = hub.subscribe(setOf("job.updated", "sync.changed"))
            try {
                while (isActive) {
                    val frame = sub.channel.receive()
                    runCatching { handle(frame.json) }
                        .onFailure { log.warn("ntfy forwarding failed: {}", it.javaClass.simpleName) }
                }
            } catch (_: ClosedReceiveChannelException) {
                delay(100) // dropped as a slow consumer; subscribe again
            } finally {
                hub.unsubscribe(sub)
            }
        }
    }

    internal suspend fun handle(eventJson: String) {
        val event: JsonNode = json.readTree(eventJson)
        val title: String
        when (event.path("type").asText()) {
            "job.updated" -> {
                val job = event.path("data")
                val status = job.path("status").asText()
                if (status != "succeeded" && status != "failed") return
                if (!deliveredJobs.add(job.path("id").asText() + ":" + status)) return
                if (deliveredJobs.size > 500) deliveredJobs.remove(deliveredJobs.first())
                title = job.path("kind").asText("job") + if (status == "succeeded") " finished" else " failed"
            }
            "sync.changed" -> title = "Sync changed"
            else -> return
        }
        val isSync = title == "Sync changed"
        val now = clock()
        for (topic in topics.all().distinct()) {
            val url = config.postUrl(topic) ?: continue
            if (isSync) {
                if (now - (lastSyncSent[topic] ?: Long.MIN_VALUE / 2) < SYNC_MIN_GAP_MS) continue
                lastSyncSent[topic] = now
            }
            send(url, title, eventJson)
        }
    }

    private suspend fun send(url: String, title: String, body: String) {
        val request = try {
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Title", title)
                .header("Content-Type", "application/json")
                .apply { config.token?.let { header("Authorization", "Bearer $it") } }
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .build()
        } catch (e: IllegalArgumentException) {
            return // a device stored an unusable URL
        }
        for (attempt in 0..backoffMs.size) {
            val ok = try {
                withContext(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299 }
            } catch (e: Exception) {
                false
            }
            if (ok) return
            if (attempt == backoffMs.size) {
                log.warn("ntfy delivery gave up after {} attempts", attempt + 1) // no URL: the topic path is a secret
                return
            }
            delay(backoffMs[attempt])
        }
    }

    private companion object {
        const val SYNC_MIN_GAP_MS = 60_000L
    }
}
