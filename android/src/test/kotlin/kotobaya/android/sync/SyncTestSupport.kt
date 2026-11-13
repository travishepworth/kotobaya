package kotobaya.android.sync

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.Emitter
import kotobaya.contracts.sync.HlcState
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.replay.ReplayEngine
import kotobaya.db.app.AppDatabase
import kotobaya.replay.DefaultReplayEngine
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.PushBody
import kotobaya.sync.PushBodyParser
import kotobaya.sync.PushElement
import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.DeviceDiagnostics
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.apply.StartupReplay
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.SyncEngine
import kotobaya.sync.client.protocol.SyncLimits
import kotobaya.sync.client.protocol.SyncTransport
import kotobaya.sync.client.protocol.TransportResponse
import kotobaya.sync.client.rebuild.DeviceSyncCycle
import kotobaya.sync.client.rebuild.Rebuild
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotobaya.sync.client.store.bookkeeping

// Test support for the T303c store tests. TestServer / TestTransport / the scenario helpers mirror
// core's commonTest ones (T303a/T303b), which the android module cannot depend on; the scenario
// and example files are read from contracts/sync/examples directly.

/** Runs a suspend block that never suspends (in-memory transports) to completion. */
fun <T> runSync(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "the block suspended; test transports must answer synchronously" }.getOrThrow()
}

class TestClock(var now: Long) : Clock {
    override fun nowMs(): Long = now
}

private val examplesDir = File("../contracts/sync/examples")

private fun decodeObject(text: String): JsonObject = DefaultCanonicalJson.decode(text).value as JsonObject

/** The op examples of `contracts/sync/examples/ops`, decoded (T303b's `Examples`). */
object Examples {
    val all: Map<String, Op> = File(examplesDir, "ops").listFiles { f -> f.extension == "json" }!!
        .associate { f ->
            val tree = decodeObject(f.readText())
            val source = if ("seq" in tree.members) OpSource.PULLED else OpSource.PUSHED
            f.name to assertIs<OpDecodeResult.Known>(DefaultOpCodec.decodeTree(tree, source), f.name).op
        }

    fun op(name: String): Op = all.getValue(name)

    /** Creation examples in dependency order (the examples share one id universe). */
    val world: List<String> = listOf(
        "note_type.created.json", "grammar.created.json", "fsrs_params.created.json", "media.created.json",
        "deck.created.json", "deck.created.smart.json", "deck.created.media.json",
        "note.created.json", "note.created.duplicate.json", "note.created.second.json",
        "card.created.json", "card.created.duplicate.json",
        "review.logged.json", "review.logged.manual.json",
    )
}

/** The scenario files of `contracts/sync/examples/scenarios`. */
object Scenarios {
    val files: Map<String, JsonObject> = File(examplesDir, "scenarios").listFiles { f -> f.extension == "json" }!!
        .associate { it.name to decodeObject(it.readText()) }

    val names: List<String> = files.keys.sorted()

    fun log(name: String): List<Op> = (files.getValue(name).members.getValue("log") as JsonArray).items.map {
        assertIs<OpDecodeResult.Known>(DefaultOpCodec.decodeTree(it, OpSource.PULLED), name).op
    }
}

/** Every row of every table keyed by table and canonical key. */
fun dump(t: LearningTables): Map<String, Map<String, Row>> =
    LearningTable.entries.associate { table ->
        table.sqlName to t.all(table).associateBy { DefaultCanonicalJson.encode(JsonArray(table.keyOf(it))) }
    }

/** Like [dump] but rows in table order (to compare the order [LearningTables.all] returns). */
fun orderedKeys(t: LearningTables): Map<String, List<String>> =
    LearningTable.entries.associate { table -> table.sqlName to t.all(table).map { DefaultCanonicalJson.encode(JsonArray(table.keyOf(it))) } }

private val CACHE_COLUMNS = listOf(
    "state", "step", "stability", "difficulty", "due_at", "interval_days", "last_review_at", "last_review_id",
    "reps", "lapses", "weights_version",
)

