package kotobaya.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.util.AttributeKey
import kotobaya.server.ApiException
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.ScopeRequirement
import kotobaya.server.TokenInfo
import kotobaya.server.setToken
import kotobaya.server.json
import kotobaya.server.respondJson

/** An AnkiConnect protocol-level failure: answered as HTTP 200 `{"result": null, "error": message}`. */
class AnkiConnectError(message: String) : RuntimeException(message)

/**
 * The AnkiConnect actions behind the auth gate (a later ticket implements them). Never called for `version` or
 * without a credential holding the `x-body-key-scope`.
 */
fun interface AnkiConnectActions {
    /** The envelope's `result` for [action]; throw [AnkiConnectError] for its `error`. */
    suspend fun run(call: ApplicationCall, auth: Authenticated, action: String, request: ObjectNode): Any?
}

/** Set by [TokenAuthenticator] on `x-auth: body-key` operations when the header/cookie credential was refused. */
internal val HeaderFailureKey = AttributeKey<ApiException>("kotobaya.auth.headerFailure")

/** Set by [TokenAuthenticator] on `x-auth: body-key` operations: the requirement (for `x-body-key-scope`). */
internal val BodyKeyRequirementKey = AttributeKey<ScopeRequirement>("kotobaya.auth.bodyKeyRequirement")

/**
 * `POST /ankiconnect` (`x-auth: body-key`). The authenticator never refuses this operation over HTTP; this gate
 * answers every auth failure in the AnkiConnect envelope (always HTTP 200, only a non-JSON body is `400`):
 *
 * - a header or cookie credential wins; if it was refused (invalid, lacking `write`, cookie without CSRF header) the
 *   body `key` is not tried;
 * - otherwise `key` must be a valid personal access token (the same validity rules as a bearer token) holding the
 *   operation's `x-body-key-scope`;
 * - `version` needs no credential.
 */
internal class AnkiConnectGate(
    private val validator: TokenValidator,
    private val actions: AnkiConnectActions?,
    /** Usage bookkeeping (`last_used_at`), as the authenticator does for header credentials. */
    private val onAuthenticated: suspend (Principal) -> Unit = {},
) {
    val handler: OperationHandler = { handle(this) }

    private suspend fun handle(call: ApplicationCall) {
        val body: JsonNode = runCatching { json.readTree(call.receiveText()) }.getOrNull()
            ?: throw Problems.invalidRequest("The body is not valid JSON.")
        val result = runCatching { dispatch(call, body) }
        result.exceptionOrNull()?.let { if (it !is AnkiConnectError) throw it }
        call.respondJson(
            linkedMapOf("result" to result.getOrNull(), "error" to result.exceptionOrNull()?.message),
        )
    }

    private suspend fun dispatch(call: ApplicationCall, body: JsonNode): Any? {
        if (body !is ObjectNode) throw AnkiConnectError("the request must be a JSON object")
        val action = body.get("action")?.takeIf { it.isTextual }?.asText() ?: throw AnkiConnectError("action is required")
        if (action == "version") return PROTOCOL_VERSION
        val auth = call.authenticated ?: bodyKey(call, body)
        val run = actions ?: throw AnkiConnectError("unsupported action")
        return run.run(call, auth, action, body)
    }

    private suspend fun bodyKey(call: ApplicationCall, body: ObjectNode): Authenticated {
        call.attributes.getOrNull(HeaderFailureKey)?.let { e -> throw AnkiConnectError(message(e)) }
        val key = body.get("key")?.takeIf { it.isTextual }?.asText() ?: throw AnkiConnectError(NO_KEY)
        val principal = validator.validate(key)?.takeIf { it.kind == TokenKind.PERSONAL } ?: throw AnkiConnectError(NO_KEY)
        val scope = call.attributes.getOrNull(BodyKeyRequirementKey)?.bodyKeyScope ?: "write"
        if (scope !in principal.scopes) throw AnkiConnectError("the api key lacks the $scope scope")
        val auth = Authenticated(principal, CredentialSource.BODY_KEY)
        call.attributes.put(AuthenticatedKey, auth)
        call.setToken(TokenInfo(principal.tokenId.toString(), principal.name, principal.deviceId?.toString()))
        onAuthenticated(principal)
        return auth
    }

    private fun message(e: ApiException): String = when (e.code) {
        "unauthenticated" -> NO_KEY
        else -> e.detail ?: e.title
    }

    companion object {
        const val PROTOCOL_VERSION = 6
        const val NO_KEY = "valid api key must be provided"
    }
}
