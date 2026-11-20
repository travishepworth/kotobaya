package kotobaya.android.shell.api.browse

import java.net.URLEncoder
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.search.ReplicaCardQuery
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.ShellGate
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.actions.CardQuery
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.db.app.AppDatabase

/**
 * LF-A5: the browse routes of [d] with the device evaluator ([ReplicaCardQuery], as production must bind it) as the
 * [CardQuery], and an [Upstream] that fails the test when called: nothing on the search path may reach the network.
 */
internal class LocalSearchApi(val d: ParityDevice, dictionary: InstalledDictionary? = d.installedDictionary) {
    val query = ReplicaCardQuery(d.driver, d.database, dictionary)

    private val api = LocalApi(
        routes = routes,
        gate = object : ShellGate {
            override fun isPaired(): Boolean = true
            override fun isRebuildPending(): Boolean = false
            override suspend fun awaitReady() = Unit
        },
        upstream = Upstream { _, op, _ -> throw AssertionError("${op.operationId} reached the upstream") },
        services = object : ShellServices {
            private val byType: Map<KClass<*>, Any> = mapOf(
                ShellIdentity::class to d.identity, CardQuery::class to query, AppDatabase::class to d.database,
                SqlDelightDeviceStore::class to d.store,
            )

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> get(type: KClass<T>): T = byType[type] as T? ?: error("no ${type.simpleName}")
        },
        newRequestId = { "r" },
    )

    fun cards(query: String, extra: String = ""): ShellResponse = runBlocking {
        val q = "query=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20") + (if (extra.isEmpty()) "" else "&$extra")
        api.handle(ShellRequest.fromRaw("GET", "/api/v1/cards", q, emptyMap(), null))
    }
}
