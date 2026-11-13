package kotobaya.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotobaya.auth.AuthConfig
import kotobaya.auth.PasswordHasher
import kotobaya.auth.TokenKind
import kotobaya.auth.Tokens
import kotobaya.blobs.InMemoryBlobStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-15: boots the real composition (`composeApp`, as Main.kt does) and asserts every OpenAPI operation answers with
 * something other than the 501 stub, except the explicit allowlist below.
 */
class WiringAuditTest {
    private val hash = PasswordHasher.hash("pw", PasswordHasher.Params(1024, 1, 1))

    /** Deliberately unwired until W-5 (parse/sentences/coverage handlers are in flight in other tickets). */
    private val unwiredUntilW5: Set<String> = setOf(
        // Every other entry has no handler in this tree yet (later tickets); remove entries as they land.
        "listHotKanji",
        "listHotWords",
        "listLeeches",
        "listWordConfusables",
        "listConfusables",
        "setConfusable",
        "listGrammarPoints",
        "createGrammarPoint",
        "getGrammarPoint",
        "updateGrammarPoint",
        "deleteGrammarPoint",
        "testGrammarRules",
        "listGrammarExamples",
        "putGrammarSpan",
        "removeGrammarSpan",
        "getStatsSummary",
        "getStatsHeatmap",
        "getStatsRetention",
        "getStatsForecast",
        "getStatsKnownWords",
        "getStatsCalibration",
        "synthesizeSpeech",
        "exportAll",
        "exportAnki",
    )

    @Test
    fun everyImplementedOperationIsRegistered() {
        val url = TestPg.freshDatabaseUrl()
        val db = Database.connect(url)
        Migrations.migrate(db.dataSource)
        val app = composeApp(
            db, url, AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to hash, "PUBLIC_URL" to "https://kotobaya.example.net")),
            blobStore = InMemoryBlobStore(),
        )
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { kotobayaModule(app.deps) }
        server.start(wait = false)
        val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val root = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
        val notImplemented = mutableSetOf<String>()
        try {
            for (op in app.deps.spec.operations) {
                // A fresh full-scope token per request: some operations (logout, revoke) consume their caller.
                val token = Tokens.generate(TokenKind.PERSONAL)
                db.txBlocking {
                    it.execute(
                        "insert into app.api_token (id, name, kind, token_hash, token_prefix, scopes) values (?, 'f15', 'personal', ?, ?, ?)",
                        UUID.randomUUID(), Tokens.hash(token), Tokens.prefix(token),
                        arrayOf("read", "write", "review", "import", "admin"),
                    )
                }
                val path = op.path.replace(Regex("\\{[^}]+}")) { UUID.randomUUID().toString() }
                val method = op.method.value
                val body = if (method == "GET" || method == "DELETE") HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString("{}")
                val b = HttpRequest.newBuilder(URI(root + app.deps.spec.basePath + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .method(method, body)
                // Streaming operations (events) only need their headers.
                val resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
                resp.body().close()
                if (resp.statusCode() == 501) notImplemented += op.operationId
            }
        } finally {
            server.stop(100, 500)
            app.jobs.close()
            db.close()
        }
        assertEquals(unwiredUntilW5, notImplemented, "operations answering 501 must be exactly the W-5 allowlist")
        assertTrue("listJpdbVocab" !in notImplemented)
    }
}
