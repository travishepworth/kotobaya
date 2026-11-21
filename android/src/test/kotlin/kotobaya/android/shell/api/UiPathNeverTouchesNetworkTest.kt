package kotobaya.android.shell.api

import android.app.Application
import java.util.concurrent.Executor
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotobaya.android.dict.DictGapCache
import kotobaya.android.dict.DictLookup
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.net.Reachability
import kotobaya.android.notify.DueTrigger
import kotobaya.android.queue.EntryMaterializer
import kotobaya.android.shell.api.actions.CardQuery
import kotobaya.android.shell.api.identity.ClientErrorIntake
import kotobaya.android.shell.api.identity.ClientErrorSink
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.cache.CacheEntry
import kotobaya.android.shell.cache.CacheKey
import kotobaya.android.shell.cache.ResponseCache
import kotobaya.android.shell.cache.SqlResponseCacheStore
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityDictionary
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.android.sync.FailureCounter
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SyncClient
import kotobaya.android.sync.SyncCoordinator
import kotobaya.contracts.sync.JsonValue
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.store.Clock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A2, SHELL.md rule L-9 (normative test): with an [Upstream] that records every call (each one is a failure) and a
 * [SyncCoordinator] whose cycle never finishes, every `local` and `local_or_proxy` operation of `ops.json` is driven
 * over a seeded replica and every `cached` one with and without a cache row; the background fetch the cache starts runs
 * on a scheduler this test never runs.
 *
 * Requests come from every parity scenario (its own seed, its own steps) plus [EXTRA] over the seed of
 * `bulk-cards-all-actions-local`. The operation list is read from `ops.json`, so a new operation without a request
 * here fails [everyLocalAndCachedOperationAnswersWithoutTheNetwork].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UiPathNeverTouchesNetworkTest {
    data class Req(val method: String, val path: String, val query: String = "", val body: String? = null)

    companion object {
        /**
         * `local` operations that still reach the upstream on at least one request (no handler yet, or a handler's
         * [Fallthrough]), with the ticket that makes them local. LF-X1: all have landed, so the list is empty (the strict version).
         */
        val STILL_FORWARDS: Map<String, String> = emptyMap()

        /** Answered by the blob intercept (SHELL.md 2.7), never through [LocalApi]. */
        val NOT_THROUGH_THE_BRIDGE: Set<String> = setOf("getBlob")

        const val SEED_SCENARIO = "bulk-cards-all-actions-local"
        private const val N1 = "0193a1b2-0001-7000-8000-000000000001"
        private const val C1 = "0193a1b2-0002-7000-8000-000000000010"
        private const val D1 = "0193a1b2-0004-7000-8000-000000000001"
        private const val D2 = "0193a1b2-0004-7000-8000-000000000002"
        private const val MEDIA = "0193a1b2-0009-7000-8000-000000000001"
        private const val KANJI = "%E9%A3%9F"
        private const val JSON = "application/json"

        /** Operations the parity scenarios do not reach (and a few they do), over [SEED_SCENARIO]'s seed, in order. */
        val EXTRA: List<Req> = listOf(
            Req("GET", "/api/v1/meta"),
            Req("GET", "/api/v1/auth/session"),
            Req(
                "POST", "/api/v1/client-errors",
                body = """{"items":[{"client":"web","app_version":"1","occurred_at":"2026-11-20T10:00:00Z","level":"error","kind":"exception","message":"x"}]}""",
            ),
            Req("GET", "/api/v1/settings"),
            Req("GET", "/api/v1/note-types"),
            Req("GET", "/api/v1/presets"),
            Req("GET", "/api/v1/dict/search", "q=$KANJI"),
            Req("GET", "/api/v1/dict/search", "q=zzzzzz"),
            Req("GET", "/api/v1/dict/entries/1000360"),
            Req("GET", "/api/v1/dict/entries/9999999"),
            Req("GET", "/api/v1/dict/entries/1000360/examples"),
            Req("GET", "/api/v1/dict/headwords", "seq=1000360,1000470,9999999"),
            Req("GET", "/api/v1/dict/release"),
            Req("GET", "/api/v1/kanji/hot"),
            Req("GET", "/api/v1/kanji/$KANJI"),
            Req("GET", "/api/v1/kanji/$KANJI/words"),
            Req("GET", "/api/v1/notes"),
            Req("GET", "/api/v1/notes/$N1"),
            Req("GET", "/api/v1/cards", "q=deck%3AInbox"),
            Req("GET", "/api/v1/cards/$C1"),
            Req("POST", "/api/v1/drill/session", body = """{"source":"hot_words"}"""),
            Req("POST", "/api/v1/drill/session", body = """{"source":"cards","card_ids":["$C1"]}"""),
            Req("GET", "/api/v1/decks"),
            Req("GET", "/api/v1/decks/$D1"),
            Req("GET", "/api/v1/decks/$D1/items"),
            Req("POST", "/api/v1/decks", body = """{"type":"user","name":"New deck"}"""),
            Req("PATCH", "/api/v1/decks/$D2", body = """{"name":"Renamed"}"""),
            Req("POST", "/api/v1/decks/$D1/items", body = """{"items":[{"entry_seq":1000470}]}"""),
            Req("PUT", "/api/v1/decks/$D1/items/e%3A1000510", body = "{}"),
            Req("DELETE", "/api/v1/decks/$D1/items/e%3A1000360"),
            Req("DELETE", "/api/v1/decks/$D2"),
            Req("GET", "/api/v1/media"),
            Req("GET", "/api/v1/media/$MEDIA"),
            Req("PATCH", "/api/v1/media/$MEDIA", body = """{"title":"x"}"""),
            Req("DELETE", "/api/v1/media/$MEDIA"),
            Req("GET", "/api/v1/words/hot"),
            Req("GET", "/api/v1/words/leeches"),
            Req("GET", "/api/v1/words/1000360/status"),
            Req("GET", "/api/v1/confusables"),
            Req("GET", "/api/v1/words/1000360/confusables"),
            Req("GET", "/api/v1/grammar"),
            Req("GET", "/api/v1/stats/summary"),
            Req("GET", "/api/v1/review/queue"),
            // cached: each is driven twice by the test (no row, then a row)
            Req("GET", "/api/v1/deck-lists"),
            Req("GET", "/api/v1/decks/$D1/coverage"),
            Req("GET", "/api/v1/media/$MEDIA/coverage"),
            Req("GET", "/api/v1/media/$MEDIA/sentences", "limit=20"),
            Req("GET", "/api/v1/sentences", "limit=20&cursor="),
            Req("GET", "/api/v1/sentences/0193a1b2-0010-7000-8000-000000000001"),
            Req("GET", "/api/v1/grammar/0193a1b2-0011-7000-8000-000000000001/examples"),
            Req("GET", "/api/v1/stats/forecast"),
            Req("GET", "/api/v1/stats/known-words"),
            Req("GET", "/api/v1/stats/calibration"),
            Req("GET", "/api/v1/parse-overrides"),
        )
    }

    /** One device under test: the parity device's replica and services, a recording upstream, a hanging coordinator. */
    private inner class Harness(deviceId: String, clockMs: Long) {
        val device = ParityDevice(deviceId, clockMs, emptyList())
        val forwarded = ArrayList<String>()
        val upstream = Upstream { _, op, _ ->
            forwarded += op.operationId
            error("L-9: ${op.operationId} reached the network")
        }
        private val failures = object : FailureCounter {
            override var failures: Int = 0
        }

        /** A cycle never finishes: a handler that joined or waited for one would hang (and be reported). */
        val coordinator = SyncCoordinator(
            scope = CoroutineScope(Dispatchers.Default),
            runCycle = { awaitCancellation() },
            isPaired = { true },
            isOnline = { true },
            probe = { Reachability.Reachable(true) },
            counter = failures,
        )

        /** The cache's background work: queued, never run. */
        val unrunBackground = ArrayDeque<Runnable>()
        val cacheStore = SqlResponseCacheStore(device.database)
        val cache = ResponseCache(
            store = cacheStore,
            upstream = upstream,
            scope = CoroutineScope(Executor { unrunBackground.addLast(it) }.asCoroutineDispatcher()),
            isOnline = { true },
            hasPendingOps = { false },
            onUpdated = {},
            newRequestId = { "bg" },
            nowMs = { device.clock.nowMs() },
        )

        private val services = object : ShellServices {
            private val byType: Map<KClass<*>, Any> = mapOf(
                SqlDelightDeviceStore::class to device.store,
                SyncClient::class to device.client,
                LocalWrites::class to device.writes,
                Clock::class to device.clock,
                DictStore::class to device.dictStore,
                ParityDictionary::class to device.dictionary,
                DictGapCache::class to device.gapCache,
                DictLookup::class to device.dictLookup,
                ShellIdentity::class to device.identity,
                ClientErrorIntake::class to ClientErrorIntake(ClientErrorSink { }, device.clock::nowMs),
                AppDatabase::class to device.database,
                InstalledDictionary::class to device.installedDictionary,
                EntryMaterializer::class to device.noteCreator,
                SyncCoordinator::class to coordinator,
                DueTrigger::class to DueTrigger(background = { it.run() }) { emptyList() },
                CardQuery::class to device.cardQuery,
                Upstream::class to upstream,
            )

            override fun <T : Any> get(type: KClass<T>): T {
                @Suppress("UNCHECKED_CAST")
                return (byType[type] ?: error("no service ${type.simpleName}")) as T
            }
        }

        val api = LocalApi(
            routes = LocalRoutes.all,
            gate = object : ShellGate {
                override fun isPaired(): Boolean = true
                override fun isRebuildPending(): Boolean = false
                override suspend fun awaitReady() = Unit
            },
            upstream = upstream,
            services = services,
            newRequestId = { "r" },
            cache = cache,
        )

        fun handle(r: Req): ShellResponse? {
            val headers = if (r.body != null) mapOf("content-type" to JSON) else emptyMap()
            return runBlocking { withTimeoutOrNull(10_000) { api.handle(ShellRequest.fromRaw(r.method, r.path, r.query, headers, r.body)) } }
        }
    }

    init {
        Class.forName("org.sqlite.JDBC") // Robolectric's class loader does not run the JDBC service loader
    }

    private val covered = LinkedHashSet<String>()
    private val forwarded = LinkedHashMap<String, String>()
    private val problems = ArrayList<String>()

    private fun opOf(r: Req): String? = ApiOperations.match(r.method, r.path)?.first?.operationId

    private fun drive(h: Harness, r: Req, where: String) {
        val op = opOf(r) ?: return run { problems += "$where: no operation for ${r.method} ${r.path}" }
        covered += op
        val before = h.forwarded.size
        val res = h.handle(r)
        if (res == null) problems += "$where: $op did not answer within 10 s (it waited for the network or a sync cycle)"
        if (h.coordinator.cyclesRun > 0) problems += "$where: $op started a sync cycle"
        if (h.forwarded.size > before) forwarded.putIfAbsent(op, "$where ${r.method} ${r.path}?${r.query}")
    }

    private fun cachedRow(h: Harness, r: Req, op: String) {
        val query = CacheKey.canonicalQuery(r.query)
        val key = CacheKey.of(op, r.path, query)
        h.cacheStore.put(CacheEntry(key, op, r.path, query, 200, JSON, """{"cached":true}""", 15, null, 0, 0, true))
    }

    @Test
    fun everyLocalAndCachedOperationAnswersWithoutTheNetwork() {
        val ops = RouteTableTest.opsJson()
        val cachedOps = ops.filterValues { it == "cached" }.keys
        val mustAnswer = ops.filterValues { it == "local" || it == "local_or_proxy" || it == "cached" }.keys - NOT_THROUGH_THE_BRIDGE

        // 1. every parity scenario, on its own seed
        for (scenario in ParityScenario.all()) {
            val h = Harness(scenario.deviceId, scenario.clockMs)
            h.device.pull(scenario.seedLog, "seed of ${scenario.id}")
            scenario.steps.indices.forEach { i ->
                h.device.clock.now = scenario.atMs(i)
                if (scenario.isApply(i)) {
                    h.device.pull(scenario.applyOps(i), "${scenario.id} step $i")
                } else {
                    val s = scenario.request(i)
                    drive(h, Req(s.method, s.path, s.query, s.body?.let { b: JsonValue -> ParityJson.encode(b) }), "${scenario.id} step $i")
                }
            }
        }

        // 2. the extra requests on one seeded replica; cached ones without and then with a row
        val seed = ParityScenario.load(SEED_SCENARIO)
        val h = Harness(seed.deviceId, seed.clockMs)
        h.device.pull(seed.seedLog, "seed")
        for (r in EXTRA) {
            val op = opOf(r)
            if (op in cachedOps && op != null) {
                drive(h, r, "extra (no row)")
                val miss = h.handle(r)
                if (miss?.status != 503 || miss.body?.contains("\"shell_uncached\"") != true) problems += "$op without a row: ${miss?.status} ${miss?.body}"
                cachedRow(h, r, op)
                val hit = h.handle(r)
                if (hit?.status != 200 || hit.body != """{"cached":true}""") problems += "$op with a row: ${hit?.status} ${hit?.body}"
                if (hit?.headers?.get("kotobaya-local") != "1") problems += "$op hit without kotobaya-local"
            } else {
                drive(h, r, "extra")
            }
        }
        assertTrue(h.unrunBackground.isNotEmpty(), "the cache scheduled its background fetches (and the test never ran them)")
        assertTrue(h.forwarded.none { it in cachedOps }, "a cached operation reached the upstream: ${h.forwarded}")

        val notDriven = mustAnswer - covered
        assertEquals(emptySet(), notDriven, "operations with no request in this test: add one to EXTRA")
        val unexpected = forwarded.filterKeys { it !in STILL_FORWARDS }
        assertEquals(emptyMap(), unexpected, "reached the network on the UI path (rule L-9)")
        assertEquals(emptyList(), problems)
        val nowLocal = STILL_FORWARDS.keys.filter { it !in forwarded }
        if (nowLocal.isNotEmpty()) println("UiPathNeverTouchesNetworkTest: no longer forwarded, remove from STILL_FORWARDS: $nowLocal")
        val notLocal = STILL_FORWARDS.keys.filter { ops[it] != "local" && ops[it] != "local_or_proxy" }
        assertEquals(emptyList(), notLocal, "STILL_FORWARDS names operations that are not local")
    }
}
