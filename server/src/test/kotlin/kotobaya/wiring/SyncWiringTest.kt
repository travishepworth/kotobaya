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
import kotobaya.contracts.sync.OpType
import kotobaya.server.Database
import kotobaya.server.Migrations
import kotobaya.server.TestPg
import kotobaya.server.Uuid7
import kotobaya.server.composeApp
import kotobaya.server.json
import kotobaya.server.kotobayaModule
import kotobaya.sync.SyncFixture
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W-2: notes, sync, decks and settings composed by `composeApp` (as `main` does), on Netty.
 * REST note create -> /sync/push review -> /sync/pull -> card cache; duplicate note from a second device merges.
 */
class SyncWiringTest {
    private val password = "wiring test password"
    private val hash = PasswordHasher.hash(password, PasswordHasher.Params(1024, 1, 1))
    private val vocabType = "00000000-0000-7000-8000-000000000101"

    @Test
    fun restNotePushedReviewPulledCardCacheUpdatedAndDuplicateNoteMerges() {
        val url = TestPg.freshDatabaseUrl()
        val db = Database.connect(url)
        Migrations.migrate(db.dataSource)
        // No log_epoch row: the composed startup sequence (SyncWiring.startup) must create it.
        val app = composeApp(db, url, AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to hash, "PUBLIC_URL" to "https://kotobaya.example.net")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        app.jobs.start(scope)
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { kotobayaModule(app.deps) }
        server.start(wait = false)
        val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val root = "http://127.0.0.1:$port"
            val base = "$root/api/v1"
            fun send(method: String, path: String, body: Any? = null, bearer: String? = null, cookie: String? = null): HttpResponse<String> {
                val b = HttpRequest.newBuilder(URI(if (path.startsWith("/readyz")) "$root$path" else "$base$path"))
                    .method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody())
                if (body != null) b.header("Content-Type", "application/json")
                bearer?.let { b.header("Authorization", "Bearer $it") }
                cookie?.let { b.header("Cookie", "kotobaya_session=$it").header("X-Kotobaya-Csrf", "1") }
                return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            }
            fun count(sql: String): Long = db.txBlocking { (it.fetchValue(sql) as Number).toLong() }

            // Readiness waits for the startup sequence (epoch + scheduler replay).
            val deadline = System.currentTimeMillis() + 20_000
            while (send("GET", "/readyz").statusCode() != 200 && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals(200, send("GET", "/readyz").statusCode())
            assertEquals(1, count("select count(*) from app.server_state where key = 'core_scheduler_version'"), "startup replay ran")
            assertEquals(1, count("select count(*) from app.server_state where key = 'log_epoch'"))

            val login = http.send(
                HttpRequest.newBuilder(URI("$base/auth/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf("password" to password)))).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val session = login.headers().allValues("set-cookie").single().substringAfter("kotobaya_session=").substringBefore(';')

            fun pair(name: String): Pair<String, String> {
                val code = json.readTree(send("POST", "/devices/pairings", cookie = session).body())["code"].asText()
                val id = Uuid7.generate().toString()
                val r = send("POST", "/devices/pair", mapOf("code" to code, "device_id" to id, "name" to name))
                assertEquals(201, r.statusCode(), r.body())
                return id to json.readTree(r.body())["token"].asText()
            }
            val (phoneId, phone) = pair("phone")
            val (tabletId, tablet) = pair("tablet")

            db.txBlocking {
                it.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
                it.execute(
                    "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) " +
                        "values (1358280, '食べる', 'たべる', '[{\"text\":\"たべる\"}]', '[]', 1) on conflict do nothing",
                )
            }

            // REST note create through the shared OpApply.
            val created = send("POST", "/notes", mapOf("note_type_id" to vocabType, "entry_seq" to 1358280), cookie = session)
            assertEquals(201, created.statusCode(), created.body())
            val cardId = json.readTree(created.body())["cards"].first { it["kind"].asText() == "meaning" }["id"].asText()

            // Review pushed by the phone updates the card cache.
            val now = System.currentTimeMillis()
            val review = SyncFixture.op(
                OpType.REVIEW_LOGGED, phoneId, now,
                """{"id":"${Uuid7.generate(now)}","card_id":"$cardId","reviewed_at":$now,"utc_offset_min":540,"review_type":"learning","rating":3,"button":"good","duration_ms":4200}""",
            )
            val push = send("POST", "/sync/push", mapOf("ops" to listOf(review)), bearer = phone)
            assertEquals(200, push.statusCode(), push.body())
            assertEquals("accepted", json.readTree(push.body())["results"][0]["status"].asText(), push.body())
            assertEquals(1, count("select reps from app.card where id = '$cardId'"))
            assertEquals(0, count("select count(*) from app.card where id = '$cardId' and state = 'new'"))

            // The tablet pulls the REST note's ops and the review.
            val pull = json.readTree(send("GET", "/sync/pull?since=0", bearer = tablet).body())
            val types = pull["ops"].map { it["op_type"].asText() }
            assertTrue("note.created" in types && "card.created" in types && "review.logged" in types, types.toString())

            // A second device pushes a duplicate note (same entry): merged, not 503.
            val dup = SyncFixture.op(
                OpType.NOTE_CREATED, tabletId, now + 1,
                """{"id":"${Uuid7.generate(now + 1)}","note_type_id":"$vocabType","entry_seq":1358280}""",
            )
            val dupPush = send("POST", "/sync/push", mapOf("ops" to listOf(dup)), bearer = tablet)
            assertEquals(200, dupPush.statusCode(), dupPush.body())
            assertEquals("accepted", json.readTree(dupPush.body())["results"][0]["status"].asText(), dupPush.body())
            assertEquals(1, count("select count(*) from app.note where entry_seq = 1358280 and deleted_at is null"), "one live note after the merge")
            assertTrue(count("select count(*) from app.change where caused_by_op_id is not null") > 0, "the merge is a server-emitted op")

            // Scopes still enforced.
            assertEquals(401, send("POST", "/sync/push", mapOf("ops" to listOf(review))).statusCode())
            val ro = json.readTree(send("POST", "/tokens", mapOf("name" to "ro", "scopes" to listOf("read")), cookie = session).body())["token"].asText()
            assertEquals(403, send("POST", "/notes", mapOf("note_type_id" to vocabType), bearer = ro).statusCode())
            assertEquals(200, send("GET", "/notes", bearer = ro).statusCode())
            val wd = json.readTree(
                send("POST", "/tokens", mapOf("name" to "w", "scopes" to listOf("read", "write"), "with_device" to true), cookie = session).body(),
            )
            val review2 = SyncFixture.op(
                OpType.REVIEW_LOGGED, wd["info"]["device_id"].asText(), now + 2,
                """{"id":"${Uuid7.generate(now + 2)}","card_id":"$cardId","reviewed_at":${now + 2},"utc_offset_min":540,"review_type":"learning","rating":3,"button":"good","duration_ms":1}""",
            )
            val scoped = json.readTree(send("POST", "/sync/push", mapOf("ops" to listOf(review2)), bearer = wd["token"].asText()).body())
            assertEquals("insufficient_scope", scoped["results"][0]["error"]["code"].asText(), scoped.toString())
        } finally {
            app.jobs.close()
            scope.cancel()
            server.stop(100, 500)
            db.close()
        }
    }
}
