package kotobaya.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {
    @Test
    fun flywayAppliesV1AndReadyzIsOkOnceStartupFinished() = testApplication {
        val db = TestPg.freshDatabaseUrl().let { Database.connect(it) }
        assertEquals(1, Migrations.migrate(db.dataSource))
        val startup = StartupSequence(db)
        application { kotobayaModule(AppDeps(db, startup = startup)) }
        // not ready until the startup sequence has committed
        assertEquals(503, client.get("/readyz").status.value)
        assertEquals(200, client.get("/healthz").status.value)
        startup.runUntilDone()
        assertEquals(200, client.get("/readyz").status.value)
        assertEquals(200, client.get("/api/v1/health").status.value)
        val meta = json.readTree(client.get("/api/v1/meta").bodyAsText())
        assertEquals(0, meta["high_water_seq"].asInt())
        assertEquals(36, meta["log_epoch"].asText().length)
        assertTrue(client.get("/metrics").bodyAsText().contains("jvm_"))
        db.close()
    }

    @Test
    fun readyzIs503WhenPostgresIsUnreachable() = testApplication {
        val db = TestPg.deadDb()
        application { kotobayaModule(AppDeps(db)) }
        val ready = client.get("/readyz")
        assertEquals(503, ready.status.value)
        assertEquals("application/problem+json", ready.contentType()?.withoutParameters().toString())
        assertEquals(503, client.get("/api/v1/health").status.value)
        assertEquals(200, client.get("/healthz").status.value) // liveness does not depend on Postgres
        db.close()
    }

    @Test
    fun readyzTakesExtraChecks() = testApplication {
        val db = TestPg.migratedDb()
        val gate = object : ReadinessCheck {
            @Volatile var ok = false
            override val name = "replay"
            override suspend fun check(): String? = if (ok) null else "not finished"
        }
        application { kotobayaModule(AppDeps(db, extraReadiness = listOf(gate))) }
        assertEquals(503, client.get("/readyz").status.value)
        gate.ok = true
        assertEquals(200, client.get("/readyz").status.value)
        db.close()
    }

    @Test
    fun errorsAreProblemJsonWithRequestIdAndLogsAreJson() = testApplication {
        val db = TestPg.migratedDb()
        val boom = Authenticator { call: ApplicationCall, _ ->
            if (call.request.local.uri.endsWith("/jobs")) throw IllegalStateException("boom")
            TokenInfo("tok-1", "laptop")
        }
        application { kotobayaModule(AppDeps(db, authenticator = boom)) }

        val logger = LoggerFactory.getLogger("kotobaya.request") as Logger
        val captured = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }

        val notFound = client.get("/api/v1/nope")
        assertEquals(404, notFound.status.value)
        assertEquals("application/problem+json", notFound.contentType()?.withoutParameters().toString())
        val nf = json.readTree(notFound.bodyAsText())
        assertEquals("urn:kotobaya:problem:not_found", nf["type"].asText())
        assertEquals(notFound.headers["X-Request-Id"], nf["request_id"].asText())

        val failure = client.get("/api/v1/jobs")
        assertEquals(500, failure.status.value)
        val f = json.readTree(failure.bodyAsText())
        assertEquals("internal", f["code"].asText())
        assertEquals(failure.headers["X-Request-Id"], f["request_id"].asText())
        assertTrue(!failure.bodyAsText().contains("boom"), "internal details must not leak")

        client.get("/api/v1/notes") // 501 stub, authenticated as tok-1
        // let the ResponseSent hook run
        Thread.sleep(200)
        logger.detachAppender(captured)

        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val encoder = (root.getAppender("STDOUT") as ConsoleAppender<ILoggingEvent>).encoder
        val lines = captured.list.map { json.readTree(encoder.encode(it)) } // parses: every line is JSON
        val notes = lines.single { it["path"]?.asText() == "/api/v1/notes" }
        assertEquals(501, notes["status"].asInt())
        assertEquals("tok-1", notes["token.id"].asText())
        assertEquals("laptop", notes["token.name"].asText())
        assertNotNull(notes["request_id"])
    }

    @Test
    fun migrateCommandReturnsZeroOnSuccessAndNonZeroOnFailure() {
        val url = TestPg.freshDatabaseUrl()
        assertEquals(0, migrateCommand(mapOf("DATABASE_URL" to url)))
        assertEquals(0, migrateCommand(mapOf("DATABASE_URL" to url))) // idempotent
        assertEquals(1, migrateCommand(mapOf("DATABASE_URL" to "jdbc:postgresql://127.0.0.1:1/none?user=a&password=b&connectTimeout=1")))
        assertEquals(1, migrateCommand(emptyMap()))
    }

    @Test
    fun configUsesTheFixedEnvNames() {
        val c = Config.fromEnv(
            mapOf(
                "DATABASE_URL" to "jdbc:postgresql://h/d", "S3_ENDPOINT" to "http://s3", "S3_BUCKET" to "b",
                "S3_ACCESS_KEY" to "a", "S3_SECRET_KEY" to "s", "S3_REGION" to "r", "MIGRATE_ON_START" to "true",
            ),
        )
        assertEquals("jdbc:postgresql://h/d", c.databaseUrl)
        assertEquals("r", c.s3Region)
        assertTrue(c.migrateOnStart)
        assertTrue(runCatching { Config.fromEnv(emptyMap()) }.exceptionOrNull() is ConfigException)
    }
}
