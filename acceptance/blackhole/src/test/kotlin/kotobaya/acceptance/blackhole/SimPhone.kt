package kotobaya.acceptance.blackhole

import java.util.concurrent.Executor
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotobaya.android.dict.DictGapCache
import kotobaya.android.dict.DictLookup
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.net.MetaLimits
import kotobaya.android.notes.EntryMatcher
import kotobaya.android.notes.InstalledCandidateSource
import kotobaya.android.notes.NoteCreator
import kotobaya.android.notify.DueTrigger
import kotobaya.android.queue.EntryMaterializer
import kotobaya.android.review.InstalledRenderData
import kotobaya.android.search.ReplicaCardQuery
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ShellGate
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.actions.CardQuery
import kotobaya.android.shell.api.identity.ClientErrorIntake
import kotobaya.android.shell.api.identity.ClientErrorSink
import kotobaya.android.shell.api.identity.DictReleaseRef
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.cache.ResponseCache
import kotobaya.android.shell.cache.SqlResponseCacheStore
import kotobaya.android.shell.parity.ParityClock
import kotobaya.android.shell.parity.ParityDictionary
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityTemp
import kotobaya.android.sync.DbFile
import kotobaya.android.sync.FailureCounter
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.SyncClient
import kotobaya.android.sync.SyncCoordinator
import kotobaya.android.sync.TestServer
import kotobaya.android.sync.TestTransport
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonValue
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.bookkeeping
import kotobaya.sync.client.store.UuidV7Source

/**
 * A phone as the shell sees it (the wiring of `ParityDevice`, SHELL.md 8.2) but synced for real: the production
 * [SyncClient] over [net]'s transport onto [server], and [LocalApi] over every route with the black-hole [Upstream].
 * One file-backed database per phone (WAL, like production).
 */
class SimPhone(val deviceId: String, val server: TestServer, startMs: Long, val net: BlackHoleNet, seed: Int) {
    val clock = ParityClock(startMs)
    private val db = DbFile()
    val driver = db.open()
    val store = SqlDelightDeviceStore(driver)
    val client = SyncClient(store, net.transport(TestTransport(server)), clock)
    var localChanges: Int = 0
        private set
    val writes = LocalWrites(store, client.minter) { localChanges++ }
    private val dictionary: ParityDictionary = ParityDictionary.fixture
    private val dictStore: DictStore = dictionary.install(ParityTemp.next("blackhole-dict-$deviceId"))
    private val gapCache = DictGapCache(store, driver)
    val database = AppDatabase(driver)
    private val installedDictionary = InstalledDictionary(dictStore, null, gapCache)
    private val dictLookup = DictLookup(
        object : kotobaya.android.dict.PackageSource by kotobaya.android.shell.parity.JdbcPackageSource(dictStore.installed) {
            override fun packageEntry(seq: Long) = installedDictionary.packageEntry(seq)

            override fun packageKanji(literal: String) = installedDictionary.packageKanji(literal)
        },
        gapCache,
        remote = null,
        nowMs = { clock.nowMs() },
    )
    private val noteCreator: NoteCreator<SqlLearningTables> = NoteCreator(
        store = store,
        minter = client.minter,
        matcher = EntryMatcher(InstalledCandidateSource(installedDictionary)),
        ids = UuidV7Source(Random(seed)),
        clock = clock,
        kanjiData = { literal -> installedDictionary.kanji(literal)?.let { KanjiData(it.literal, it.meanings, it.onReadings, it.kunReadings, it.strokeCount) } },
        afterCreate = { localChanges++ },
        dictionary = InstalledRenderData(installedDictionary).let { data -> { seq: Long -> data.lookup(seq) } },
        dictionaryAvailable = { installedDictionary.isAvailable() },
    )
    private val failures = object : FailureCounter {
        override var failures: Int = 0
    }

