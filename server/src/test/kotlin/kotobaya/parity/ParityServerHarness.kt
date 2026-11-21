package kotobaya.parity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotobaya.auth.Auth
import kotobaya.auth.AuthConfig
import kotobaya.auth.PasswordHasher
import kotobaya.auth.TokenKind
import kotobaya.auth.TokenValidator
import kotobaya.auth.Tokens
import kotobaya.cards.CardsModule
import kotobaya.confusables.ConfusableModule
import kotobaya.grammar.GrammarModule
import kotobaya.decks.DecksModule
import kotobaya.dict.dictHandlers
import kotobaya.notes.NotesModule
import kotobaya.review.ReviewModule
import kotobaya.server.AppDeps
import kotobaya.server.Database
import kotobaya.server.TestPg
import kotobaya.server.json
import kotobaya.server.kotobayaModule
import kotobaya.settings.SettingsModule
import kotobaya.tts.TtsModule
import kotobaya.stats.StatsModule
import kotobaya.sync.SyncModule
import kotobaya.sync.restore.SyncWiring
import org.postgresql.PGConnection
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/** A seed or apply op the server did not accept: the scenario fails loudly (SHELL.md 8.2). */
class SeedRejected(message: String) : AssertionError(message)

/**
 * SHELL.md 8.2, server side (SHELL-P1). Per scenario: a fresh migrated database with the dictionary fixture in `ref.*`,
 * the production handler set built with one injected clock, two registered devices (`seed.device_id` pushes the
 * seed and apply steps; `device_id` sends the request steps), every request over HTTP (Ktor test host).
 *
 * Returns the raw answers in step order; apply steps are `null`.
 */
object ParityServerHarness {
    /** The built-in server device: the minting device of every server-side answer (8.3 rule 4). */
    const val SERVER_DEVICE = "00000000-0000-7000-8000-000000000001"
    private val SCOPES = listOf("read", "write", "review")
    private val cheapHash by lazy { PasswordHasher.hash("parity", PasswordHasher.Params(1024, 1, 1)) }

    fun run(scenario: ParityScenario): List<RawResponse?> {
        val db = freshDictDb()
        try {
            return runOn(db, scenario)
        } finally {
            db.close()
        }
    }