/** SYNC.md 11: the projection on which two converged replicas are equal (T303b's). */
fun projection(t: LearningTables): Map<String, Map<String, Row>> {
    val compared = listOf(
        LearningTable.NOTE_TYPE, LearningTable.NOTE, LearningTable.CARD, LearningTable.REVIEW_LOG,
        LearningTable.WORD_STATUS, LearningTable.DECK, LearningTable.DECK_ITEM, LearningTable.CONFUSABLE,
        LearningTable.GRAMMAR_POINT, LearningTable.SENTENCE_GRAMMAR_SPAN, LearningTable.SETTINGS,
        LearningTable.FSRS_PARAMS, LearningTable.MEDIA,
    )
    return compared.associate { table ->
        val rows = t.all(table).filter { row ->
            when (table) {
                LearningTable.DECK_ITEM -> (row["hlc"] ?: JsonNull) != JsonNull
                LearningTable.SENTENCE_GRAMMAR_SPAN -> row["origin"] == JsonString("manual")
                else -> true
            }
        }.map { row ->
            val out = LinkedHashMap(row)
            if ((row["merged_into_id"] ?: JsonNull) != JsonNull) out.keys.retainAll(setOf("id", "merged_into_id", "deleted_at"))
            if (table == LearningTable.CARD && !isMaintained(row)) CACHE_COLUMNS.forEach { out.remove(it) }
            if (table == LearningTable.WORD_STATUS) {
                out.remove("updated_at")
                out.remove("auto_status")
            }
            out.remove("installed_content_version")
            out as Row
        }
        table.sqlName to rows.associateBy { DefaultCanonicalJson.encode(JsonArray(table.keyOf(it))) }
    }
}

private fun isMaintained(card: Row): Boolean =
    (card["deleted_at"] ?: JsonNull) == JsonNull && (card["merged_into_id"] ?: JsonNull) == JsonNull

private fun matches(row: Row, partial: Map<String, JsonValue>): Boolean = partial.all { (k, v) -> (row[k] ?: JsonNull) == v }

/** Checks `expected.rows`, `expected.absent` and `expected.replay_sets` of scenario [name] against [t]. */
fun assertScenarioExpected(name: String, t: LearningTables, cache: DeviceCardCache, context: String) {
    val expected = Scenarios.files.getValue(name).members.getValue("expected") as JsonObject
    fun table(n: String) = LearningTable.entries.single { it.sqlName == n }
    (expected.members["rows"] as? JsonObject)?.members?.forEach { (tableName, list) ->
        val table = table(tableName)
        for (item in (list as JsonArray).items) {
            val partial = (item as JsonObject).members
            val key = table.keyColumns.associateWith { partial[it] ?: fail("$context: expected $tableName row without key column $it") }
            val row = t.all(table).singleOrNull { matches(it, key) }
            assertNotNull(row, "$context: $tableName row $key missing")
            for ((col, v) in partial) assertEquals(v, row[col] ?: JsonNull, "$context: $tableName $key column $col")
        }
    }
    (expected.members["absent"] as? JsonObject)?.members?.forEach { (tableName, list) ->
        val table = table(tableName)
        for (item in (list as JsonArray).items) {
            val partial = (item as JsonObject).members
            assertNull(t.all(table).firstOrNull { matches(it, partial) }, "$context: $tableName row $partial must be absent")
        }
    }
    (expected.members["replay_sets"] as? JsonObject)?.members?.forEach { (cardId, ids) ->
        val want = (ids as JsonArray).items.map { (it as JsonString).value }
        assertEquals(want, cache.replaySet(t, cardId).map { it.id }, "$context: replay set of $cardId")
    }
}

/** Rows that differ between two dumps, readable. */
fun diffRows(a: Map<String, Map<String, Row>>, b: Map<String, Map<String, Row>>): List<String> =
    (a.keys + b.keys).flatMap { table ->
        val ra = a[table].orEmpty()
        val rb = b[table].orEmpty()
        (ra.keys + rb.keys).filter { ra[it] != rb[it] }.map { k -> "$table $k: ${ra[k] ?: "absent"} vs ${rb[k] ?: "absent"}" }
    }

fun assertSameRows(want: Map<String, Map<String, Row>>, got: Map<String, Map<String, Row>>, context: String) {
    val d = diffRows(want, got)
    assertEquals(emptyList(), d, "$context: ${d.size} rows differ")
}

/** The server's replica: its log applied in seq order with the device apply function. */
fun serverReplica(server: TestServer): InMemoryLearningTables {
    val applier = DeviceOpApplier<InMemoryLearningTables>()
    return InMemoryLearningTables.seeded().also { t -> for (op in server.loggedOps()) applier.applyTo(t, op) }
}

