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
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T207c: the jpdb history import through the composed app (`composeApp`, as `main` does). The worker's half is played
 * by SQL: it inserts the internal `jpdb-history-apply` child with the fixture plan (T207a's `ImportPlan.to_json()`),
 * the server's runner (`app.imports.start`) applies it, and the worker then ends the public parent with the child's
 * result. The API must show the parent's counters and never the child.
 */
class JpdbImportWiringTest {
    private val password = "wiring test password"
    private val hash = PasswordHasher.hash(password, PasswordHasher.Params(1024, 1, 1))

    private val sample by lazy {
        val dir = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "server/src/test/kotlin/kotobaya/imports") }.firstOrNull { it.isDirectory }
            ?: File(System.getProperty("user.dir"), "src/test/kotlin/kotobaya/imports")
        json.readTree(File(dir, "reviews-sample.plan.json"))
    }

    @Test
    fun theInternalChildIsAppliedByTheRealAppAndOnlyTheParentIsVisible() {
        val url = TestPg.freshDatabaseUrl()
        val db = Database.connect(url)
        Migrations.migrate(db.dataSource)
        val app = composeApp(db, url, AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to hash, "PUBLIC_URL" to "https://kotobaya.example.net")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        app.jobs.start(scope)
        app.imports.start(scope)
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { kotobayaModule(app.deps) }
        server.start(wait = false)
        val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val root = "http://127.0.0.1:$port"
            fun send(method: String, path: String, cookie: String? = null): HttpResponse<String> {
                val b = HttpRequest.newBuilder(URI(if (path.startsWith("/readyz")) "$root$path" else "$root/api/v1$path"))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                cookie?.let { b.header("Cookie", "kotobaya_session=$it").header("X-Kotobaya-Csrf", "1") }
                return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            }
            fun until(what: String, ms: Long = 30_000, cond: () -> Boolean) {
                val end = System.currentTimeMillis() + ms
                while (!cond() && System.currentTimeMillis() < end) Thread.sleep(100)
                assertTrue(cond(), what)
            }
            until("ready") { send("GET", "/readyz").statusCode() == 200 }
            val login = http.send(
                HttpRequest.newBuilder(URI("$root/api/v1/auth/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf("password" to password)))).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val session = login.headers().allValues("set-cookie").single().substringAfter("kotobaya_session=").substringBefore(';')

            // The worker's step 1-2: a running public parent and the internal child holding the plan.
            val parent = Uuid7.generate()
            val child = Uuid7.generate()
            db.txBlocking { dsl ->
                dsl.execute(
                    "insert into app.job (id, kind, status, payload, locked_by, heartbeat_at, started_at, max_attempts) " +
                        "values (?, 'jpdb-history-import', 'running', '{}'::jsonb, 'worker-1', now(), now(), 3)",
                    parent,
                )
                dsl.execute(
                    "insert into app.job (id, kind, payload, dedupe_key, max_attempts) values (?, 'jpdb-history-apply', ?::jsonb, ?, 1)",
                    child, json.writeValueAsString(mapOf("parent_job_id" to parent.toString(), "plan" to sample["plan"])), "jpdb-history-apply:$parent",
                )
            }
            fun childRow() = db.txBlocking { it.fetchOne("select status, result::text as result, payload::text as payload from app.job where id = ?", child)!!.intoMap() }
            until("child applied") { childRow()["status"] in setOf("succeeded", "failed") }
            val c = childRow()
            assertEquals("succeeded", c["status"], c.toString())
            assertFalse(json.readTree(c["payload"] as String).has("plan"))
            val result = json.readTree(c["result"] as String)

            // The worker's step 4: the parent ends with the child's result.
            db.txBlocking {
                it.execute(
                    "update app.job set status = 'succeeded', result = ?::jsonb, progress = 1, finished_at = now(), locked_by = null where id = ?",
                    c["result"], parent,
                )
            }
            val got = json.readTree(send("GET", "/jobs/$parent", session).body())
            assertEquals("jpdb-history-import", got["kind"].asText())
            assertEquals("succeeded", got["status"].asText())
            val expected = sample["expected_result_empty_db"]
            expected.fieldNames().forEach { k -> assertEquals(expected[k], got["result"][k], k) }
            assertEquals(13, got["result"]["reviews_imported"].asInt())
            assertEquals(0, got["result"]["reviews_id_collisions"].asInt())
            assertEquals(result["notes_created"], got["result"]["notes_created"])
            assertEquals(13L, (db.txBlocking { it.fetchValue("select count(*) from app.review_log where import_source = 'jpdb'") } as Number).toLong())

            // The child never shows.
            assertEquals(404, send("GET", "/jobs/$child", session).statusCode())
            assertEquals(404, send("POST", "/jobs/$child/cancel", session).statusCode())
            val listed = json.readTree(send("GET", "/jobs", session).body())["items"].map { it["id"].asText() }
            assertEquals(listOf(parent.toString()), listed)
        } finally {
            server.stop(100, 500)
            app.jobs.close()
            scope.cancel()
            db.close()
        }
    }
}
