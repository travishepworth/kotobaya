package kotobaya.android.shell.parity

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import kotobaya.android.dict.DictGapCache
import kotobaya.android.dict.DictGapRemote
import kotobaya.android.dict.DictLookup
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.DictEntryDetail
import kotobaya.android.dict.KanjiDetail
import kotobaya.android.dict.PackageSource
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.actions.CardQuery
import kotobaya.android.shell.api.actions.LocalCardQuery
import kotobaya.android.shell.api.LocalRoute
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.OfflineUpstream
import kotobaya.android.shell.api.ShellGate
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.identity.ClientErrorIntake
import kotobaya.android.shell.api.identity.ClientErrorSink
import kotobaya.android.shell.api.identity.DictReleaseRef
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.net.MetaLimits
import kotobaya.android.report.ClientErrorItem
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.SyncClient
import kotobaya.android.sync.FailureCounter
import kotobaya.android.sync.SyncCoordinator
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.notes.EntryMatcher
import kotobaya.android.notify.DueListener
import kotobaya.android.notify.DueTrigger
import kotobaya.android.notes.InstalledCandidateSource
import kotobaya.android.notes.NoteCreator
import kotobaya.android.queue.EntryMaterializer
import kotobaya.android.review.InstalledRenderData
import kotobaya.contracts.render.KanjiData
import kotobaya.sync.client.store.UuidV7Source
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.SyncLimits
import kotobaya.sync.client.protocol.SyncTransport
import kotobaya.sync.client.protocol.TransportResponse
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.bookkeeping

/** A seed or apply op the device's pull-apply path did not take: the scenario fails loudly (SHELL.md 8.2). */
class SeedNotApplied(message: String) : AssertionError(message)

/** The injected device clock (SHELL.md 8.2): set to `clock_ms` while seeding and to each step's `at_ms`. */
class ParityClock(var now: Long) : Clock {
    override fun nowMs(): Long = now
}

/**
 * The seed device's ops as the server's log (pull side only): every op gets `seq` = 1, 2, … in arrival order and
 * `caused_by_op_id: null` (SHELL.md 8.2, shell column), and is served by `GET /sync/pull`. Push is refused: the device
 * under test never goes online during a scenario (its own ops stay in `pending_op`).
 */
class SeedLog(private val clock: Clock) : SyncTransport {
    private val log = ArrayList<JsonObject>()
    var pulls: Int = 0
        private set

    val size: Int get() = log.size

    /** Appends [ops] (pushed form) with the next seqs. */
    fun append(ops: List<JsonObject>) {
        for (op in ops) {
            val seq = log.size + 1L
            log += JsonObject(LinkedHashMap(op.members).apply {
                put("seq", JsonNumber(seq.toDouble()))
                put("caused_by_op_id", JsonNull)
            })
        }
    }

    override suspend fun push(body: String): TransportResponse = TransportResponse.NetworkError("parity: the device under test is offline")

    override suspend fun pull(since: Long): TransportResponse {
        pulls++
        val ops = log.drop(since.coerceAtMost(log.size.toLong()).toInt())
        val high = JsonNumber(log.size.toDouble())
        val page = linkedMapOf<String, JsonValue>(
            "ops" to JsonArray(ops),
            "next_since" to high,
            "has_more" to JsonBool(false),
            "high_water_seq" to high,
            "durable_seq" to high,
            "server_time" to JsonNumber(clock.nowMs().toDouble()),
            "log_epoch" to JsonString(LOG_EPOCH),
        )
        return TransportResponse.Ok(ParityJson.encode(JsonObject(page)))
    }

    override suspend fun limits(): SyncLimits = SyncLimits.DEFAULT

    companion object {
        const val LOG_EPOCH: String = "00000000-0000-7000-8000-0000000000e1"
    }
}

/**
 * The device under test of SHELL.md 8.2 (shell column): a fresh [SqlDelightDeviceStore] on an in-memory JDBC SQLite
 * database with only the built-in rows, `sync_state.device_id` = the scenario's `device_id`, paired (fake token: the
 * gate says so), the production sync wiring ([SyncClient]: the device applier, the pull engine, the minter) over
 * [SeedLog], the dictionary fixture installed, and [LocalApi] over [routes].
 *
 * Handlers get their dependencies from [services] by type, like `KoinShellServices` in production. Registered:
 * [SqlDelightDeviceStore], [SyncClient], `LocalWrites<SqlLearningTables>`, [Clock], [DictStore],
 * [ParityDictionary], [DictGapCache], [DictLookup] (SHELL-L9); [Upstream] (SHELL-L5, [server]); [DueTrigger] (SHELL-L3, counted in `dueChanges`); [CardQuery] (SHELL-L4); [AppDatabase], [InstalledDictionary] (steps 1-2 over the
 * fixture and the cache), [EntryMaterializer] (`NoteCreator`), [SyncCoordinator] (idle, never runs) (SHELL-L2). An L
 * ticket whose handler needs something else registers it here.
 *
 * SHELL-L2: reading dictionary details needs Android's `SQLiteDatabase` and real `org.json`, so a test whose scenario
 * reads entries runs under Robolectric. After every successful pull `last_sync_at` is set (the seed log is one complete
 * pull, `has_more` false), so `materializeAllowed` holds as on a synced phone.
 */