/** A higher hlc than [hlc]: wall + [ms], same device. */
fun later(hlc: String, ms: Long): String {
    val wall = DefaultHlcRules.wall(hlc) + ms
    return wall.toString(16).padStart(12, '0') + hlc.substring(12)
}

// ------------------------------------------------------------------------------------------- DB

/**
 * A database file reopened like a process restart: each [open] is a new JDBC driver (WAL, synchronous=FULL).
 * In [dir] (created if needed) or the default temp directory.
 */
class DbFile(dir: File? = null) {
    val file: File = File.createTempFile("kotobaya-sync", ".db", dir?.also { it.mkdirs() }).also {
        it.delete()
        it.deleteOnExit()
        File(it.path + "-wal").deleteOnExit()
        File(it.path + "-shm").deleteOnExit()
    }
    private var created = false

    fun open(): SqlDriver {
        val props = Properties().apply {
            put("journal_mode", "WAL")
            put("synchronous", "FULL")
        }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", props)
        if (!created) {
            AppDatabase.Schema.create(driver)
            created = true
        }
        return driver
    }
}

/** An in-memory app database (schema and seed rows of the contract). */
fun memoryDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AppDatabase.Schema.create(it) }

// ------------------------------------------------------------------------------------------- devices

/** The T303a/T303b stack over any store (the same wiring as [SyncClient]). */
class Stack<L : LearningTables>(
    val store: DeviceStore<L>,
    transport: SyncTransport,
    clock: Clock,
    seed: Int,
    engine: ReplayEngine = DefaultReplayEngine,
    applierWrap: (OpApplier<L>) -> OpApplier<L> = { it },
) {
    val diagnostics = DeviceDiagnostics()
    val cache = DeviceCardCache(engine = engine, diagnostics = diagnostics)
    val startup = StartupReplay(cache)
    val deviceApplier = DeviceOpApplier<L>(cache, startup)
    val applier: OpApplier<L> = applierWrap(deviceApplier)
    val engineSync = SyncEngine(store, transport, applier, clock)
    val rebuild = Rebuild(store, engineSync, applier)
    val cycle = DeviceSyncCycle(store, engineSync, rebuild, startup)
    val minter = OpMinter(store, applier, clock, UuidV7Source(Random(seed)))
}

/** Puts a log op of this device in the outbox with its local effect (like T303b's `ApplyDevice.inject`). */
fun <L : LearningTables> inject(store: DeviceStore<L>, applier: OpApplier<L>, op: Op) {
    val o = op.copy(seq = null, causedByOpId = null)
    store.transaction { tx ->
        applier.apply(tx, o, ApplyOrigin.LOCAL)
        tx.outbox.insert(
            PendingOpRow(
                opId = o.opId, opType = o.opType.wire, opVersion = o.opVersion, entityType = o.entityType,
                entityId = o.entityId, hlc = o.hlc, payload = DefaultCanonicalJson.encode(o.payload),
                createdAt = DefaultHlcRules.wall(o.hlc),
            ),
        )
        val bk = tx.bookkeeping
        val last = bk.hlcLast
        if (last == null || o.hlc > last) bk.hlcLast = o.hlc
    }
}

/**
 * One device on the SQLite store, restartable: [restart] closes the driver and builds everything again
 * from the file (a process kill). The transport and clock survive (they are the world, not the app).
 */
class SqlDevice(
    val deviceId: String,
    server: TestServer,
    clockMs: Long,
    private val seed: Int = 1,
    private val engine: ReplayEngine = DefaultReplayEngine,
    dbDir: File? = null,
) : DeviceApi {
    val db = DbFile(dbDir)
    val clock = TestClock(clockMs)
    val transport = TestTransport(server)
    lateinit var driver: SqlDriver
        private set
    lateinit var store: SqlDelightDeviceStore
        private set
    lateinit var stack: Stack<SqlLearningTables>
        private set
    private var restarts = 0

    init {
        open()
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, deviceId) }
    }

    private fun open() {
        driver = db.open()
        store = SqlDelightDeviceStore(driver)
        stack = Stack(store, transport, clock, seed * 1000 + restarts, engine)
    }

    fun restart() {
        driver.close()
        restarts++
        open()
    }

    fun cycle(): CycleResult = runSync { stack.cycle.run() }

    override fun sync(): CycleResult = cycle()

    override fun pushAll(): CycleResult = runSync { stack.engineSync.pushAll() }

    override fun inject(op: Op) = inject(store, stack.applier, op)

    override fun tables(): SqlLearningTables = store.read { it.learning }

    fun rows(): List<PendingOpRow> = store.read { tx ->
        kotobaya.sync.client.store.PendingState.entries.flatMap { tx.outbox.inState(it) }.sortedBy { it.hlc }
    }

    override fun state(key: String): String? = store.read { it.syncState.get(key) }
}