    private fun runOn(db: Database, scenario: ParityScenario): List<RawResponse?> {
        val now = AtomicReference(Instant.ofEpochMilli(scenario.clockMs))
        val clock = { now.get() }
        SyncWiring.startup(db).runOnce()
        // As composeApp, minus jobs/events, blobs and the external-service modules, but with the injected clock.
        val sync = SyncModule(db, SyncWiring.config().copy(clock = clock))
        val auth = Auth(db, AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to cheapHash)), clock, validator = TokenValidator(db, clock))
        val handlers = auth.handlers + dictHandlers(db) + sync.handlers +
            NotesModule(db, sync.opApply).handlers + DecksModule(db, sync.opApply).handlers + SettingsModule(db, sync.opApply).handlers +
            ReviewModule(db, sync.opApply).handlers + CardsModule(db, sync.opApply).handlers + StatsModule(db, sync.opApply.config.clock).handlers +
            GrammarModule(db, sync.opApply).handlers + ConfusableModule(db, sync.opApply).handlers + TtsModule(db).handlers
        val seedToken = device(db, scenario.seedDeviceId, "seed", now.get())
        val testToken = device(db, scenario.deviceId, "under-test", now.get())

        val out = mutableListOf<RawResponse?>()
        testApplication {
            application { kotobayaModule(AppDeps(db, authenticator = auth.authenticator, handlers = handlers)) }
            val http = client
            push(http, seedToken, scenario.seedLog, "seed")
            scenario.steps.forEachIndexed { i, step ->
                now.set(Instant.ofEpochMilli(step["at_ms"].asLong()))
                if (step.has("apply")) {
                    push(http, seedToken, step["apply"].toList(), "step $i")
                    out += null
                } else {
                    out += request(http, testToken, step["request"])
                }
            }
        }
        return out
    }

    /** POST /sync/push as the seed device, in order (≤ 500 per request); every op must be `accepted`. */
    private suspend fun push(http: HttpClient, token: String, ops: List<JsonNode>, what: String) {
        for (chunk in ops.chunked(500)) {
            val body = JsonNodeFactory.instance.objectNode().apply { set<JsonNode>("ops", JsonNodeFactory.instance.arrayNode().addAll(chunk)) }
            val r = http.request("/api/v1/sync/push") {
                method = HttpMethod.Post
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.ContentType, "application/json")
                setBody(json.writeValueAsString(body))
            }
            val text = r.bodyAsText()
            if (r.status.value != 200) throw SeedRejected("$what: push answered ${r.status.value}: $text")
            val results = json.readTree(text)["results"] as? ArrayNode ?: throw SeedRejected("$what: push without results: $text")
            if (results.size() != chunk.size) throw SeedRejected("$what: ${chunk.size} ops pushed, ${results.size()} results: $text")
            results.forEach { res ->
                if (res["status"]?.asText() != "accepted") throw SeedRejected("$what: op ${res["op_id"]?.asText()} was not accepted: $res")
            }
        }
    }

    private suspend fun request(http: HttpClient, token: String, req: JsonNode): RawResponse {
        val query = req["query"].asText()
        val bodyNode = req["body"]
        val r = http.request(req["path"].asText() + if (query.isEmpty()) "" else "?$query") {
            method = HttpMethod.parse(req["method"].asText())
            header(HttpHeaders.Authorization, "Bearer $token")
            if (bodyNode != null && bodyNode !is NullNode) {
                header(HttpHeaders.ContentType, "application/json")
                setBody(json.writeValueAsString(bodyNode))
            }
        }
        val headers = r.headers.names().associate { it.lowercase() to r.headers.getAll(it).orEmpty().joinToString(", ") }
        return RawResponse(r.status.value, headers, r.bodyAsText().ifEmpty { null })
    }

    /** Registers [id] in `app.device` with a device token (read, write, review); returns the token. */
    private fun device(db: Database, id: String, name: String, at: Instant): String {
        val token = Tokens.generate(TokenKind.DEVICE)
        db.txBlocking { dsl ->
            dsl.execute("insert into app.device (id, name, kind) values (?::uuid, ?, 'android')", id, name)
            dsl.execute(
                "insert into app.api_token (id, name, kind, token_hash, token_prefix, scopes, device_id, created_at) " +
                    "values (?, ?, 'device', ?, ?, ?::text[], ?::uuid, ?::timestamptz)",
                UUID.randomUUID(), "parity-$name", Tokens.hash(token), Tokens.prefix(token), SCOPES.toTypedArray(), id,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
            )
        }
        return token
    }

    /**
     * A fresh migrated database with `ref.*` loaded from the committed dump of the T101 loader's output on
     * `fixtures/dict` (`/dict/ref_fixture.sql.gz`), unchanged: no frequency ranks, nothing retired (SHELL.md 8.2).
     */
    fun freshDictDb(): Database {
        val db = TestPg.migratedDb()
        db.dataSource.connection.use { c ->
            val copy = c.unwrap(PGConnection::class.java).copyAPI
            val lines = dump
            var i = 0
            while (i < lines.size) {
                val line = lines[i++]
                if (line.startsWith("COPY ")) {
                    val data = StringBuilder()
                    while (lines[i] != "\\.") data.append(lines[i++]).append('\n')
                    i++
                    copy.copyIn(line, StringReader(data.toString()))
                } else if (line.isNotBlank() && !line.startsWith("--") && !line.startsWith("\\")) {
                    c.createStatement().use { it.execute(line) }
                }
            }
        }
        return db
    }

    private val dump: List<String> by lazy {
        GZIPInputStream(ParityServerHarness::class.java.getResourceAsStream("/dict/ref_fixture.sql.gz")!!).bufferedReader().readLines()
    }
}
