package kotobaya.android.ankiapi.store

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlin.random.Random
import kotobaya.android.ankiapi.takoboto.NoteTypeEnsurer
import kotobaya.android.notes.CandidateSource
import kotobaya.android.notes.DEVICE_A
import kotobaya.android.notes.DICT
import kotobaya.android.notes.DuplicateCheck
import kotobaya.android.notes.EntryMatcher
import kotobaya.android.notes.FakeSource
import kotobaya.android.notes.LearningReader
import kotobaya.android.notes.NoteCreator
import kotobaya.android.notes.T0
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.Stack
import kotobaya.android.sync.TestClock
import kotobaya.android.sync.TestServer
import kotobaya.android.sync.TestTransport
import kotobaya.android.sync.runSync
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.OpType
import kotobaya.db.app.AppDatabase
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source

/** What the store helpers need of a device: its SQLite store, minter and clock. */
interface Dev {
    val store: SqlDelightDeviceStore
    val minter: OpMinter<SqlLearningTables>
    val clock: TestClock

    fun tables(): SqlLearningTables = store.read { it.learning }
}

/** [SqlDevice] (JDBC; plain JVM tests only — JDBC inside a Robolectric sandbox breaks later plain tests' DriverManager). */
fun SqlDevice.dev(): Dev = object : Dev {
    override val store get() = this@dev.store
    override val minter get() = this@dev.stack.minter
    override val clock get() = this@dev.clock
}

/**
 * A device on Robolectric's Android SQLite (for tests that also need a ContentResolver or the app-private
 * map). [name] null = in memory; a name survives [restart].
 */
class RoboDevice(
    private val context: Context,
    val server: TestServer = TestServer(T0),
    private val name: String? = null,
    deviceId: String = DEVICE_A,
) : Dev {
    override val clock = TestClock(T0)
    val transport = TestTransport(server)
    private var restarts = 0
    private lateinit var driver: AndroidSqliteDriver
    override lateinit var store: SqlDelightDeviceStore
        private set
    lateinit var stack: Stack<SqlLearningTables>
        private set
    override val minter: OpMinter<SqlLearningTables> get() = stack.minter

    init {
        open()
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, deviceId) }
    }

    private fun open() {
        driver = AndroidSqliteDriver(AppDatabase.Schema, context, name)
        val db = AppDatabase(driver)
        db.syncOpsQueries.getSyncState(SyncStateKeys.DEVICE_ID).executeAsOneOrNull() // open before the first transaction
        store = SqlDelightDeviceStore(driver, db)
        stack = Stack(store, transport, clock, 1000 + restarts)
    }

    fun restart() {
        driver.close()
        restarts++
        open()
    }

    fun sync(): CycleResult = runSync { stack.cycle.run() }
}

/** The dictionary of the notes tests ([DICT]) as the store's [EntryLookup]. */
val FAKE_ENTRIES: EntryLookup = EntryLookup { seq ->
    DICT.firstOrNull { it.seq == seq }?.let { Headword(it.headword, it.headwordReading, it.glosses.joinToString("; ")) }
}

fun ensurer(d: Dev, seed: Int = 3): NoteTypeEnsurer<SqlLearningTables> =
    NoteTypeEnsurer(d.store, d.minter, UuidV7Source(Random(seed)), d.clock)

fun reader(d: Dev): LearningReader = object : LearningReader {
    override fun <R> read(block: (LearningTables) -> R): R = d.store.read { block(it.learning) }
}

fun noteCreator(d: Dev, source: CandidateSource = FakeSource(DICT), seed: Int = 7): NoteCreator<SqlLearningTables> =
    NoteCreator(d.store, d.minter, EntryMatcher(source), UuidV7Source(Random(seed)), d.clock)

fun duplicates(d: Dev, source: CandidateSource = FakeSource(DICT)): DuplicateCheck = DuplicateCheck(reader(d), EntryMatcher(source))

/** The production store wired over test device [d] (rebuild it after a restart). */
fun takobotoStore(
    d: Dev,
    map: AnkiIdMap,
    source: CandidateSource = FakeSource(DICT),
    seed: Int = 7,
): TakobotoAnkiStore<SqlLearningTables> = TakobotoAnkiStore(
    store = d.store,
    reader = reader(d),
    minter = d.minter,
    noteTypes = ensurer(d, seed),
    creator = noteCreator(d, source, seed),
    duplicates = duplicates(d, source),
    map = map,
    entries = FAKE_ENTRIES,
    ids = UuidV7Source(Random(seed + 1)),
    clock = d.clock,
)

fun memoryMap(context: Context): SqliteAnkiIdMap = SqliteAnkiIdMap(context, null)

/** A pending (not yet pushed) op of a device: its wire type, entity id and payload. */
data class Pending(val type: String, val entityId: String, val payload: JsonObject)

/** Pending ops of [d], in hlc order. */
fun pendingOps(d: Dev): List<Pending> = d.store.read { tx -> tx.outbox.inState(PendingState.PENDING) }.sortedBy { it.hlc }.map { row ->
    Pending(row.opType, row.entityId, DefaultCanonicalJson.decode(row.payload).value as JsonObject)
}

fun countOf(ops: List<Pending>, type: OpType): Int = ops.count { it.type == type.wire }