/** The same device on T303a's in-memory store (the reference the SQLite store must match). */
class MemDevice(val deviceId: String, server: TestServer, clockMs: Long, seed: Int = 1, engine: ReplayEngine = DefaultReplayEngine) {
    val clock = TestClock(clockMs)
    val transport = TestTransport(server)
    val store = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
    val stack = Stack(store, transport, clock, seed * 1000, engine)

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, deviceId) }
    }

    fun cycle(): CycleResult = runSync { stack.cycle.run() }

    fun inject(op: Op) = inject(store, stack.applier, op)

    fun tables(): InMemoryLearningTables = store.learning()
}

// ------------------------------------------------------------------------------------------- server

/** In-memory stand-in for the server's sync endpoints (T303a's TestServer, same rules). */
class TestServer(
    var nowMs: Long,
    var maxOpBytes: Long = SyncLimits.DEFAULT_MAX_OP_BYTES,
    var maxPushBytes: Long = SyncLimits.DEFAULT_MAX_PUSH_BYTES,
) {
    var logEpoch: String = "0193a1b2-00ee-7000-8000-000000000001"
    val log: MutableList<JsonObject> = ArrayList()
    private val seqByOpId = HashMap<String, Long>()
    var nextSeq: Long = 1
    var durableSeq: Long? = null
    var pageSize: Int = 500
    var hlc: HlcState = HlcState.INITIAL
    var emitAfter: (Op) -> List<Op> = { emptyList() }

    /** How many times each op_id was pushed and accepted or found duplicate. */
    val receipts: MutableMap<String, Int> = HashMap()

    val highWaterSeq: Long get() = nextSeq - 1
    private val reportedDurable: Long get() = minOf(durableSeq ?: highWaterSeq, highWaterSeq)

    fun limits(): SyncLimits = SyncLimits(maxOpBytes, maxPushBytes)

    fun push(body: String): TransportResponse {
        if (DefaultCanonicalJson.utf8Size(body) > maxPushBytes) return TransportResponse.HttpError(413, "payload_too_large")
        val parsed = PushBodyParser.parse(body)
        if (parsed is PushBody.BadRequest) return TransportResponse.HttpError(400, parsed.message)
        val results = (parsed as PushBody.Ops).elements.map { result(it) }
        return TransportResponse.Ok(DefaultCanonicalJson.encode(JsonObject(mapOf("results" to JsonArray(results)) + logInfo())))
    }

    private fun result(el: PushElement): JsonValue {
        receipts[el.opId] = (receipts[el.opId] ?: 0) + 1
        seqByOpId[el.opId]?.let { return ok(el.opId, "duplicate", it) }
        val op = when (el) {
            is PushElement.DuplicateKey, is PushElement.TooDeep -> return rejected(el.opId, "schema_invalid", "duplicate key or too deep")
            is PushElement.Parsed -> when (val d = DefaultOpCodec.decodeTree(el.tree, OpSource.PUSHED)) {
                is OpDecodeResult.Unsupported -> return rejected(el.opId, "unsupported_op", d.opType)
                is OpDecodeResult.Invalid -> return rejected(el.opId, "schema_invalid", d.message)
                is OpDecodeResult.Known -> d.op
            }
        }
        if (op.opType.emitter == Emitter.SERVER || op.deviceId == Reserved.SERVER_DEVICE_ID) return rejected(op.opId, "server_only", op.opType.wire)
        val seq = append(op, null)
        for (emitted in emitAfter(op)) append(emitted, op.opId)
        return ok(op.opId, "accepted", seq)
    }

    fun append(op: Op, cause: String?): Long {
        val seq = nextSeq++
        hlc = DefaultHlcRules.observe(hlc, op.hlc)
        log.add(DefaultOpCodec.encodeTree(op.copy(seq = seq, causedByOpId = cause)))
        seqByOpId[op.opId] = seq
        return seq
    }

    fun pull(since: Long): TransportResponse {
        val out = ArrayList<JsonValue>()
        for (e in log) {
            if (seqOf(e) <= since) continue
            if (out.size >= pageSize) break
            out.add(e)
        }
        val next = if (out.isEmpty()) since else seqOf(out.last() as JsonObject)
        val hasMore = log.any { seqOf(it) > next }
        val page = mapOf("ops" to JsonArray(out), "next_since" to num(next), "has_more" to JsonBool(hasMore)) + logInfo()
        return TransportResponse.Ok(DefaultCanonicalJson.encode(JsonObject(page)))
    }

    /** SYNC.md 6.2 restore: the log keeps seq <= [toSeq], a new epoch, durable = that seq. */
    fun restore(toSeq: Long, newEpoch: String) {
        log.removeAll { seqOf(it) > toSeq }
        seqByOpId.entries.removeAll { it.value > toSeq }
        nextSeq = toSeq + 1
        logEpoch = newEpoch
        durableSeq = toSeq
    }

    fun loggedOps(): List<Op> = log.map { (DefaultOpCodec.decodeTree(it, OpSource.PULLED) as OpDecodeResult.Known).op }

    fun loggedOpIds(): List<String> = log.map { (it.members.getValue("op_id") as JsonString).value }

    private fun seqOf(e: JsonObject): Long = (e.members.getValue("seq") as JsonNumber).value.toLong()

    private fun logInfo(): Map<String, JsonValue> = mapOf(
        "high_water_seq" to num(highWaterSeq),
        "server_time" to num(nowMs),
        "log_epoch" to JsonString(logEpoch),
        "durable_seq" to num(reportedDurable),
    )

    private fun ok(opId: String, status: String, seq: Long): JsonValue =
        JsonObject(mapOf("op_id" to JsonString(opId), "status" to JsonString(status), "seq" to num(seq)))

    private fun rejected(opId: String, code: String, message: String): JsonValue = JsonObject(
        mapOf(
            "op_id" to JsonString(opId),
            "status" to JsonString("rejected"),
            "error" to JsonObject(mapOf("code" to JsonString(code), "message" to JsonString(message))),
        ),
    )

    private fun num(v: Long) = JsonNumber(v.toDouble())
}

