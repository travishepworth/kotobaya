package kotobaya.auth

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotobaya.server.AppDeps
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.TestPg
import kotobaya.server.json
import kotobaya.server.kotobayaModule
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.assertEquals

/** Records what auth publishes. */
class RecordingEvents : AuthEvents {
    val deviceEvents = CopyOnWriteArrayList<Pair<String, UUID>>()
    val revoked = CopyOnWriteArrayList<UUID>()

    override fun deviceEventInTx(dsl: DSLContext, type: String, deviceId: UUID, name: String) {
        deviceEvents += type to deviceId
    }

    override fun tokensRevoked(tokenIds: Collection<UUID>) {
        revoked += tokenIds
    }
}

/** A migrated database, a controllable clock and helpers to mint credentials directly in `app.api_token`. */
abstract class AuthFixture {
    protected val now = AtomicReference(Instant.parse("2026-11-12T09:30:00Z"))
    protected val nanos = AtomicLong(0)
    protected val db: Database = TestPg.migratedDb()
    protected val events = RecordingEvents()
    protected lateinit var auth: Auth
    private val cheapHash = PasswordHasher.hash("pw", PasswordHasher.Params(1024, 1, 1))

    @AfterTest
    fun closeDb() = db.close()

    protected fun app(
        withHandlers: Boolean = true,
        authEvents: AuthEvents = events,
        ankiConnect: AnkiConnectActions? = null,
        extraHandlers: Map<String, OperationHandler> = emptyMap(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val clock = { now.get() }
        auth = Auth(
            db, AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to cheapHash, "PUBLIC_URL" to "https://kotobaya.example.net/")), clock,
            validator = TokenValidator(db, clock, nanoTime = { nanos.get() }),
            clientAddress = { it.request.headers["X-Test-Client"] ?: "client-a" },
            events = authEvents,
            ankiConnect = ankiConnect,
        )
        val handlers = if (withHandlers) auth.handlers + extraHandlers else extraHandlers
        application { kotobayaModule(AppDeps(db, authenticator = auth.authenticator, handlers = handlers)) }
        block()
    }

    /** Inserts a token row and returns (id, token string). */
    protected fun mint(kind: TokenKind, scopes: Collection<String>, deviceId: UUID? = null, expiresAt: Instant? = null): Pair<UUID, String> {
        val token = Tokens.generate(kind)
        val id = UUID.randomUUID()
        db.txBlocking { dsl ->
            dsl.execute(
                "insert into app.api_token (id, name, kind, token_hash, token_prefix, scopes, device_id, created_at, expires_at) " +
                    "values (?, ?, ?, ?, ?, ?::text[], ?, ?::timestamptz, ?::timestamptz)",
                id, "test-${kind.wire}", kind.wire, Tokens.hash(token), Tokens.prefix(token), scopes.toTypedArray(), deviceId,
                OffsetDateTime.ofInstant(now.get(), ZoneOffset.UTC), expiresAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            )
        }
        return id to token
    }

    protected fun insertDevice(name: String = "Pixel 9", kind: String = "android"): UUID {
        val id = UUID.randomUUID()
        db.txBlocking { it.execute("insert into app.device (id, name, kind) values (?, ?, ?)", id, name, kind) }
        return id
    }

    protected fun admin(): String = mint(TokenKind.PERSONAL, ALL_SCOPES).second

    /** A request; [bearer] or [cookie] (with the CSRF header) as the credential, [body] as JSON. */
    protected suspend fun HttpClient.call(
        method: HttpMethod,
        path: String,
        bearer: String? = null,
        cookie: String? = null,
        body: Any? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = request("/api/v1$path") {
        this.method = method
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        cookie?.let {
            header(HttpHeaders.Cookie, "kotobaya_session=$it")
            header(CSRF_HEADER, "1")
        }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(if (body is String) body else json.writeValueAsString(body))
        }
        configure()
    }

    protected fun HttpResponse.json(): JsonNode = runBlocking { json.readTree(bodyAsText()) }

    protected fun assertProblem(r: HttpResponse, status: Int, code: String) {
        val text = runBlocking { r.bodyAsText() }
        assertEquals(status, r.status.value, text)
        assertEquals(code, json.readTree(text)["code"].asText(), text)
    }
}
