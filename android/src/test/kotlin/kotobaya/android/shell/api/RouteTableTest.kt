package kotobaya.android.shell.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.DefaultCanonicalJson

/**
 * SHELL-A2 acceptance: the route table against `contracts/api/shell/ops.json`, and the kernel's copy
 * of the operation table against `openapi.yaml` / `shell.yaml` / `ops.json`.
 */
class RouteTableTest {
    companion object {
        /**
         * `local` / `local_or_proxy` operations with no handler yet. Each L ticket removes its own
         * line when it registers the routes; the set only shrinks.
         */
        val PENDING: Set<String> = setOf(
            // SHELL-L7 decks
            "listDecks", "createDeck", "getDeck", "updateDeck", "deleteDeck", "listDeckItems", "addDeckItems",
            "putDeckItem", "removeDeckItem",
            // SHELL-L8 struggle
            "listHotWords", "listHotKanji", "listLeeches", "createDrillSession",
        )

        /** `local`, but never a bridge route: answered by the blob intercept (SHELL.md 2.7). */
        val NOT_A_ROUTE: Set<String> = setOf("getBlob")

        private val contracts = File("../contracts/api")

        fun opsJson(): Map<String, String> {
            val tree = DefaultCanonicalJson.decode(File(contracts, "shell/ops.json").readText()).value as JsonObject
            return tree.members.mapValues { (it.value as JsonString).value }
        }

        /** `operationId -> "METHOD /prefix/path"` from the `paths:` block of an OpenAPI YAML file. */
        fun yamlOperations(file: File, prefix: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var inPaths = false
            var path: String? = null
            var method: String? = null
            for (line in file.readLines()) {
                if (line.startsWith("paths:")) { inPaths = true; continue }
                if (inPaths && line.isNotEmpty() && !line[0].isWhitespace()) inPaths = false
                if (!inPaths) continue
                Regex("^  (/\\S*):\\s*$").find(line)?.let { path = it.groupValues[1] }
                Regex("^    (get|post|put|patch|delete):\\s*$").find(line)?.let { method = it.groupValues[1].uppercase() }
                Regex("^      operationId:\\s*(\\S+)").find(line)?.let { out[it.groupValues[1]] = "$method $prefix$path" }
            }
            return out
        }
    }

    private val api = LocalApi(LocalRoutes.all, AlwaysOpenGate(), newRequestId = { "r" })

    private fun classOf(ops: Map<String, String>, id: String): String =
        ops[id] ?: if (ApiOperations.byId[id]?.opClass == OpClass.SHELL) "shell" else "unknown"

    @Test
    fun everyLocalOperationIsRegisteredOrPending() {
        val ops = opsJson()
        val local = ops.filterValues { it == "local" || it == "local_or_proxy" }.keys - NOT_A_ROUTE
        val missing = local.filter { it !in api.registered && it !in PENDING }
        assertEquals(emptyList(), missing, "local operations with neither a route nor a PENDING entry")
    }

    @Test
    fun pendingOnlyShrinks() {
        val ops = opsJson()
        val stale = PENDING.filter { it in api.registered }
        assertEquals(emptyList(), stale, "registered operations still listed in PENDING: remove them")
        val notLocal = PENDING.filter { ops[it] != "local" && ops[it] != "local_or_proxy" }
        assertEquals(emptyList(), notLocal, "PENDING names operations that are not local / local_or_proxy")
    }

    @Test
    fun noRouteIsRegisteredForAProxyOrHiddenOperation() {
        val ops = opsJson()
        val wrong = api.registered.filter { classOf(ops, it) !in setOf("local", "local_or_proxy", "shell") }
        assertEquals(emptyList(), wrong)
        assertTrue(NOT_A_ROUTE.none { it in api.registered }, "getBlob is answered by the intercept only")
    }

    @Test
    fun aRouteForAProxyOperationIsRejectedAtConstruction() {
        val r = runCatching { LocalApi(listOf(LocalRoute("getStatsSummary", "GET", "/api/v1/stats/summary") { Fallthrough }), AlwaysOpenGate(), newRequestId = { "r" }) }
        assertTrue(r.isFailure)
        val wrongPath = runCatching { LocalApi(listOf(LocalRoute("getMeta", "GET", "/api/v1/metadata") { Fallthrough }), AlwaysOpenGate(), newRequestId = { "r" }) }
        assertTrue(wrongPath.isFailure)
    }