/** Thrown by [TestTransport] to stand for the process dying at that point (nothing after it runs). */
class Killed(where: String) : RuntimeException("process killed $where")

/** [SyncTransport] onto a [TestServer] with fault injection. */
class TestTransport(val server: TestServer) : SyncTransport {
    var offline: Boolean = false

    /** The next push never reaches the server (connection reset). */
    var failNextPushBeforeServer: Boolean = false

    /** The next push is applied by the server but its response is lost. */
    var loseNextPushResponse: Boolean = false

    /** The process dies before the next push is sent. */
    var killBeforeNextPush: Boolean = false

    /** The process dies after the server applied the next push, before its response is read. */
    var killAfterNextPush: Boolean = false

    /** The process dies after the next pull page arrived, before it is applied. */
    var killAfterNextPull: Boolean = false

    /** Pulls from `since` = 0 fail (offline) — a rebuild cut off after its wipe. */
    var failPullFromZero: Boolean = false

    override suspend fun push(body: String): TransportResponse {
        if (offline) return TransportResponse.NetworkError("offline")
        if (killBeforeNextPush) {
            killBeforeNextPush = false
            throw Killed("before push")
        }
        if (failNextPushBeforeServer) {
            failNextPushBeforeServer = false
            return TransportResponse.NetworkError("connection reset before the request was sent")
        }
        val r = server.push(body)
        if (killAfterNextPush) {
            killAfterNextPush = false
            throw Killed("after the server applied the push")
        }
        if (loseNextPushResponse) {
            loseNextPushResponse = false
            return TransportResponse.NetworkError("response lost")
        }
        return r
    }

    override suspend fun pull(since: Long): TransportResponse {
        if (offline) return TransportResponse.NetworkError("offline")
        if (failPullFromZero && since == 0L) return TransportResponse.NetworkError("offline during rebuild")
        val r = server.pull(since)
        if (killAfterNextPull) {
            killAfterNextPull = false
            throw Killed("after a pull page arrived")
        }
        return r
    }

    override suspend fun limits(): SyncLimits? = if (offline) null else server.limits()
}
