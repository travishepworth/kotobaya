package kotobaya.android.shell.cache

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executor
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.events.ShellEvents
import kotobaya.android.sync.NetworkMonitor
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SyncOutcome
import kotobaya.android.sync.memoryDriver
import kotobaya.contracts.sync.JsonObject
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.protocol.CycleResult
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A2b: the production wiring of the response cache. [ResponseCache.fromServices] over the four Koin parts, and the
 * stale marks that must reach `response_cache` when no cache hooks are set (a background sync with no WebView).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CacheWiringTest {
    private val driver = memoryDriver()
    private val db = AppDatabase(driver)
    private val store = SqlResponseCacheStore(db)
    private val now = 1_795_168_800_000L

    private fun events(fallback: () -> CacheHooks? = { null }) = ShellEvents(
        scope = CoroutineScope(Executor { }.asCoroutineDispatcher()),
        send = { },
        syncChanged = { 0L to 0 },
        syncStatus = { JsonObject(emptyMap()) },
        fallbackHooks = fallback,
    )

    private fun seed(op: String, path: String) {
        val key = CacheKey.of(op, path, "")
        store.put(CacheEntry(key, op, path, "", 200, "application/json", "{}", 2, null, now, now, false))
    }

    private fun stale(op: String, path: String): Boolean = assertNotNull(store.get(CacheKey.of(op, path, ""))).stale

    private val pulled = SyncOutcome.Ran(CycleResult.Completed(pushedOps = 0, pulledOps = 2, purgedRows = 0, pullAdvanced = true))

    @Test
    fun fromServicesWithAllFourPartsIsNonNullAndHooksTheEvents() {
        val ev = events()
        val parts: Map<KClass<*>, Any> = mapOf(
            AppDatabase::class to db,
            SqlDelightDeviceStore::class to SqlDelightDeviceStore(driver, db),
            NetworkMonitor::class to NetworkMonitor(ApplicationProvider.getApplicationContext()),
            ShellEvents::class to ev,
        )
        val services = object : ShellServices {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> get(type: KClass<T>): T = parts[type] as? T ?: error("no service ${type.simpleName}")
        }
        val errors = ArrayList<String>()
        val cache = ResponseCache.fromServices(services, Upstream { _, _, _ -> ShellResponse(500, emptyMap(), null) }, { "id" }) { m, e ->
            errors += "$m: $e"
        }
        assertNotNull(cache, "null here means every cached operation is 503 shell_uncached forever: $errors")
        assertSame(cache, ev.cacheHooks)
        assertEquals(emptyList(), errors)
    }

    @Test
    fun withoutHooksAPullMarksStoredRowsStaleThroughTheDatabase() {
        seed("getStatsForecast", "/api/v1/stats/forecast")
        seed("listParseOverrides", "/api/v1/parse-overrides")
        events { StaleMarks(store) }.onSyncOutcome(pulled)
        assertTrue(stale("getStatsForecast", "/api/v1/stats/forecast"))
        assertFalse(stale("listParseOverrides", "/api/v1/parse-overrides"), "no replica table invalidates parse overrides")
    }

    @Test
    fun withoutHooksARebuildMarksEveryRowStale() {
        seed("getStatsForecast", "/api/v1/stats/forecast")
        seed("listParseOverrides", "/api/v1/parse-overrides")
        events { StaleMarks(store) }.onSyncOutcome(SyncOutcome.Ran(CycleResult.RebuildPending(pushedOps = 0)))
        assertTrue(stale("getStatsForecast", "/api/v1/stats/forecast"))
        assertTrue(stale("listParseOverrides", "/api/v1/parse-overrides"))
    }

    @Test
    fun withoutHooksALocalWriteMarksStoredRowsStale() {
        seed("getDeckCoverage", "/api/v1/decks/d1/coverage")
        events { StaleMarks(store) }.onLocalWrite()
        assertTrue(stale("getDeckCoverage", "/api/v1/decks/d1/coverage"))
    }

    @Test
    fun theDefaultFallbackFindsTheAppDatabaseThroughKoin() {
        seed("getStatsForecast", "/api/v1/stats/forecast")
        startKoin { modules(module { single { db } }) }
        try {
            ShellEvents(CoroutineScope(Executor { }.asCoroutineDispatcher()), { }, { 0L to 0 }, { JsonObject(emptyMap()) })
                .onSyncOutcome(pulled)
        } finally {
            stopKoin()
        }
        assertTrue(stale("getStatsForecast", "/api/v1/stats/forecast"))
    }

    @Test
    fun noHooksAndNoKoinIsANoOp() {
        seed("getStatsForecast", "/api/v1/stats/forecast")
        ShellEvents.applyCacheMarks(pulled, ShellEvents.dbStaleMarks())
        assertFalse(stale("getStatsForecast", "/api/v1/stats/forecast"))
    }

    @Test
    fun applyCacheMarksWithStaleMarksIsWhatTheHubCallsWithNoShellEvents() {
        seed("getStatsForecast", "/api/v1/stats/forecast")
        ShellEvents.applyCacheMarks(pulled, StaleMarks(store))
        assertTrue(stale("getStatsForecast", "/api/v1/stats/forecast"))
    }
}
