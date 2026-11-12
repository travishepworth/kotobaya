package kotobaya.auth

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.TokenInfo
import kotobaya.server.TokenKey
import kotobaya.server.Uuid7
import kotobaya.server.formatInstant
import kotobaya.server.json
import kotobaya.server.requestId
import kotobaya.server.respondJson
import kotobaya.server.respondProblem
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Everything auth: login/logout/session (T105a); devices, pairing, personal tokens, scope enforcement and the
 * `/ankiconnect` body-key gate (T105b). Wire it with `AppDeps(authenticator = auth.authenticator, handlers = auth.handlers + ...)`.
 *
 * [clientAddress] keys the login and pairing rate limits; the default is [ClientAddressResolver] over the TCP peer
 * and `X-Forwarded-For`, trusting the header only from `AUTH_TRUSTED_PROXIES`.
 * [events] publishes `device.paired` / `device.revoked` and ends revoked tokens' `/events` streams
 * (`AuthEvents.of(eventBus, eventHub)`). [ankiConnect] runs the AnkiConnect actions behind the auth gate; until a
 * ticket supplies it, only `version` works.
 */
class Auth(
    private val db: Database,
    private val config: AuthConfig,
    private val clock: () -> Instant = Instant::now,
    val validator: TokenValidator = TokenValidator(db, clock),
    private val limiter: LoginRateLimiter = LoginRateLimiter(),
    scopeCheck: ScopeCheck = ScopePolicy,
    private val clientAddress: (ApplicationCall) -> String = ClientAddressResolver(config.trustedProxies).let { r ->
        { call -> r.resolve(call.request.local.remoteAddress, call.request.headers.getAll("X-Forwarded-For").orEmpty()) }
    },
    private val events: AuthEvents = AuthEvents.None,
    ankiConnect: AnkiConnectActions? = null,
    pairingCodes: PairingCodes = PairingCodes(),
    /** openapi `pairDevice`: more than 10 failures from one client address in 15 minutes is `429`. */
    pairLimiter: LoginRateLimiter = LoginRateLimiter(perAddress = 10, global = 200),
) {
    private val log = LoggerFactory.getLogger("kotobaya.auth")
    private val totp = config.totpKey?.let { Totp(it, startedAt = clock()) }

    /** Bound concurrent Argon2 verifications to 256 MiB in total (2 at the default 64 MiB) so a login flood cannot exhaust the heap. */
    private val hashing = Semaphore((PasswordHasher.MAX_MEMORY_KiB / config.passwordHash.params.memoryKiB).coerceIn(1, 2))

    private val usage = UsageRecorder(db, clock)

    val authenticator = TokenAuthenticator(validator, scopeCheck) { usage.record(it) }

    private val devices = Devices(db, validator, events, pairingCodes, pairLimiter, clock, clientAddress, config.publicUrl)
    private val tokens = ApiTokens(db, validator, events, clock)

    val handlers: Map<String, OperationHandler> = mapOf(
        "login" to { login(this) },
        "logout" to { logout(this) },
        "getPrincipal" to { getPrincipal(this) },
        "ankiConnect" to AnkiConnectGate(validator, ankiConnect).handler,
    ) + devices.handlers + tokens.handlers

    private suspend fun login(call: ApplicationCall) {
        val req = parseLogin(call)
        val address = clientAddress(call)
        val now = clock()
        limiter.begin(address, now)?.let { wait ->
            log.warn("login rate limited", kv("event", "auth.login"), kv("outcome", "rate_limited"), kv("client", address), kv("request_id", call.requestId))
            call.response.header(HttpHeaders.RetryAfter, wait.toString())
            call.respondProblem(
                ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many attempts", "Try again in $wait seconds."),
            )
            return
        }
        if (totp != null && req.totpCode == null) {
            limiter.uncounted(address, now)
            throw ApiException(HttpStatusCode.Unauthorized, "totp_required", "TOTP code required", "Send totp_code.")
        }
        // Always run the password check, also when the code is wrong, so timing does not tell which was wrong.
        val passwordOk = hashing.withPermit {
            withContext(Dispatchers.Default) { PasswordHasher.verify(req.password, config.passwordHash) }
        }
        val t = totp
        val codeOk = t == null || run {
            val step = req.totpCode?.let { t.matchingStep(it, now) }
            // Consume the step only with the right password, so wrong guesses cannot burn the user's code.
            step != null && passwordOk && t.consume(step)
        }
        if (!passwordOk || !codeOk) {
            log.warn("login failed", kv("event", "auth.login"), kv("outcome", "invalid_credentials"), kv("client", address), kv("request_id", call.requestId))
            throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Invalid credentials", "Wrong password or code.")
        }
        limiter.succeeded(address, now)

        val principal = createSession(now)
        val token = principal.second
        call.attributes.put(TokenKey, TokenInfo(principal.first.tokenId.toString(), principal.first.name))
        log.info(
            "login ok", kv("event", "auth.login"), kv("outcome", "ok"), kv("client", address),
            kv("token.id", principal.first.tokenId.toString()), kv("request_id", call.requestId),
        )
        call.response.header(
            HttpHeaders.SetCookie,
            "$SESSION_COOKIE=$token; Path=/api; HttpOnly; Secure; SameSite=Strict; Max-Age=${SESSION_LIFETIME.seconds}",
        )
        call.respondJson(principalJson(principal.first))
    }

    /** Inserts a `session` row (only the hash) and returns it with the token string. */
    private suspend fun createSession(now: Instant): Pair<Principal, String> {
        val token = Tokens.generate(TokenKind.SESSION)
        val id = Uuid7.generate(now.toEpochMilli())
        val name = "web session ${now.atOffset(ZoneOffset.UTC).toLocalDate()}"
        val expires = now.plus(SESSION_LIFETIME)
        db.tx { dsl ->
            dsl.execute(
                """
                insert into app.api_token (id, name, kind, token_hash, token_prefix, scopes, created_at, expires_at)
                values (?, ?, 'session', ?, ?, array['read', 'write', 'review', 'import', 'admin'], ?::timestamptz, ?::timestamptz)
                """.trimIndent(),
                id, name, Tokens.hash(token), Tokens.prefix(token),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC), OffsetDateTime.ofInstant(expires, ZoneOffset.UTC),
            )
        }
        return Principal(id, name, TokenKind.SESSION, ALL_SCOPES, null, expires) to token
    }

    private suspend fun logout(call: ApplicationCall) {
        val auth = call.authenticated ?: throw Problems.unauthenticated()
        if (auth.principal.kind != TokenKind.SESSION) {
            throw validationFailed("", "invalid_value", "Only session tokens log out; revoke device and personal tokens by id.")
        }
        validator.revoke(auth.principal.tokenId)
        events.tokensRevoked(listOf(auth.principal.tokenId))
        log.info("logout", kv("event", "auth.logout"), kv("token.id", auth.principal.tokenId.toString()), kv("request_id", call.requestId))
        call.response.header(HttpHeaders.SetCookie, "$SESSION_COOKIE=; Path=/api; Max-Age=0")
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun getPrincipal(call: ApplicationCall) {
        val auth = call.authenticated ?: throw Problems.unauthenticated()
        call.respondJson(principalJson(auth.principal))
    }

    private fun principalJson(p: Principal): Map<String, Any?> = linkedMapOf(
        "token_id" to p.tokenId.toString(),
        "kind" to p.kind.wire,
        "name" to p.name,
        "scopes" to p.scopes,
        "device_id" to p.deviceId?.toString(),
        "expires_at" to p.expiresAt?.let(::formatInstant),
        "totp_enabled" to config.totpEnabled,
    )

    private class LoginRequest(val password: String, val totpCode: String?)

    private suspend fun parseLogin(call: ApplicationCall): LoginRequest {
        if (!call.request.contentType().match(ContentType.Application.Json)) {
            throw ApiException(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type", "Unsupported media type", "Send application/json.")
        }
        val body: JsonNode = runCatching { json.readTree(call.receiveText()) }.getOrNull()
            ?: throw Problems.invalidRequest("The body is not valid JSON.")
        if (!body.isObject) throw validationFailed("", "invalid_type", "The body must be a JSON object.")
        body.fieldNames().asSequence().firstOrNull { it != "password" && it != "totp_code" }?.let {
            throw validationFailed("/$it", "unknown_field", "Unknown property $it.")
        }
        val pw = body.get("password") ?: throw validationFailed("/password", "required", "password is required.")
        if (!pw.isTextual) throw validationFailed("/password", "invalid_type", "password must be a string.")
        if (pw.asText().isEmpty() || pw.asText().length > 1024) {
            throw validationFailed("/password", "out_of_range", "password must be 1 to 1024 characters.")
        }
        val code = body.get("totp_code")
        if (code != null && (!code.isTextual || !TOTP_CODE.matches(code.asText()))) {
            throw validationFailed("/totp_code", "invalid_value", "totp_code must be 6 digits.")
        }
        return LoginRequest(pw.asText(), code?.asText())
    }

    companion object {
        val SESSION_LIFETIME: Duration = Duration.ofDays(30)
        private val TOTP_CODE = Regex("^[0-9]{6}$")
    }
}
