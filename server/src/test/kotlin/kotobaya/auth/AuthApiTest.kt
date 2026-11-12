package kotobaya.auth

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotobaya.server.AppDeps
import kotobaya.server.Database
import kotobaya.server.TestPg
import kotobaya.server.json
import kotobaya.server.kotobayaModule
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthApiTest {
    private val password = "correct horse battery staple"
    private val totpSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // RFC 6238 key "12345678901234567890"
    private val totpGen = Totp(Totp.decodeBase32(totpSecret))
    private val cheapHash = PasswordHasher.hash(password, PasswordHasher.Params(1024, 1, 1))
    private val now = AtomicReference(Instant.parse("2026-11-12T09:30:15Z"))
    private val nanos = AtomicLong(0)
    private val db: Database = TestPg.migratedDb()
    private lateinit var auth: Auth

    @AfterTest
    fun close() = db.close()

    private fun app(totp: Boolean = false, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val env = buildMap {
            put("AUTH_PASSWORD_HASH", cheapHash)
            if (totp) put("AUTH_TOTP_SECRET", totpSecret)
        }
        val clock = { now.get() }
        auth = Auth(
            db, AuthConfig.fromEnv(env), clock,
            validator = TokenValidator(db, clock, nanoTime = { nanos.get() }),
            clientAddress = { it.request.headers["X-Test-Client"] ?: "client-a" },
        )
        application { kotobayaModule(AppDeps(db, authenticator = auth.authenticator, handlers = auth.handlers)) }
        block()
    }

    private suspend fun HttpClient.login(body: String, client: String = "client-a"): HttpResponse =
        post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            header("X-Test-Client", client)
            setBody(body)
        }

    private fun pw(p: String = password, code: String? = null) =
        json.writeValueAsString(buildMap { put("password", p); code?.let { put("totp_code", it) } })

    private fun codeNow(offsetSteps: Long = 0) = totpGen.codeAt(totpGen.step(now.get()) + offsetSteps)

    private fun HttpResponse.problem(): JsonNode = kotlinx.coroutines.runBlocking { json.readTree(bodyAsText()) }

    private fun HttpResponse.sessionToken(): String =
        headers.getAll(HttpHeaders.SetCookie)!!.single().substringAfter("kotobaya_session=").substringBefore(';')

    private suspend fun HttpClient.whoami(cookie: String? = null, bearer: String? = null) = get("/api/v1/auth/session") {
        cookie?.let { header(HttpHeaders.Cookie, "kotobaya_session=$it") }
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private fun assertProblem(r: HttpResponse, status: Int, code: String) {
        assertEquals(status, r.status.value)
        assertEquals("application/problem+json", r.contentType()?.withoutParameters().toString())
        assertEquals(code, r.problem()["code"].asText())
    }

    // ---------------------------------------------------------------- login matrix

    @Test
    fun loginSetsSessionCookieWithContractAttributesAndStoresOnlyTheHash() = app {
        val r = client.login(pw())
        assertEquals(200, r.status.value)
        val cookie = r.headers.getAll(HttpHeaders.SetCookie)!!.single()
        val token = r.sessionToken()
        assertTrue(Regex("^kbs_[A-Za-z0-9]{40}$").matches(token), token)
        val attrs = cookie.split(';').map { it.trim() }.drop(1).toSet()
        assertEquals(setOf("Path=/api", "HttpOnly", "Secure", "SameSite=Strict", "Max-Age=2592000"), attrs)

        val body = json.readTree(r.bodyAsText())
        assertFalse(r.bodyAsText().contains(token), "token string is never in the body")
        assertEquals("session", body["kind"].asText())
        assertEquals("web session 2026-11-12", body["name"].asText())
        assertEquals(listOf("read", "write", "review", "import", "admin"), body["scopes"].map { it.asText() })
        assertTrue(body["device_id"].isNull)
        assertEquals("2026-12-12T09:30:15.000Z", body["expires_at"].asText())
        assertFalse(body["totp_enabled"].asBoolean())

        val row = db.txBlocking { it.fetchOne("select * from app.api_token where id = ?::uuid", body["token_id"].asText())!! }
        assertEquals(Tokens.hash(token), row.get("token_hash", String::class.java))
        assertEquals(token.take(8), row.get("token_prefix", String::class.java))
        assertEquals("session", row.get("kind", String::class.java))
        assertFalse(row.intoMap().values.any { it?.toString()?.contains(token.substring(4)) == true }, "plaintext token stored")

        val who = client.whoami(cookie = token)
        assertEquals(200, who.status.value)
        assertEquals(body, json.readTree(who.bodyAsText()))
    }

    @Test
    fun wrongPasswordIsInvalidCredentials() = app {
        assertProblem(client.login(pw("nope")), 401, "invalid_credentials")
        assertNull(client.login(pw("nope")).headers[HttpHeaders.SetCookie])
    }

    @Test
    fun missingTotpIsTotpRequiredWithoutCheckingThePassword() = app(totp = true) {
        val right = client.login(pw())
        val wrong = client.login(pw("wrong password"))
        assertProblem(right, 401, "totp_required")
        assertProblem(wrong, 401, "totp_required")
        // Identical apart from the per-request id: the response does not reveal the password was right.
        fun strip(r: HttpResponse) = (r.problem() as com.fasterxml.jackson.databind.node.ObjectNode).apply { remove("request_id") }
        assertEquals(strip(right), strip(wrong))
        // ...and it is not counted as a guess.
        repeat(10) { client.login(pw("wrong")) }
        assertEquals(200, client.login(pw(code = codeNow())).status.value)
    }

    @Test
    fun wrongTotpGivesTheSameResponseAsWrongPassword() = app(totp = true) {
        val badCode = client.login(pw(code = if (codeNow() == "000000") "111111" else "000000"))
        val badPw = client.login(pw("wrong", code = codeNow()))
        assertProblem(badCode, 401, "invalid_credentials")
        assertProblem(badPw, 401, "invalid_credentials")
        assertEquals(badCode.problem()["detail"], badPw.problem()["detail"])
        assertEquals(200, client.login(pw(code = codeNow())).status.value, "wrong password did not burn the code")
    }

    @Test
    fun expiredTotpStepIsRejected() = app(totp = true) {
        assertProblem(client.login(pw(code = codeNow(-2))), 401, "invalid_credentials")
        assertProblem(client.login(pw(code = codeNow(+1))), 401, "invalid_credentials")
    }

    @Test
    fun previousStepCodeIsAccepted() = app(totp = true) {
        val r = client.login(pw(code = codeNow(-1)))
        assertEquals(200, r.status.value)
        assertTrue(json.readTree(r.bodyAsText())["totp_enabled"].asBoolean())
    }

    @Test
    fun totpCodeIsAcceptedOnlyOnce() = app(totp = true) {
        val code = codeNow()
        assertEquals(200, client.login(pw(code = code)).status.value)
        assertProblem(client.login(pw(code = code)), 401, "invalid_credentials")
        assertProblem(client.login(pw(code = codeNow(-1))), 401, "invalid_credentials") // older step after newer
        now.set(now.get().plusSeconds(30))
        assertEquals(200, client.login(pw(code = codeNow())).status.value)
    }

    @Test
    fun loginBodyIsValidated() = app {
        assertProblem(client.login("""{"password":"x","user":"me"}"""), 422, "validation_failed")
        assertProblem(client.login("""{"password":""}"""), 422, "validation_failed")
        assertProblem(client.login("""{"password":"x","totp_code":"12345"}"""), 422, "validation_failed")
        assertProblem(client.login("""{"""), 400, "invalid_request")
        val wrongType = client.post("/api/v1/auth/login") { contentType(ContentType.Text.Plain); setBody(pw()) }
        assertProblem(wrongType, 415, "unsupported_media_type")
    }

    // ---------------------------------------------------------------- brute force

    @Test
    fun bruteForceLimitTriggersAndRecovers() = app {
        repeat(5) { assertProblem(client.login(pw("guess$it")), 401, "invalid_credentials") }
        val limited = client.login(pw()) // correct credentials are refused too
        assertProblem(limited, 429, "rate_limited")
        assertEquals((15 * 60).toString(), limited.headers[HttpHeaders.RetryAfter])
        assertNull(limited.headers[HttpHeaders.SetCookie])
        assertEquals(200, client.login(pw(), client = "client-b").status.value, "per address")

        now.set(now.get().plus(Duration.ofMinutes(15)))
        assertEquals(200, client.login(pw()).status.value, "recovers once the window passes")
    }

    // ---------------------------------------------------------------- sessions and revocation

    @Test
    fun logoutTakesEffectOnTheVeryNextRequest() = app {
        val token = client.login(pw()).sessionToken()
        assertEquals(200, client.whoami(cookie = token).status.value) // now cached
        val out = client.post("/api/v1/auth/logout") {
            header(HttpHeaders.Cookie, "kotobaya_session=$token")
            header("X-Kotobaya-Csrf", "1")
        }
        assertEquals(204, out.status.value)
        assertEquals("kotobaya_session=; Path=/api; Max-Age=0", out.headers[HttpHeaders.SetCookie])
        assertProblem(client.whoami(cookie = token), 401, "unauthenticated")
        val revoked = db.txBlocking { it.fetchValue("select revoked_at from app.api_token where token_hash = ?", Tokens.hash(token)) }
        assertNotNull(revoked)
    }

    @Test
    fun revocationThroughTheValidatorBypassesTheCache() = app {
        val token = client.login(pw()).sessionToken()
        val id = UUID.fromString(json.readTree(client.whoami(cookie = token).bodyAsText())["token_id"].asText())
        assertTrue(auth.validator.revoke(id))
        assertProblem(client.whoami(cookie = token), 401, "unauthenticated")
        assertFalse(auth.validator.revoke(id), "second revoke is a no-op")
    }

    @Test
    fun cacheLivesAtMostFiveSeconds() = app {
        val token = client.login(pw()).sessionToken()
        assertEquals(200, client.whoami(cookie = token).status.value)
        // Revoked behind the validator's back (another replica, a manual SQL fix): visible within 5 s.
        db.txBlocking { it.execute("update app.api_token set revoked_at = now() where token_hash = ?", Tokens.hash(token)) }
        nanos.addAndGet(4_999_000_000)
        assertEquals(200, client.whoami(cookie = token).status.value)
        nanos.addAndGet(1_000_000)
        assertProblem(client.whoami(cookie = token), 401, "unauthenticated")
    }

    @Test
    fun expiredSessionIsRejectedEvenWhenCached() = app {
        val token = client.login(pw()).sessionToken()
        assertEquals(200, client.whoami(cookie = token).status.value)
        now.set(now.get().plus(Auth.SESSION_LIFETIME))
        assertProblem(client.whoami(cookie = token), 401, "unauthenticated")
    }

    @Test
    fun tokenOfARevokedDeviceIsRejected() = app {
        val (deviceId, token) = insertDeviceToken()
        assertEquals(200, client.whoami(bearer = token).status.value)
        db.txBlocking { it.execute("update app.device set revoked_at = now() where id = ?", deviceId) }
        auth.validator.invalidateDevice(deviceId)
        assertProblem(client.whoami(bearer = token), 401, "unauthenticated")
    }

    @Test
    fun missingUnknownAndMalformedCredentialsAre401() = app {
        assertProblem(client.whoami(), 401, "unauthenticated")
        assertProblem(client.whoami(cookie = Tokens.generate(TokenKind.SESSION)), 401, "unauthenticated")
        assertProblem(client.whoami(bearer = "not-a-token"), 401, "unauthenticated")
        assertProblem(client.get("/api/v1/auth/session") { header(HttpHeaders.Authorization, "Basic Zm9vOmJhcg==") }, 401, "unauthenticated")
    }

    @Test
    fun authorizationHeaderWinsOverTheCookie() = app {
        val session = client.login(pw()).sessionToken()
        val (_, device) = insertDeviceToken()
        // valid cookie, invalid header: the header is used, so 401
        assertProblem(client.whoami(cookie = session, bearer = Tokens.generate(TokenKind.PERSONAL)), 401, "unauthenticated")
        // invalid cookie, valid header: the header's principal
        val r = client.whoami(cookie = "garbage", bearer = device)
        assertEquals(200, r.status.value)
        assertEquals("device", json.readTree(r.bodyAsText())["kind"].asText())
    }

    @Test
    fun cookieWritesNeedTheCsrfHeaderButBearerWritesDoNot() = app {
        val token = client.login(pw()).sessionToken()
        val noCsrf = client.post("/api/v1/auth/logout") { header(HttpHeaders.Cookie, "kotobaya_session=$token") }
        assertProblem(noCsrf, 403, "csrf_required")
        assertEquals(200, client.whoami(cookie = token).status.value, "the refused logout revoked nothing")
        // A session token sent as a bearer header is not exposed to CSRF.
        assertEquals(204, client.post("/api/v1/auth/logout") { header(HttpHeaders.Authorization, "Bearer $token") }.status.value)
    }

    @Test
    fun logoutWithADeviceTokenIs422AndRevokesNothing() = app {
        val (_, device) = insertDeviceToken()
        val r = client.post("/api/v1/auth/logout") { header(HttpHeaders.Authorization, "Bearer $device") }
        assertProblem(r, 422, "validation_failed")
        assertEquals(200, client.whoami(bearer = device).status.value)
    }

    @Test
    fun requestLogCarriesTokenIdAndName() = app {
        val logger = LoggerFactory.getLogger("kotobaya.request") as Logger
        val captured = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        val login = client.login(pw())
        val id = json.readTree(login.bodyAsText())["token_id"].asText()
        client.whoami(cookie = login.sessionToken())
        Thread.sleep(200)
        logger.detachAppender(captured)
        val lines = captured.list.map { e -> e.argumentArray.associate { a -> a.toString().substringBefore('=') to a.toString().substringAfter('=') } }
        val paths = lines.filter { it["token.id"] == id && it["token.name"] == "web session 2026-11-12" }.map { it["path"] }
        assertEquals(listOf("/api/v1/auth/login", "/api/v1/auth/session"), paths)
    }

    private fun insertDeviceToken(): Pair<UUID, String> {
        val deviceId = UUID.randomUUID()
        val token = Tokens.generate(TokenKind.DEVICE)
        db.txBlocking { dsl ->
            dsl.execute("insert into app.device (id, name, kind) values (?, 'Pixel 9', 'android')", deviceId)
            dsl.execute(
                "insert into app.api_token (id, name, kind, token_hash, token_prefix, scopes, device_id, created_at) " +
                    "values (?, 'Pixel 9', 'device', ?, ?, array['read','write','review'], ?, ?::timestamptz)",
                UUID.randomUUID(), Tokens.hash(token), Tokens.prefix(token), deviceId, OffsetDateTime.ofInstant(now.get(), ZoneOffset.UTC),
            )
        }
        return deviceId to token
    }
}
