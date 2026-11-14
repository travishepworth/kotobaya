package kotobaya.server

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

// ---------------------------------------------------------------------------------------------
// Auth hook points. T105a/b implement [Authenticator] (in kotobaya.auth); the skeleton allows all.
// ---------------------------------------------------------------------------------------------

/** The credential behind a request; logged on every request as `token.id` / `token.name` (the audit trail). */
data class TokenInfo(val id: String, val name: String, /** The credential's device, when it is a device token (OBS-1s: logged as `device_id`). */ val deviceId: String? = null)

/**
 * What one operation needs, built from the spec's `security` entries.
 *
 * - [public]: `security: []`, no credential needed.
 * - [alternatives]: caller needs ANY one alternative (scheme to scopes); an empty map is the
 *   "no header needed" alternative of `x-auth: body-key` operations, which T105b must handle by hand:
 *   the token then comes from the JSON body property `key` and needs [bodyKeyScope].
 */
data class ScopeRequirement(
    val operationId: String,
    val public: Boolean,
    val alternatives: List<SecurityAlternative>,
    val bodyKey: Boolean,
    val bodyKeyScope: String?,
)

class ScopeRequirements(private val byOperation: Map<String, ScopeRequirement>) {
    fun forOperation(operationId: String): ScopeRequirement =
        byOperation[operationId] ?: error("no such operation $operationId")

    companion object {
        fun from(spec: ApiSpec) = ScopeRequirements(
            spec.operations.associate {
                it.operationId to ScopeRequirement(
                    operationId = it.operationId,
                    public = it.security.isEmpty(),
                    alternatives = it.security,
                    bodyKey = it.xAuth == "body-key",
                    bodyKeyScope = it.xBodyKeyScope,
                )
            },
        )
    }
}

/**
 * Called for every spec operation before its handler. Return the token (or null for public/anonymous),
 * or throw [ApiException] (`Problems.unauthenticated()`, `Problems.insufficientScope(..)`).
 */
fun interface Authenticator {
    suspend fun authenticate(call: ApplicationCall, requirement: ScopeRequirement): TokenInfo?
}

/** Default until T105a/b: every request is allowed. */
object AllowAll : Authenticator {
    override suspend fun authenticate(call: ApplicationCall, requirement: ScopeRequirement): TokenInfo? = null
}

// ---------------------------------------------------------------------------------------------
// Readiness checks. `/readyz` is 200 only when every check in the list is ready.
// T202 appends "SYNC 10.5 startup replay finished" to the list.
// ---------------------------------------------------------------------------------------------

interface ReadinessCheck {
    val name: String

    /** Null when ready, otherwise a short reason. Must not throw. */
    suspend fun check(): String?
}

class DatabaseCheck(private val db: Database) : ReadinessCheck {
    override val name = "postgres"
    override suspend fun check(): String? = if (db.ping()) null else "Postgres is unreachable"
}

/** Ready once the SYNC.md 6.3 / 10.5 startup sequence has committed. */
class StartupCheck(private val state: StartupState) : ReadinessCheck {
    override val name = "startup"
    override suspend fun check(): String? =
        if (state.done) null else "startup sequence not finished" + (state.lastError?.let { ": $it" } ?: "")
}

/** An operation handler, run with the call as receiver after the authenticator allowed it. */
typealias OperationHandler = suspend ApplicationCall.() -> Unit

val RequestIdKey = AttributeKey<String>("kotobaya.requestId")
val TokenKey = AttributeKey<TokenInfo>("kotobaya.token")

val ApplicationCall.requestId: String get() = attributes.getOrNull(RequestIdKey) ?: "unknown"
