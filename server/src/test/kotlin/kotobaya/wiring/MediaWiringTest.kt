package kotobaya.wiring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotobaya.auth.AuthConfig
import kotobaya.auth.PasswordHasher
import kotobaya.blobs.InMemoryBlobStore
import kotobaya.server.Config
import kotobaya.server.ConfigException
import kotobaya.server.Database
import kotobaya.server.Migrations
import kotobaya.server.TestPg
import kotobaya.server.Uuid7
import kotobaya.server.composeApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W-5: the composed app builds the media-publish runner (the only claimer of that internal kind); PARSER_URL is optional. */
class MediaWiringTest {
    private val hash = PasswordHasher.hash("pw", PasswordHasher.Params(1024, 1, 1))

    @Test
    fun theMediaPublishRunnerClaimsItsInternalKind() {
        val url = TestPg.freshDatabaseUrl()
        val db = Database.connect(url)
        Migrations.migrate(db.dataSource)
        val app = composeApp(
            db, url, AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to hash)),
            blobStore = InMemoryBlobStore(),
        )
        val runner = assertNotNull(app.mediaPublish)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val child = Uuid7.generate()
        try {
            // A malformed payload: a live runner claims the child and ends it `failed`; unclaimed it stays `queued`.
            db.txBlocking {
                it.execute("insert into app.job (id, kind, payload, max_attempts) values (?, 'media-publish', '{}'::jsonb, 1)", child)
            }
            runner.start(scope)
            val end = System.currentTimeMillis() + 30_000
            fun status() = db.txBlocking { it.fetchValue("select status from app.job where id = ?", child) as String }
            while (status() == "queued" && System.currentTimeMillis() < end) Thread.sleep(100)
            assertTrue(status() != "queued", "media-publish child was never claimed")
        } finally {
            scope.cancel()
            app.jobs.close()
            db.close()
        }
    }

    @Test
    fun withoutABlobStoreThereIsNoPublishRunner() {
        val url = TestPg.freshDatabaseUrl()
        val db = Database.connect(url)
        Migrations.migrate(db.dataSource)
        try {
            assertNull(composeApp(db, url, AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to hash))).mediaPublish)
        } finally {
            db.close()
        }
    }

    @Test
    fun parserUrlIsOptional() {
        val base = mapOf(
            "DATABASE_URL" to "jdbc:postgresql://x/y", "S3_ENDPOINT" to "http://s3", "S3_BUCKET" to "b",
            "S3_ACCESS_KEY" to "a", "S3_SECRET_KEY" to "s",
        )
        assertNull(Config.fromEnv(base).parserUrl)
        assertEquals("http://worker:8082", Config.fromEnv(base + ("PARSER_URL" to "http://worker:8082")).parserUrl)
        assertFailsWith<ConfigException> { Config.fromEnv(base - "S3_BUCKET") }
    }
}
