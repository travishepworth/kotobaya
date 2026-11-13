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
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** W-5a: putBlob/getBlob/queryBlobs, getDictRelease and refreshDictionary are served by the composed app, not 501. */
class BlobsDictWiringTest {
    private val hash = PasswordHasher.hash("pw", PasswordHasher.Params(1024, 1, 1))

    @Test
    fun blobAndDictOperationsAreWiredAndScoped() {
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
        fun mint(scopes: List<String>): String {
            val token = Tokens.generate(TokenKind.PERSONAL)
            db.txBlocking {
                it.execute(
                    "insert into app.api_token (id, name, kind, token_hash, token_prefix, scopes) values (?, 'w5a', 'personal', ?, ?, ?)",
                    UUID.randomUUID(), Tokens.hash(token), Tokens.prefix(token), scopes.toTypedArray(),
                )
            }
            return token
        }
        try {
            val root = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}/api/v1"
            fun send(method: String, path: String, token: String, body: ByteArray? = null, type: String = "application/json"): HttpResponse<ByteArray> {
                val b = HttpRequest.newBuilder(URI(root + path)).header("Authorization", "Bearer $token")
                    .method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
                if (body != null) b.header("Content-Type", type)
                return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            val full = mint(listOf("read", "write", "review", "import", "admin"))
            val readOnly = mint(listOf("read"))

            val bytes = "hello blob".toByteArray()
            val h = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(201, send("PUT", "/blobs/$h?kind=upload", full, bytes, "text/plain").statusCode())
            val got = send("GET", "/blobs/$h", full)
            assertEquals(200, got.statusCode())
            assertEquals(bytes.toList(), got.body().toList())
            val q = send("POST", "/blobs/query", full, json.writeValueAsBytes(mapOf("hashes" to listOf(h))))
            assertEquals(200, q.statusCode())
            assertEquals(h, json.readTree(q.body())["present"][0].asText())

            assertEquals(404, send("GET", "/dict/release", full).statusCode(), "no release loaded yet")
            db.txBlocking {
                it.execute(
                    "insert into ref.dict_release (id, version, built_at, is_current, artifacts) values (3, '2026.11.0', now(), true, ?::jsonb)",
                    json.writeValueAsString(
                        listOf(
                            mapOf(
                                "kind" to "full", "scope" to "top60k", "from_release" to null, "blob_hash" to h,
                                "size_bytes" to 10, "format_version" to 1, "uncompressed_size_bytes" to 20,
                            ),
                        ),
                    ),
                )
            }
            val rel = send("GET", "/dict/release", readOnly)
            assertEquals(200, rel.statusCode())
            val relJson = json.readTree(rel.body())
            assertEquals(3, relJson["id"].asInt())
            assertEquals(h, relJson["artifacts"][0]["blob_hash"].asText())

            // refreshDictionary needs the import scope; read alone is refused.
            assertEquals(403, send("POST", "/dict/refresh", readOnly).statusCode())
            val first = send("POST", "/dict/refresh", full)
            assertEquals(202, first.statusCode(), String(first.body()))
            val second = send("POST", "/dict/refresh", full, "{}".toByteArray())
            assertEquals(200, second.statusCode())
            assertEquals(json.readTree(first.body())["id"], json.readTree(second.body())["id"])
            assertEquals(1L, (db.txBlocking { it.fetchValue("select count(*) from app.job where kind = 'dict-refresh'") } as Number).toLong())
            assertNotEquals(501, send("POST", "/dict/refresh", full, """{"freq_lists":["jpdb"]}""".toByteArray()).statusCode())
        } finally {
            server.stop(100, 500)
            app.jobs.close()
            db.close()
        }
    }
}
