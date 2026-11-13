package kotobaya.parse

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.server.json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The worker's `200` body (contracts/api/worker-parse.yaml): sentences are passed through untouched. */
class WorkerParse(val parserVersion: String?, val sentences: JsonNode)

/** The worker said `413 payload_too_large`. */
class ParserTooLarge : RuntimeException("worker: payload_too_large")

/** Not configured, unreachable, timed out, a non-200/413 answer or an invalid body (PARSER.md 5.3). */
class ParserUnavailable(message: String, val retryAfter: Boolean = false, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Client of the worker parse service; the seam tests replace with a fake. */
fun interface ParserClient {
    /** @throws ParserTooLarge @throws ParserUnavailable */
    suspend fun parse(text: String, requestId: String?): WorkerParse
}

/**
 * JDK HTTP client against `PARSER_URL` (PARSER.md 5.3): total timeout [timeoutMs], connect timeout 1 s, one attempt.
 * [baseUrl] null/blank means "not configured": every call is unavailable.
 */
class HttpParserClient(
    private val baseUrl: String?,
    private val timeoutMs: Long = 10_000,
    connectTimeoutMs: Long = 1_000,
) : ParserClient {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .build()

    override suspend fun parse(text: String, requestId: String?): WorkerParse {
        val base = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: throw ParserUnavailable("PARSER_URL is not set")
        val builder = try {
            HttpRequest.newBuilder(URI.create(base.trimEnd('/') + "/internal/parse"))
        } catch (e: IllegalArgumentException) {
            throw ParserUnavailable("bad PARSER_URL", cause = e)
        }
        builder.timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf("text" to text))))
        requestId?.let { builder.header("X-Request-Id", it) }
        val response = try {
            withContext(Dispatchers.IO) { http.send(builder.build(), HttpResponse.BodyHandlers.ofString()) }
        } catch (e: IOException) {
            throw ParserUnavailable("worker unreachable: ${e.javaClass.simpleName}", cause = e)
        }
        val body = response.body().orEmpty()
        when (response.statusCode()) {
            200 -> Unit
            413 -> throw ParserTooLarge()
            else -> {
                val code = runCatching { json.readTree(body)["code"]?.asText() }.getOrNull()
                throw ParserUnavailable(
                    "worker ${response.statusCode()} ${code ?: ""}".trim(),
                    retryAfter = code == "busy" || code == "not_ready",
                )
            }
        }
        val tree = try {
            json.readTree(body)
        } catch (e: JsonProcessingException) {
            throw ParserUnavailable("worker body is not JSON", cause = e)
        }
        return validated(tree)
    }

    private fun validated(tree: JsonNode): WorkerParse {
        val sentences = tree["sentences"]
        val version = tree["parser_version"]
        if (!tree.isObject || sentences == null || !sentences.isArray || (version != null && !version.isTextual && !version.isNull)) {
            throw ParserUnavailable("worker body is not a parse response")
        }
        for (s in sentences) {
            if (!s.isObject || s["text"]?.isTextual != true || s["tokens"]?.isArray != true) {
                throw ParserUnavailable("worker body has a malformed sentence")
            }
        }
        return WorkerParse(version?.takeIf { it.isTextual }?.asText(), sentences)
    }
}
