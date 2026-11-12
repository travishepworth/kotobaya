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

/** OpenAPI security scheme names (`components.securitySchemes`). */
enum class CredentialSource(val scheme: String) { BEARER("bearerAuth"), COOKIE("sessionCookie") }

data class Authenticated(val principal: Principal, val source: CredentialSource)

val AuthenticatedKey = AttributeKey<Authenticated>("kotobaya.authenticated")

/** The caller's credential, set by [TokenAuthenticator] for every non-public operation. */
val ApplicationCall.authenticated: Authenticated?
    get() = attributes.getOrNull(AuthenticatedKey)

/**
 * Scope check run after the credential is known to be valid and allowed for the operation's scheme.
 * T105b supplies the real one; until then every valid credential passes (sessions hold all scopes anyway).
 */
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
 * writes (openapi §2); then [scopeCheck].
 */
class TokenAuthenticator(
    private val validator: TokenValidator,
    private val scopeCheck: ScopeCheck = ScopeCheck.None,
) : Authenticator {
    override suspend fun authenticate(call: ApplicationCall, requirement: ScopeRequirement): TokenInfo? {
        if (requirement.public) return null
        val presented = credentialOf(call)
        if (presented == null) {
            // `x-auth: body-key` operations: the `{}` alternative means the token may come in the body (T105b).
            if (requirement.alternatives.any { it.isEmpty() }) return null
            throw Problems.unauthenticated("No credential.")
        }
        val (token, source) = presented
        val principal = validator.validate(token) ?: throw Problems.unauthenticated("The token is unknown, expired or revoked.")
        // Which scheme an operation accepts (bearer-only `/devices/self`, ...) is left to [scopeCheck] (T105b):
        // `logout` declares only the cookie yet must answer a bearer device token with 422, so a blanket
        // scheme filter here would contradict the contract.
        val auth = Authenticated(principal, source)
        call.attributes.put(AuthenticatedKey, auth)
        if (source == CredentialSource.COOKIE && call.request.httpMethod !in SAFE_METHODS &&
            call.request.headers[CSRF_HEADER] != "1"
        ) {
            throw ApiException(HttpStatusCode.Forbidden, "csrf_required", "CSRF header required", "Send $CSRF_HEADER: 1.")
        }
        scopeCheck.check(auth, requirement)
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
