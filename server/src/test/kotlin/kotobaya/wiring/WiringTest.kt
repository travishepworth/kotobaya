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
import kotobaya.server.Database
import kotobaya.server.Migrations
import kotobaya.server.TestPg
import kotobaya.server.Uuid7
import kotobaya.server.composeApp
import kotobaya.server.json
import kotobaya.server.kotobayaModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real composition (`composeApp`, as `main` calls it) on Netty and a C.UTF-8 Postgres 17. */
class WiringTest {
    private val password = "wiring test password"
    private val hash = PasswordHasher.hash(password, PasswordHasher.Params(1024, 1, 1))

    @Test
    fun composedServerLogsInProtectsRoutesAndServesDictJobsAndEvents() {
        val url = TestPg.freshDatabaseUrl()
        val db = Database.connect(url)
        Migrations.migrate(db.dataSource)
        db.txBlocking {
            it.execute("insert into app.server_state (key, value, updated_at) values ('log_epoch', ?, now())", Uuid7.generate().toString())
        }
        val app = composeApp(db, url, AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to hash)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        app.jobs.start(scope)
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { kotobayaModule(app.deps) }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val base = "http://127.0.0.1:$port/api/v1"
            val http = HttpClient.newHttpClient()
            fun get(path: String, token: String? = null): HttpResponse<java.io.InputStream> {
                val b = HttpRequest.newBuilder(URI("$base$path"))
                token?.let { b.header("Authorization", "Bearer $it") }
                return http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
            }
            fun HttpResponse<java.io.InputStream>.text() = body().use { String(it.readAllBytes()) }

            // unauthenticated -> 401 on protected routes
            assertEquals(401, get("/jobs").also { it.body().close() }.statusCode())
            assertEquals(401, get("/dict/search?q=a").also { it.body().close() }.statusCode())

            // wrong password -> 401, right password -> session cookie
            fun login(p: String) = http.send(
                HttpRequest.newBuilder(URI("$base/auth/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf("password" to p)))).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, login("wrong").statusCode())
            val ok = login(password)
            assertEquals(200, ok.statusCode())
            val token = ok.headers().allValues("set-cookie").single().substringAfter("kotobaya_session=").substringBefore(';')

            assertEquals(200, get("/auth/session", token).also { it.body().close() }.statusCode())

            val search = get("/dict/search?q=a", token)
            assertEquals(200, search.statusCode())
            assertTrue(json.readTree(search.text()).has("items"))

            val jobs = get("/jobs", token)
            assertEquals(200, jobs.statusCode())
            assertTrue(json.readTree(jobs.text()).has("items"))

            val events = get("/events", token)
            assertEquals(200, events.statusCode())
            events.body().bufferedReader().use { r ->
                val lines = generateSequence { r.readLine() }.take(4).toList()
                assertTrue(lines.any { it == "event: hello" }, lines.toString())
            }
        } finally {
            app.jobs.close()
            scope.cancel()
            server.stop(100, 500)
            db.close()
        }
    }
}