    /** A cycle that never finishes would be a finding: the UI path must not join one. */
    private val coordinator = SyncCoordinator(
        scope = CoroutineScope(Dispatchers.Unconfined),
        runCycle = { error("the UI path started a sync cycle") },
        isPaired = { true },
        isOnline = { true },
        probe = { error("the UI path probed the network") },
        counter = failures,
    )
    private val identity = object : ShellIdentity {
        override fun deviceName(): String = "Black hole phone"
        override fun tokenId(): String? = "01931f3f-0000-7000-8000-0000000000b2"
        override fun serverVersion(): String? = null
        override fun coreVersion(): String = "kotobaya-core-blackhole"
        override fun dictRelease(): DictReleaseRef? = DictReleaseRef(dictionary.releaseId, "blackhole")
        override fun limits(): MetaLimits = MetaLimits.DEFAULTS
        override fun nowMs(): Long = clock.nowMs()
    }
    private val dueTrigger = DueTrigger(background = { it.run() }) { emptyList() }
    private val cardQuery: CardQuery = ReplicaCardQuery(driver, database, installedDictionary)

    /** The cache's background fetches: queued, never run on the UI path (they would wait on the black hole). */
    val backgroundQueue = ArrayDeque<Runnable>()
    val backgroundCalls = ArrayList<String>()
    private val cache = ResponseCache(
        store = SqlResponseCacheStore(database),
        upstream = Upstream { request, op, _ ->
            backgroundCalls += "${op.operationId} ${request.path}"
            ShellResponse(503)
        },
        scope = CoroutineScope(Executor { backgroundQueue.addLast(it) }.asCoroutineDispatcher()),
        isOnline = { true },
        hasPendingOps = { false },
        onUpdated = {},
        newRequestId = { "bg" },
        nowMs = { clock.nowMs() },
    )

    private val services = object : ShellServices {
        private val byType: Map<KClass<*>, Any> = mapOf(
            SqlDelightDeviceStore::class to store,
            SyncClient::class to client,
            LocalWrites::class to writes,
            Clock::class to clock,
            DictStore::class to dictStore,
            ParityDictionary::class to dictionary,
            DictGapCache::class to gapCache,
            DictLookup::class to dictLookup,
            ShellIdentity::class to identity,
            ClientErrorIntake::class to ClientErrorIntake(ClientErrorSink { }, clock::nowMs),
            AppDatabase::class to database,
            InstalledDictionary::class to installedDictionary,
            EntryMaterializer::class to noteCreator,
            SyncCoordinator::class to coordinator,
            DueTrigger::class to dueTrigger,
            CardQuery::class to cardQuery,
            Upstream::class to net.upstream,
        )

        override fun <T : Any> get(type: KClass<T>): T {
            @Suppress("UNCHECKED_CAST")
            return (byType[type] ?: error("the black-hole phone has no service ${type.simpleName}")) as T
        }
    }

    private var requestIds = 0
    val api = LocalApi(
        routes = LocalRoutes.all,
        gate = object : ShellGate {
            override fun isPaired(): Boolean = true
            override fun isRebuildPending(): Boolean = store.read { it.bookkeeping.rebuildPending }
            override suspend fun awaitReady() = Unit
        },
        upstream = net.upstream,
        services = services,
        newRequestId = { "bh-$deviceId-${++requestIds}" },
        cache = cache,
    )

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, deviceId) }
        client.startup.run(store)
        gapCache.recordServerRelease(dictionary.releaseId)
    }

    /** One request through [LocalApi.handle], as the bridge sends it. */
    fun request(method: String, path: String, query: String = "", body: JsonValue? = null): ShellResponse {
        val headers = if (body != null) mapOf("content-type" to "application/json") else emptyMap()
        val req = ShellRequest.fromRaw(method, path, query, headers, body?.let { ParityJson.encode(it) })
        return runBlocking { api.handle(req) }
    }

    fun get(path: String, query: String = ""): ShellResponse = request("GET", path, query)

    fun post(path: String, body: JsonObject): ShellResponse = request("POST", path, "", body)

    /** A background sync cycle (never on the UI path). */
    fun sync(): CycleResult = runBlocking { client.cycle.run() }

    fun tables(): SqlLearningTables = store.read { it.learning }
}
