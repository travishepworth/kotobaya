package kotobaya.android.shell.api.actions

import app.cash.sqldelight.db.SqlDriver
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.search.ReplicaCardQuery
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.ShellGate
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.actions.ActionsTestSupport.C1
import kotobaya.android.shell.api.actions.ActionsTestSupport.C2
import kotobaya.android.shell.api.actions.ActionsTestSupport.T0
import kotobaya.android.shell.api.actions.ActionsTestSupport.card
import kotobaya.android.shell.api.actions.ActionsTestSupport.device
import kotobaya.android.shell.api.actions.ActionsTestSupport.json
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.shellFeature
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SyncClient
import kotobaya.contracts.sync.JsonBool
import kotobaya.db.app.AppDatabase
import org.koin.core.KoinApplication
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * SHELL-L4 production wiring: the shell's Koin module binds [CardQuery], so a `bulkCards` call with a `query` selector
 * is answered by the device (no [Upstream] call) when the handlers' services come from Koin, as `KoinShellServices`
 * resolves them. The app-level singletons the shell module expects from other features (the app driver, the store,
 * the sync client, the dictionary) are those of a [kotobaya.android.shell.parity.ParityDevice].
 */
class BulkQueryWiringTest {
    private var app: KoinApplication? = null

    @AfterTest
    fun close() {
        app?.close()
    }

    @Test
    fun theShellModuleAnswersAQuerySelectorLocally() {
        val d = device()
        val base = module {
            single<SqlDriver>(named("syncAppDriver")) { d.driver }
            single<AppDatabase> { d.database }
            single<InstalledDictionary> { d.installedDictionary }
            single<SqlDelightDeviceStore> { d.store }
            single<SyncClient> { d.client }
            // the device clock of the test (production: AppShellIdentity over the system clock)
            single<ShellIdentity> { d.identity }
        }
        val koin = koinApplication {
            allowOverride(true)
            modules(checkNotNull(shellFeature.module), base)
        }.also { app = it }.koin
        assertTrue(koin.get<CardQuery>() is ReplicaCardQuery)

        val forwarded = ArrayList<String>()
        val api = LocalApi(
            routes = LocalRoutes.all,
            gate = object : ShellGate {
                override fun isPaired(): Boolean = true
                override fun isRebuildPending(): Boolean = false
                override suspend fun awaitReady() = Unit
            },
            upstream = Upstream { r, op, _ -> forwarded += op.operationId; error("unexpected forward of ${r.path}") },
            services = object : ShellServices {
                override fun <T : Any> get(type: KClass<T>): T = koin.get(type)
            },
            newRequestId = { "r" },
        )
        d.clock.now = T0 + 1_000
        val res = runBlocking {
            api.handle(
                ShellRequest.fromRaw(
                    "POST", "/api/v1/cards/bulk", "", mapOf("content-type" to "application/json"),
                    """{"query":"kind:meaning","action":{"type":"suspend"}}""",
                ),
            )
        }
        assertEquals(200, res.status, res.body)
        assertEquals("1", res.headers["kotobaya-local"])
        assertEquals(emptyList(), forwarded)
        assertEquals(2L, kotobaya.android.shell.parity.ParityJson.long(res.json().members["matched"]))
        assertEquals(2, d.minted().size)
        assertEquals(JsonBool(true), d.card(C1)["suspended"])
        assertEquals(JsonBool(true), d.card(C2)["suspended"])
    }
}
