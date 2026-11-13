package kotobaya.grammar

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.parse.ParserUnavailable
import kotobaya.server.json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One `errors` entry of the worker's answer: [ruleIndex] indexes the request's `rules`; [column] is 1-based, in code points. */
class RuleError(val ruleIndex: Int, val message: String, val column: Int)

/** A stored sentence in the tokenised form: [tokens] is the stored `app.sentence.tokens` array, used as given. */
class RuleSentence(val text: String, val tokens: JsonNode)

/** One match of rule [ruleIndex] in sentence [sentenceIndex] (UTF-16 offsets into that sentence's text). */
class RuleMatch(val ruleIndex: Int, val sentenceIndex: Int, val startOffset: Int, val endOffset: Int)

/** The worker's `200` body (worker-parse.yaml `WorkerRuleTestResult`). */
class RuleTestResponse(val matches: List<RuleMatch>, val errors: List<RuleError>, val truncated: Boolean)

/** Client of the worker's `POST /internal/grammar/rules/test`; the seam tests replace with a fake. */
fun interface RuleTesterClient {
    /**
     * Validates [rules] and, when [sentences] is non-empty, matches them (rule_index and sentence_index index the arguments).
     * @throws ParserUnavailable not configured, unreachable, timed out, any non-200 answer, or an invalid body.
     */
    suspend fun test(rules: List<String>, sentences: List<RuleSentence>, requestId: String?): RuleTestResponse
}

/** JDK HTTP client against `PARSER_URL` (worker-parse.yaml section 5): one attempt, total timeout [timeoutMs]. */
class HttpRuleTesterClient(
    private val baseUrl: String?,
    private val timeoutMs: Long = 10_000,
    connectTimeoutMs: Long = 1_000,
) : RuleTesterClient {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .build()

    override suspend fun test(rules: List<String>, sentences: List<RuleSentence>, requestId: String?): RuleTestResponse {
        val base = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: throw ParserUnavailable("PARSER_URL is not set")
        val builder = try {
            HttpRequest.newBuilder(URI.create(base.trimEnd('/') + "/internal/grammar/rules/test"))
        } catch (e: IllegalArgumentException) {
            throw ParserUnavailable("bad PARSER_URL", cause = e)
        }
        val body = json.createObjectNode()
        body.putArray("rules").also { a -> rules.forEach { a.add(it) } }
        if (sentences.isNotEmpty()) {
            body.putArray("sentences").also { a ->
                sentences.forEach { a.addObject().put("text", it.text).set<JsonNode>("tokens", it.tokens) }
            }
        }
        builder.timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        requestId?.let { builder.header("X-Request-Id", it) }
        val response = try {
            withContext(Dispatchers.IO) { http.send(builder.build(), HttpResponse.BodyHandlers.ofString()) }
        } catch (e: IOException) {
            throw ParserUnavailable("worker unreachable: ${e.javaClass.simpleName}", cause = e)
        }
        val text = response.body().orEmpty()
        if (response.statusCode() != 200) {
            val code = runCatching { json.readTree(text)["code"]?.asText() }.getOrNull()
            throw ParserUnavailable("worker ${response.statusCode()} ${code ?: ""}".trim(), retryAfter = code == "busy" || code == "not_ready")
        }
        return try {
            parse(json.readTree(text))
        } catch (e: JsonProcessingException) {
            throw ParserUnavailable("worker body is not JSON", cause = e)
        }
    }

    private fun parse(tree: JsonNode): RuleTestResponse {
        fun bad(): Nothing = throw ParserUnavailable("worker body is not a rule test response")
        val matches = tree["matches"]
        val errors = tree["errors"]
        val truncated = tree["truncated"]
        if (!tree.isObject || matches?.isArray != true || errors?.isArray != true || truncated?.isBoolean != true) bad()
        return RuleTestResponse(
            matches.map { m ->
                if (!m.path("rule_index").isInt || !m.path("sentence_index").isInt || !m.path("start_offset").isInt || !m.path("end_offset").isInt) bad()
                RuleMatch(m.path("rule_index").asInt(), m.path("sentence_index").asInt(), m.path("start_offset").asInt(), m.path("end_offset").asInt())
            },
            errors.map { e ->
                if (!e.path("rule_index").isInt || !e.path("message").isTextual || !e.path("column").isInt) bad()
                RuleError(e.path("rule_index").asInt(), e.path("message").asText(), e.path("column").asInt())
            },
            truncated.asBoolean(),
        )
    }
}
