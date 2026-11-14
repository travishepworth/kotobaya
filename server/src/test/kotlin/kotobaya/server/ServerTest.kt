package kotobaya.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {
    @Test
    fun flywayAppliesAllContractMigrationsAndReadyzIsOkOnceStartupFinished() = testApplication {
        val db = TestPg.freshDatabaseUrl().let { Database.connect(it) }
        val versions = java.io.File("../contracts/db").listFiles().orEmpty()
            .mapNotNull { Regex("""V(\d+)__.+\.sql""").matchEntire(it.name)?.groupValues?.get(1)?.toInt() }
        assertTrue(versions.isNotEmpty())
        assertEquals(versions.size, Migrations.migrate(db.dataSource))
        val applied = db.dataSource.connection.use { c ->
            c.createStatement().executeQuery("select max(version::int) from flyway_schema_history where success").use { r -> r.next(); r.getInt(1) }
        }
        assertEquals(versions.max(), applied)
        val startup = StartupSequence(db)
        application { kotobayaModule(AppDeps(db, startup = startup)) }
        // not ready until the startup sequence has committed
        assertEquals(503, client.get("/readyz").status.value)
        assertEquals(200, client.get("/healthz").status.value)
        startup.runUntilDone()
        assertEquals(200, client.get("/readyz").status.value)
        val health = json.readTree(client.get("/api/v1/health").bodyAsText())
        // F-16: the build-provided version (Gradle passes it as a system property), never a literal.
        assertEquals(System.getProperty("kotobaya.expectedVersion"), health["version"].asText())
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

        val requests = LogCapture("kotobaya.request")
        val app = LogCapture("kotobaya.app")

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
        requests.close()
        app.close()

        val lines = requests.lines() // parses: every line is JSON
        val notes = lines.single { it["path"]?.asText() == "/api/v1/notes" }
        assertEquals(501, notes["status"].asInt())
        assertEquals("WARN", notes["level"].asText()) // 5xx
        assertEquals("tok-1", notes["token.id"].asText())
        assertEquals("laptop", notes["token.name"].asText())
        assertNotNull(notes["request_id"])
        assertNotNull(notes["duration_ms"])
        // OBS-1s: StatusPages logs the unhandled exception with its stack trace and the request id.
        val crash = app.lines().single { it["message"].asText().startsWith("unhandled error") }
        assertEquals("ERROR", crash["level"].asText())
        assertEquals(failure.headers["X-Request-Id"], crash["request_id"].asText())
        assertTrue(crash["stack_trace"].asText().contains("IllegalStateException: boom"))
    }

    /** OBS-1s: a well-formed inbound X-Request-Id is kept and echoed; a malformed one is replaced; both reach the log. */
    @Test
    fun requestIdIsAcceptedOrMintedAndEchoed() = testApplication {
        val db = TestPg.migratedDb()
        application { kotobayaModule(AppDeps(db, authenticator = Authenticator { _, _ -> TokenInfo("tok-2", "phone", "0193a1b2-0000-7000-8000-00000000000a") })) }
        val requests = LogCapture("kotobaya.request")

        val given = client.get("/api/v1/notes") { header("X-Request-Id", "web-1234.abc") }
        assertEquals("web-1234.abc", given.headers["X-Request-Id"])
        assertEquals("web-1234.abc", json.readTree(given.bodyAsText())["request_id"].asText())

        val bad = client.get("/api/v1/notes") { header("X-Request-Id", "evil\" injected=1 " + "x".repeat(200)) }
        val minted = bad.headers["X-Request-Id"]
        assertNotNull(minted)
        assertEquals(36, minted.length)

        val none = client.get("/api/v1/notes")
        assertEquals(36, none.headers["X-Request-Id"]?.length)

        Thread.sleep(200)
        requests.close()
        val ids = requests.lines().map { it["request_id"].asText() }
        assertTrue("web-1234.abc" in ids)
        assertTrue(minted in ids)
        val line = requests.lines().first { it["request_id"].asText() == "web-1234.abc" }
        assertEquals("tok-2", line["token.id"].asText())
        assertEquals("0193a1b2-0000-7000-8000-00000000000a", line["device_id"].asText())
        assertEquals("GET", line["method"].asText())
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