    @Test
    fun operationClassesEqualOpsJson() {
        val fromTable = ApiOperations.all.filter { it.opClass != OpClass.SHELL }
            .associate { it.operationId to it.opClass.name.lowercase() }
        assertEquals(opsJson(), fromTable)
    }

    @Test
    fun operationPathsEqualTheOpenApiFiles() {
        val expected = yamlOperations(File(contracts, "openapi.yaml"), "/api/v1") +
            yamlOperations(File(contracts, "shell/shell.yaml"), "/shell/v1")
        assertEquals(123 + 18, expected.size)
        val actual = ApiOperations.all.associate { it.operationId to "${it.method} ${it.pathTemplate}" }
        assertEquals(expected, actual)
        assertEquals(18, ApiOperations.all.count { it.opClass == OpClass.SHELL })
    }

    @Test
    fun hiddenScopesFollowRuleP4() {
        val admin = setOf("listDevices", "createPairing", "getDevice", "updateDevice", "revokeDevice", "listTokens", "createToken", "getToken", "revokeToken")
        val import = setOf(
            "optimizePreset", "refreshDictionary", "importMedia", "importJpdbDeck", "importJpdbHistory", "putJpdbVocabResolution",
            "clearJpdbVocabResolution", "reparseMedia", "cancelJob",
        )
        val native = setOf("login", "logout", "pairDevice", "syncPush", "syncPull", "streamEvents", "ankiConnect")
        val hidden = ApiOperations.all.filter { it.opClass == OpClass.HIDDEN }
        assertEquals(admin + import + native, hidden.map { it.operationId }.toSet())
        hidden.forEach { op ->
            val expected = when (op.operationId) {
                in admin -> "admin"
                in import -> "import"
                else -> null
            }
            assertEquals(expected, op.requiredScope, op.operationId)
        }
    }

    @Test
    fun aLiteralSegmentBeatsAParameter() {
        assertEquals("getOwnDevice", ApiOperations.match("GET", "/api/v1/devices/self")?.first?.operationId)
        assertEquals("getDevice", ApiOperations.match("GET", "/api/v1/devices/0193a1b2-0000-7000-8000-000000000001")?.first?.operationId)
        assertEquals("bulkCards", ApiOperations.match("POST", "/api/v1/cards/bulk")?.first?.operationId)
        assertEquals("listHotKanji", ApiOperations.match("GET", "/api/v1/kanji/hot")?.first?.operationId)
        assertEquals("listHotWords", ApiOperations.match("GET", "/api/v1/words/hot")?.first?.operationId)
        assertEquals("getSyncStatus", ApiOperations.match("GET", "/shell/v1/sync")?.first?.operationId)
        assertEquals("syncNow", ApiOperations.match("POST", "/shell/v1/sync")?.first?.operationId)
    }

    @Test
    fun pathParametersArePercentDecoded() {
        val (op, params) = assertNotNull(ApiOperations.match("GET", "/api/v1/kanji/%E9%A3%9F/words"))
        assertEquals("listKanjiWords", op.operationId)
        assertEquals(mapOf("literal" to "食"), params)
        val (put, p2) = assertNotNull(ApiOperations.match("PUT", "/api/v1/decks/d1/items/e%3A1358280"))
        assertEquals("putDeckItem", put.operationId)
        assertEquals(mapOf("deck_id" to "d1", "item_key" to "e:1358280"), p2)
    }

    @Test
    fun noMatchForWrongMethodEmptySegmentOrUnknownPath() {
        assertEquals(null, ApiOperations.match("POST", "/api/v1/meta"))
        assertEquals(null, ApiOperations.match("GET", "/api/v1/notes/"))
        assertEquals(null, ApiOperations.match("GET", "/api/v1/nothing"))
        assertEquals(null, ApiOperations.match("GET", "/api/v2/meta"))
    }
}
