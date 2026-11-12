package kotobaya.auth

import io.ktor.client.statement.bodyAsText
import kotobaya.server.ApiSpec
import kotobaya.server.Operation
import kotobaya.server.ScopeRequirements
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Table-driven over `contracts/api/openapi.yaml` (the classpath copy; nothing hand-copied). No handlers are
 * registered, so an operation that passes auth answers 501 from the spec-derived stub.
 */
class ScopeEnforcementTest : AuthFixture() {
    private val spec = ApiSpec.load()
    private val BUILTIN = setOf("getHealth", "getMeta")

    private fun samplePath(op: Operation) = op.path.replace(Regex("\\{[^}]+}"), "x")

    private enum class Scheme(val name_: String) { BEARER("bearerAuth"), COOKIE("sessionCookie") }

    /** The scope sets that satisfy [op] for [scheme] (the other scheme's when the operation lists none for it). */
    private fun alternatives(op: Operation, scheme: Scheme): List<List<String>> {
        val withCredential = op.security.filter { it.isNotEmpty() }
        val own = withCredential.filter { scheme.name_ in it }.ifEmpty { withCredential }
        return own.map { it.values.flatten() }
    }

    @Test
    fun everyScopedOperationRefusesACredentialLackingItsScopeAndAdmitsOneHoldingIt() = app(withHandlers = false) {
        val tokens = HashMap<Pair<Scheme, Set<String>>, String>()
        fun credential(scheme: Scheme, scopes: Set<String>) = tokens.getOrPut(scheme to scopes) {
            mint(if (scheme == Scheme.BEARER) TokenKind.PERSONAL else TokenKind.SESSION, scopes).second
        }
        var checked = 0
        for (op in spec.operations) {
            if (op.security.isEmpty() || op.xAuth == "body-key") continue
            for (scheme in Scheme.entries) {
                val alts = alternatives(op, scheme)
                suspend fun send(scopes: Set<String>) = credential(scheme, scopes).let { t ->
                    if (scheme == Scheme.BEARER) client.call(op.method, samplePath(op), bearer = t) else client.call(op.method, samplePath(op), cookie = t)
                }
                val needed = alts.flatten().toSet()
                if (needed.isEmpty()) {
                    // `getPrincipal`: any valid credential, even one with no scope at all.
                    assertEquals(501, send(emptySet()).status.value, "${op.operationId} $scheme")
                    continue
                }
                val lacking = send(ALL_SCOPES.toSet() - needed)
                assertProblem(lacking, 403, "insufficient_scope")
                assertTrue(lacking.json()["required_scope"].asText() in needed, op.operationId)
                for (alt in alts) {
                    val r = send(alt.toSet())
                    // Built-in handlers (getMeta) run for real; everything else is the 501 stub.
                    val expected = if (op.operationId in BUILTIN) r.status.value !in setOf(401, 403) else r.status.value == 501
                    assertTrue(expected, "${op.operationId} $scheme $alt: ${r.status} ${r.bodyAsText()}")
                }
                checked++
            }
        }
        assertTrue(checked > 200, "checked $checked operation/scheme pairs")
    }

    @Test
    fun publicOperationsNeedNoCredentialAndScopedOnesRefuseAMissingOne() = app(withHandlers = false) {
        for (op in spec.operations) {
            val r = client.call(op.method, samplePath(op))
            when {
                op.security.isEmpty() || op.xAuth == "body-key" -> assertTrue(r.status.value !in setOf(401, 403), "${op.operationId}: ${r.status}")
                else -> assertProblem(r, 401, "unauthenticated")
            }
        }
    }

    @Test
    fun scriptTokensWithoutAdminCannotReachAnyAdminOperation() = app(withHandlers = false) {
        val script = mint(TokenKind.PERSONAL, ALL_SCOPES - "admin").second
        val adminOps = spec.operations.filter { op -> op.security.any { "admin" in it.values.flatten() } }
        assertTrue(adminOps.map { it.operationId }.containsAll(listOf("listDevices", "createPairing", "revokeDevice", "createToken", "revokeToken")))
        for (op in adminOps) assertProblem(client.call(op.method, samplePath(op), bearer = script), 403, "insufficient_scope")
    }

    @Test
    fun scopesAreIndependentNoneImpliesAnother() = app(withHandlers = false) {
        val adminOnly = mint(TokenKind.PERSONAL, listOf("admin")).second
        assertProblem(client.call(io.ktor.http.HttpMethod.Get, "/settings", bearer = adminOnly), 403, "insufficient_scope")
        val writeOnly = mint(TokenKind.PERSONAL, listOf("write")).second
        assertProblem(client.call(io.ktor.http.HttpMethod.Get, "/settings", bearer = writeOnly), 403, "insufficient_scope")
    }

    @Test
    fun bulkScheduleNeedsOnlyReviewAsTheContractSays() {
        val req = ScopeRequirements.from(spec).forOperation("bulkScheduleCards")
        val reviewOnly = Authenticated(principal(listOf("review")), CredentialSource.BEARER)
        assertNull(ScopePolicy.missingScope(reviewOnly, req))
    }

    @Test
    fun putBlobAcceptsWriteOrImportForBearerButOnlyWriteForTheCookie() {
        val req = ScopeRequirements.from(spec).forOperation("putBlob")
        assertNull(ScopePolicy.missingScope(Authenticated(principal(listOf("import")), CredentialSource.BEARER), req))
        assertNull(ScopePolicy.missingScope(Authenticated(principal(listOf("write")), CredentialSource.BEARER), req))
        assertEquals("write", ScopePolicy.missingScope(Authenticated(principal(listOf("import")), CredentialSource.COOKIE), req))
    }

    // ---------------------------------------------------------------- push per-op helper (T202 calls it)

    @Test
    fun pushOpAuthRejectsForeignDeviceThenMissingReviewScope() {
        val device = UUID.randomUUID()
        val phone = principal(listOf("read", "write", "review"), device)
        val script = principal(listOf("read", "write"), device)
        assertNull(PushOpAuth.check(phone, device.toString(), "review.logged"))
        assertNull(PushOpAuth.check(phone, device.toString(), "note.created"))
        assertEquals("device_mismatch", PushOpAuth.check(phone, UUID.randomUUID().toString(), "note.created"))
        assertEquals("device_mismatch", PushOpAuth.check(script, UUID.randomUUID().toString(), "review.logged"), "device first")
        assertEquals("insufficient_scope", PushOpAuth.check(script, device.toString(), "review.logged"))
        assertNull(PushOpAuth.check(script, device.toString(), "card.updated"))
        val reviewOnly = principal(listOf("review"), device)
        assertEquals("insufficient_scope", PushOpAuth.check(reviewOnly, device.toString(), "note.created"))
        assertEquals("device_mismatch", PushOpAuth.check(principal(ALL_SCOPES, null), device.toString(), "note.created"))
    }

    @Test
    fun requireDeviceIs403DeviceRequiredWithoutABoundDevice() {
        val e = runCatching { Authenticated(principal(ALL_SCOPES, null), CredentialSource.COOKIE).requireDevice() }.exceptionOrNull()
        assertEquals("device_required", (e as kotobaya.server.ApiException).code)
        assertEquals(403, e.status.value)
        val d = UUID.randomUUID()
        assertEquals(d, Authenticated(principal(listOf("read"), d), CredentialSource.BEARER).requireDevice())
    }

    private fun principal(scopes: List<String>, device: UUID? = null) =
        Principal(UUID.randomUUID(), "t", TokenKind.PERSONAL, scopes, device, null)
}
