package kotobaya.wiring

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotobaya.auth.AuthConfig
import kotobaya.auth.PasswordHasher
import kotobaya.auth.TokenKind
import kotobaya.auth.Tokens
import kotobaya.server.Database
import kotobaya.server.Migrations
import kotobaya.server.TestPg
import kotobaya.server.Uuid7
import kotobaya.server.composeApp
import kotobaya.server.json
import kotobaya.server.kotobayaModule
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real `composeApp` on Netty: revocation and expiry end `/events` streams, and `device.revoked` reaches
 * other streams through NOTIFY (the jobs bus and hub that composeApp hands to auth).
 */
class RevocationWiringTest {
    private val password = "wiring test password"
    private val hash = PasswordHasher.hash(password, PasswordHasher.Params(1024, 1, 1))

    @Test
    fun revocationAndExpiryEndEventStreamsAndDeviceRevokedIsPublished() {
        val url = TestPg.freshDatabaseUrl()
        val db = Database.connect(url)
        Migrations.migrate(db.dataSource)
        db.txBlocking {
            it.execute("insert into app.server_state (key, value, updated_at) values ('log_epoch', ?, now())", Uuid7.generate().toString())
        }
        val config = AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to hash, "PUBLIC_URL" to "https://kotobaya.example.net"))
        val app = composeApp(db, url, config, eventsGuardMs = 200)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        app.jobs.start(scope)
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { kotobayaModule(app.deps) }
        server.start(wait = false)
        val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val base = "http://127.0.0.1:$port/api/v1"

            val login = http.send(
                HttpRequest.newBuilder(URI("$base/auth/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf("password" to password)))).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val session = login.headers().allValues("set-cookie").single().substringAfter("kotobaya_session=").substringBefore(';')

            fun send(method: String, path: String, body: Any? = null): HttpResponse<String> {
                val b = HttpRequest.newBuilder(URI("$base$path"))
                    .header("Cookie", "kotobaya_session=$session").header("X-Kotobaya-Csrf", "1")
                    .method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody())
                if (body != null) b.header("Content-Type", "application/json")
                return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            }
            fun stream(bearer: String? = null, query: String = ""): BufferedReader {
                val b = HttpRequest.newBuilder(URI("$base/events$query"))
                if (bearer != null) b.header("Authorization", "Bearer $bearer") else b.header("Cookie", "kotobaya_session=$session")
                val r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
                assertEquals(200, r.statusCode())
                return r.body().bufferedReader().also { reader ->
                    // Wait for `hello` so the subscription exists before anything else happens.
                    assertTrue(generateSequence { reader.readLine() }.take(3).any { it == "event: hello" })
                }
            }
            /** Lines until the server closes the stream; fails if it is still open after [seconds]. */
            fun readToEnd(r: BufferedReader, seconds: Long): List<String> =
                CompletableFuture.supplyAsync { generateSequence { r.readLine() }.toList() }.get(seconds, TimeUnit.SECONDS)

            // A paired phone with an open stream.
            val code = json.readTree(send("POST", "/devices/pairings").body())["code"].asText()
            val deviceId = Uuid7.generate()
            val paired = send("POST", "/devices/pair", mapOf("code" to code, "device_id" to deviceId.toString(), "name" to "Pixel 9"))
            assertEquals(201, paired.statusCode(), paired.body())
            val phoneToken = json.readTree(paired.body())["token"].asText()
            val phone = stream(bearer = phoneToken)
            val observer = stream(query = "?types=device.revoked")

            assertEquals(204, send("DELETE", "/devices/$deviceId").statusCode())
            readToEnd(phone, 5) // the phone's stream ends (hub.closeToken on revocation)

            val seen = CompletableFuture.supplyAsync {
                generateSequence { observer.readLine() }.dropWhile { it != "event: device.revoked" }.drop(1).first()
            }.get(5, TimeUnit.SECONDS)
            val event = json.readTree(seen.removePrefix("data: "))
            assertEquals("device.revoked", event["type"].asText())
            assertEquals(deviceId.toString(), event["data"]["device_id"].asText())
            assertEquals("Pixel 9", event["data"]["name"].asText())

            // A personal token that expires while its stream is open: the stream guard ends it.
            val token = Tokens.generate(TokenKind.PERSONAL)
            db.txBlocking {
                it.execute(
                    "insert into app.api_token (id, name, kind, token_hash, token_prefix, scopes, expires_at) " +
                        "values (?, 'short', 'personal', ?, ?, array['read'], now() + interval '1500 milliseconds')",
                    UUID.randomUUID(), Tokens.hash(token), Tokens.prefix(token),
                )
            }
            val expiring = stream(bearer = token)
            val rest = readToEnd(expiring, 6)
            assertNull(rest.firstOrNull { it.startsWith("event: ") && it != "event: hello" }, rest.toString())

            // And the expired token is refused on ordinary requests too.
            assertEquals(401, http.send(
                HttpRequest.newBuilder(URI("$base/auth/session")).header("Authorization", "Bearer $token").build(),
                HttpResponse.BodyHandlers.ofString(),
            ).statusCode())
        } finally {
            app.jobs.close()
            scope.cancel()
            server.stop(100, 500)
            db.close()
        }
    }
}
