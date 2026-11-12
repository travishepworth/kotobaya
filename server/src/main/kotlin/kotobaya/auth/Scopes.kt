package kotobaya.auth

import io.ktor.http.HttpStatusCode
import kotobaya.server.ApiException
import kotobaya.server.Problems
import kotobaya.server.ScopeRequirement
import java.util.UUID

/**
 * The real [ScopeCheck]: every operation's `security` from `contracts/api/openapi.yaml` (loaded at runtime by
 * `ApiSpec`, so the table is never hand-copied). Scopes are independent; one alternative is enough, and all the
 * scopes listed inside one alternative are needed.
 *
 * Which alternatives apply: those of the scheme the credential came by. If the operation lists no alternative for
 * that scheme (`/devices/self` is bearer only, `logout` cookie only), the other scheme's scopes are applied and the
 * handler decides the rest, because the contract answers those cases in the handler: a session on `/devices/self`
 * gets `403 device_required`, a bearer device token on `logout` gets `422`.
 */
object ScopePolicy : ScopeCheck {
    override fun check(auth: Authenticated, requirement: ScopeRequirement) {
        if (requirement.public) return
        val missing = missingScope(auth, requirement) ?: return
        throw Problems.insufficientScope(missing)
    }

    /** Null when [auth] satisfies [requirement]; otherwise the scope to report in `required_scope`. */
    fun missingScope(auth: Authenticated, requirement: ScopeRequirement): String? {
        val credentialAlternatives = requirement.alternatives.filter { it.isNotEmpty() }
        if (credentialAlternatives.isEmpty()) return null
        val own = credentialAlternatives.filter { auth.source.scheme in it }
        val applicable = own.ifEmpty { credentialAlternatives }
        val held = auth.principal.scopes.toSet()
        val needs = applicable.map { alt -> alt.values.flatten() }
        if (needs.any { held.containsAll(it) }) return null
        return needs.first().first { it !in held }
    }
}

/**
 * Per-op authorisation inside `POST /sync/push` (SYNC.md 5.3), for T202. Returns the rejection code, or null.
 * Checked in contract order: `device_mismatch` (the op's `device_id` is not the caller's device), then
 * `insufficient_scope` (a `review.*` op needs `review`; every other op needs `write`).
 */
object PushOpAuth {
    const val DEVICE_MISMATCH = "device_mismatch"
    const val INSUFFICIENT_SCOPE = "insufficient_scope"

    fun check(principal: Principal, opDeviceId: String, opType: String): String? {
        val own = principal.deviceId ?: return DEVICE_MISMATCH
        if (opDeviceId != own.toString()) return DEVICE_MISMATCH
        if (requiredScope(opType) !in principal.scopes) return INSUFFICIENT_SCOPE
        return null
    }

    fun requiredScope(opType: String): String = if (opType.startsWith("review.")) "review" else "write"
}

fun deviceRequired(): ApiException = ApiException(
    HttpStatusCode.Forbidden, "device_required", "Device required", "This credential is not bound to a device.",
)

/** The caller's device id, or `403 device_required` (`/devices/self`, and `POST /sync/push` for T202). */
fun Authenticated.requireDevice(): UUID = principal.deviceId ?: throw deviceRequired()