class ParityDevice(
    val deviceId: String,
    startMs: Long,
    routes: List<LocalRoute>,
    dictDir: File = ParityTemp.next("dictstore"),
    /** SHELL-L5: L-7 step 3 (the server's `getDictEntry` / `getKanji`); null = never online (a scenario). */
    remote: DictGapRemote? = null,
    /** SHELL-L5: false = no dictionary package installed on this device. */
    withDictionary: Boolean = true,
) {
    val clock = ParityClock(startMs)
    val log = SeedLog(clock)
    /** The in-memory database under [store] (SHELL-L4: bound as the app driver in the Koin wiring test). */
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AppDatabase.Schema.create(it) }
    val store = SqlDelightDeviceStore(driver)
    val client = SyncClient(store, log, clock)
    var localChanges: Int = 0
        private set
    val writes = LocalWrites(store, client.minter) { localChanges++ }
    val dictionary: ParityDictionary = ParityDictionary.fixture
    val dictStore: DictStore = if (withDictionary) dictionary.install(dictDir) else DictStore(dictDir)

    /** SHELL-L9: the sidecar cache in the device's learning database, empty (no network in a scenario). */
    val gapCache = DictGapCache(store, driver)


    /** SHELL-L2: the app database the queue loader reads (the same in-memory driver as [store]). */
    val database = AppDatabase(driver)

    /** SHELL-L2: L-7 steps 1-2 as production wires it (installed package, then the sidecar cache). */
    val installedDictionary = InstalledDictionary(dictStore, null, gapCache)

    /**
     * SHELL-L9: the one L-7 lookup over the installed fixture (JDBC) and [gapCache]; never online here. SHELL-L4: entry
     * and kanji details (a re-match in `updateNote`) come from [installedDictionary] as in production, so a test that
     * reaches them runs under Robolectric; release, scope and presence stay on JDBC (plain JVM).
     */
    val dictLookup = DictLookup(
        object : PackageSource by JdbcPackageSource(dictStore.installed) {
            override fun packageEntry(seq: Long): DictEntryDetail? = installedDictionary.packageEntry(seq)

            override fun packageKanji(literal: String): KanjiDetail? = installedDictionary.packageKanji(literal)
        },
        gapCache,
        remote = remote,
        nowMs = { clock.nowMs() },
    )

    /** SHELL-L2: C-27 materialization as production wires it (`NoteCreator` over the device's minter). */
    val noteCreator: NoteCreator<SqlLearningTables> = NoteCreator(
        store = store,
        minter = client.minter,
        matcher = EntryMatcher(InstalledCandidateSource(installedDictionary)),
        ids = UuidV7Source(Random(7)),
        clock = clock,
        kanjiData = { literal -> installedDictionary.kanji(literal)?.let { KanjiData(it.literal, it.meanings, it.onReadings, it.kunReadings, it.strokeCount) } },
        afterCreate = { localChanges++ },
        dictionary = InstalledRenderData(installedDictionary).let { data -> { seq: Long -> data.lookup(seq) } },
        dictionaryAvailable = { installedDictionary.isAvailable() },
    )

    /** SHELL-L2: never runs a cycle (a scenario is offline); `running` false and no failures unless a test says so. */
    val failures = object : FailureCounter {
        override var failures: Int = 0
    }
    val coordinator = SyncCoordinator(
        scope = CoroutineScope(Dispatchers.Unconfined),
        runCycle = { error("parity: the device under test never syncs") },
        isPaired = { true },
        isOnline = { false },
        probe = { error("parity: the device under test is offline") },
        counter = failures,
    )

    /** Requests [LocalApi] forwarded upstream (a scenario never goes online: each one is a finding). */
    val forwarded = ArrayList<String>()

    /**
     * SHELL-L5: the server behind [Upstream] (rules P-1 … P-3) when a test puts the device online; null = offline, every
     * forward answers `503 shell_offline` without a network attempt. Handlers that forward themselves (L-7 "forward and
     * merge") get the same [Upstream] from the services.
     */
    var server: Upstream? = null

    private val upstream = Upstream { request, op, requestId ->
        forwarded += "${op.operationId} ${request.method} ${request.path}"
        (server ?: OfflineUpstream).forward(request, op, requestId)
    }

    /** SHELL-L1: what `getPrincipal` / `getMeta` read besides the replica (a scenario never fetched `/meta`). */
    val identity = object : ShellIdentity {
        override fun deviceName(): String = "Parity phone"
        override fun tokenId(): String? = "01931f3f-0000-7000-8000-0000000000b2"
        override fun serverVersion(): String? = null
        override fun coreVersion(): String = "kotobaya-core-parity"
        override fun dictRelease(): DictReleaseRef? = DictReleaseRef(dictionary.releaseId, "parity")
        override fun limits(): MetaLimits = MetaLimits.DEFAULTS
        override fun nowMs(): Long = clock.nowMs()
    }

    /** SHELL-L1: the reports `reportClientErrors` accepted, in order. */
    val reported = ArrayList<ClientErrorItem>()
    private val clientErrors = ClientErrorIntake(ClientErrorSink { reported += it }, clock::nowMs)

    /** SHELL-L3: how often `answerReview` / `undoReview` asked for the due alarm and widgets to be recomputed. */
    var dueChanges: Int = 0
        private set
    private val dueTrigger = DueTrigger(background = { it.run() }) { listOf(DueListener { dueChanges++ }) }

    /** SHELL-L4: the `bulkCards` `query` selector over the device's own replica (as production binds it). */
    val cardQuery: CardQuery = LocalCardQuery(driver, database, installedDictionary)

    private val services = MapServices(
        mapOf(
            SqlDelightDeviceStore::class to store,
            SyncClient::class to client,
            LocalWrites::class to writes,
            Clock::class to clock,
            DictStore::class to dictStore,
            ParityDictionary::class to dictionary,
            DictGapCache::class to gapCache,
            DictLookup::class to dictLookup,
            ShellIdentity::class to identity,
            ClientErrorIntake::class to clientErrors,
            AppDatabase::class to database,
            InstalledDictionary::class to installedDictionary,
            EntryMaterializer::class to noteCreator,
            SyncCoordinator::class to coordinator,
            DueTrigger::class to dueTrigger,
            CardQuery::class to cardQuery,
            Upstream::class to upstream,
        ),
    )

    private var requestIds = 0

    val api = LocalApi(
        routes = routes,
        gate = object : ShellGate {
            override fun isPaired(): Boolean = true
            override fun isRebuildPending(): Boolean = store.read { it.bookkeeping.rebuildPending }
            override suspend fun awaitReady() = Unit
        },
        upstream = upstream,
        services = services,
        newRequestId = { "parity-${++requestIds}" },
    )

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, deviceId) }
        // As SyncBootstrap at app start: the startup replay before anything reads or applies.
        client.startup.run(store)
        // L-7 authority: the last known server release is the fixture's (the server under the goldens serves it)
        gapCache.recordServerRelease(dictionary.releaseId)
    }

    /**
     * [ops] (pushed form, minted by another device) arrive by pull: appended to the log with the next seqs and pulled
     * through the production path ([kotobaya.sync.client.protocol.SyncEngine.pullAll] with the device applier).
     */
    fun pull(ops: List<JsonObject>, what: String) {
        log.append(ops)
        val r = runBlocking { client.engine.pullAll() }
        if (r !is CycleResult.Completed || r.pulledOps != ops.size) throw SeedNotApplied("$what: pull of ${ops.size} ops ended with $r")
        // a complete pull, as a finished sync cycle records it (C-27 materializeAllowed)
        store.transaction { it.bookkeeping.lastSyncAt = clock.nowMs() }
    }

    /** One request step through [LocalApi.handle], as the bridge would send it. */
    fun request(method: String, path: String, rawQuery: String, body: JsonValue?): ShellResponse {
        val headers = if (body != null) mapOf("content-type" to "application/json") else emptyMap()
        val req = ShellRequest.fromRaw(method, path, rawQuery, headers, body?.let { ParityJson.encode(it) })
        return runBlocking { api.handle(req) }
    }

    private class MapServices(private val byType: Map<KClass<*>, Any>) : ShellServices {
        override fun <T : Any> get(type: KClass<T>): T {
            @Suppress("UNCHECKED_CAST")
            return (byType[type] ?: error("the parity harness has no service ${type.simpleName}; register it in ParityDevice")) as T
        }
    }
}

/** Helper used by the seed tests: the learning tables of [device], read outside a transaction. */
fun ParityDevice.tables(): SqlLearningTables = store.read { it.learning }

/** One temp root per JVM for every parity test, removed on exit (per-device dirs used to leak 2.7 MB each into /tmp). */
object ParityTemp {
    private val root: File = kotlin.io.path.createTempDirectory("parity-root").toFile().also { r ->
        Runtime.getRuntime().addShutdownHook(Thread { r.deleteRecursively() })
    }
    private val counter = java.util.concurrent.atomic.AtomicInteger()

    fun next(prefix: String): File = File(root, "$prefix-${counter.incrementAndGet()}")
    fun file(name: String): File = File(root, name)
}
