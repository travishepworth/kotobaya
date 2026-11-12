package kotobaya.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.util.AttributeKey
import kotobaya.server.ApiException
import kotobaya.server.Authenticator
import kotobaya.server.Problems
import kotobaya.server.ScopeRequirement
import kotobaya.server.TokenInfo

const val SESSION_COOKIE = "kotobaya_session"
const val CSRF_HEADER = "X-Kotobaya-Csrf"

/** OpenAPI security scheme names (`components.securitySchemes`); [BODY_KEY] is the `x-auth: body-key` token in the JSON body. */
enum class CredentialSource(val scheme: String) { BEARER("bearerAuth"), COOKIE("sessionCookie"), BODY_KEY("body-key") }

data class Authenticated(val principal: Principal, val source: CredentialSource)

val AuthenticatedKey = AttributeKey<Authenticated>("kotobaya.authenticated")

/** The caller's credential, set by [TokenAuthenticator] for every non-public operation. */
val ApplicationCall.authenticated: Authenticated?
    get() = attributes.getOrNull(AuthenticatedKey)

/** Scope check run after the credential is known to be valid. The real one is [ScopePolicy]. */
fun interface ScopeCheck {
    /** Throw [ApiException] (`Problems.insufficientScope`) to refuse. */
    fun check(auth: Authenticated, requirement: ScopeRequirement)

    companion object {
        val None = ScopeCheck { _, _ -> }
    }
}

/**
 * The T104 [Authenticator]: Authorization header (wins when present) or the session cookie, validated by
 * [TokenValidator]; 401 `unauthenticated` when missing or invalid; CSRF header check for cookie-authenticated
 * writes (openapi §2); then [scopeCheck]; then [onAuthenticated] (usage bookkeeping).
 *
 * `x-auth: body-key` operations (`/ankiconnect`) are never refused here: a refused header/cookie credential is
 * recorded for [AnkiConnectGate], which answers in the AnkiConnect envelope and otherwise authenticates the body
 * `key`. No other operation ever looks at a body key.
 */
class TokenAuthenticator(
    private val validator: TokenValidator,
    private val scopeCheck: ScopeCheck = ScopePolicy,
    private val onAuthenticated: suspend (Principal) -> Unit = {},
) : Authenticator {
    override suspend fun authenticate(call: ApplicationCall, requirement: ScopeRequirement): TokenInfo? {
        if (requirement.public) return null
        if (!requirement.bodyKey) return authenticateCredential(call, requirement)
        call.attributes.put(BodyKeyRequirementKey, requirement)
        return try {
            authenticateCredential(call, requirement)
        } catch (e: ApiException) {
            call.attributes.remove(AuthenticatedKey)
            call.attributes.put(HeaderFailureKey, e)
            null
        }
    }

    private suspend fun authenticateCredential(call: ApplicationCall, requirement: ScopeRequirement): TokenInfo? {
        val presented = credentialOf(call)
        if (presented == null) {
            // `x-auth: body-key`: the token may come in the body; [AnkiConnectGate] checks it.
            if (requirement.bodyKey) return null
            throw Problems.unauthenticated("No credential.")
        }
        val (token, source) = presented
        val principal = validator.validate(token) ?: throw Problems.unauthenticated("The token is unknown, expired or revoked.")
        // Which scheme an operation accepts (bearer-only `/devices/self`, ...) is left to [scopeCheck] and the
        // handler: `logout` declares only the cookie yet must answer a bearer device token with 422, so a blanket
        // scheme filter here would contradict the contract.
        val auth = Authenticated(principal, source)
        call.attributes.put(AuthenticatedKey, auth)
        if (source == CredentialSource.COOKIE && call.request.httpMethod !in SAFE_METHODS &&
            call.request.headers[CSRF_HEADER] != "1"
        ) {
            throw ApiException(HttpStatusCode.Forbidden, "csrf_required", "CSRF header required", "Send $CSRF_HEADER: 1.")
        }
        scopeCheck.check(auth, requirement)
        onAuthenticated(principal)
        return TokenInfo(principal.tokenId.toString(), principal.name)
    }

    companion object {
        private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head)

        /** Header wins over the cookie when both are present; a non-Bearer header is no credential and fails. */
        fun credentialOf(call: ApplicationCall): Pair<String, CredentialSource>? {
            val header = call.request.headers[HttpHeaders.Authorization]
            if (header != null) {
                val parts = header.trim().split(' ', limit = 2)
                if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) {
                    throw Problems.unauthenticated("The Authorization header must be `Bearer <token>`.")
                }
                return parts[1].trim() to CredentialSource.BEARER
            }
            val cookie = call.request.cookies.rawCookies[SESSION_COOKIE]?.takeIf { it.isNotEmpty() } ?: return null
            return cookie to CredentialSource.COOKIE
        }
    }
}
