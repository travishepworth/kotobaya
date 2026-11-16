package kotobaya.android.shell.parity

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import kotobaya.android.dict.DictGapCache
import kotobaya.android.dict.DictLookup
import kotobaya.android.dict.DictStore
import kotobaya.android.shell.api.LocalApi
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
 * [ParityDictionary], [DictGapCache], [DictLookup] (SHELL-L9). An L ticket whose handler needs something else registers it here.
 */
class ParityDevice(
    val deviceId: String,
    startMs: Long,
    routes: List<LocalRoute>,
    dictDir: File = File.createTempFile("parity-dictstore", "").also { it.delete() },
) {
    val clock = ParityClock(startMs)
    val log = SeedLog(clock)
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AppDatabase.Schema.create(it) }
    val store = SqlDelightDeviceStore(driver)
    val client = SyncClient(store, log, clock)
    var localChanges: Int = 0
        private set
    val writes = LocalWrites(store, client.minter) { localChanges++ }
    val dictionary: ParityDictionary = ParityDictionary.fixture
    val dictStore: DictStore = dictionary.install(dictDir)

    /** SHELL-L9: the sidecar cache in the device's learning database, empty (no network in a scenario). */
    val gapCache = DictGapCache(store, driver)

    /** SHELL-L9: the one L-7 lookup over the installed fixture (JDBC) and [gapCache]; never online here. */
    val dictLookup = DictLookup(JdbcPackageSource(dictStore.installed), gapCache, remote = null)

    /** Requests [LocalApi] forwarded upstream (a scenario never goes online: each one is a finding). */
    val forwarded = ArrayList<String>()

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
        upstream = Upstream { request, op, requestId ->
            forwarded += "${op.operationId} ${request.method} ${request.path}"
            OfflineUpstream.forward(request, op, requestId)
        },
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
