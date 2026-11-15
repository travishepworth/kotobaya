package kotobaya.android.review

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotobaya.android.db.createAppDatabase
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.Stack
import kotobaya.android.sync.TestClock
import kotobaya.android.sync.TestServer
import kotobaya.android.sync.TestTransport
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.db.app.AppDatabase
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotlin.random.Random
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Holds (or fails) the write of the next `review.logged` inside its transaction: the op and the card cache are
 * written but not committed until [release]. AND-REVIEW-SNAPPY tests of the async commit.
 */
class CommitGate {
    @Volatile private var hold: CountDownLatch? = null
    @Volatile private var held: CountDownLatch? = null
    @Volatile private var entered: CountDownLatch? = null
    @Volatile var failHeld: Boolean = false

    /** The next review.logged write stops inside its transaction until [release]. */
    fun holdNext() {
        entered = CountDownLatch(1)
        hold = CountDownLatch(1).also { held = it }
    }

    /** Waits until the held write is inside its transaction. */
    fun awaitHeld() {
        assertTrue(checkNotNull(entered).await(10, TimeUnit.SECONDS), "a review.logged write reached the gate")
    }

    fun release() {
        held?.countDown()
    }

    fun <L> wrap(inner: OpApplier<L>): OpApplier<L> = object : OpApplier<L> {
        override fun apply(tx: StoreTransaction<L>, op: kotobaya.contracts.sync.Op, origin: kotobaya.sync.client.store.ApplyOrigin) {
            val h = hold
            if (op.opType == OpType.REVIEW_LOGGED && h != null) {
                inner.apply(tx, op, origin)
                hold = null
                entered?.countDown()
                if (!h.await(10, TimeUnit.SECONDS)) {
                    for ((t, st) in Thread.getAllStackTraces()) {
                        System.err.println("DUMP thread ${t.name} ${t.state}")
                        for (f in st.take(25)) System.err.println("DUMP   at $f")
                    }
                    throw AssertionError("gate released")
                }
                if (failHeld) {
                    failHeld = false
                    throw IllegalStateException("disk I/O error (simulated)")
                }
                return
            }
            inner.apply(tx, op, origin)
        }

        override fun <R> batch(tx: StoreTransaction<L>, block: () -> R): R = inner.batch(tx, block)
    }
}

/**
 * A device on a real SQLite file with the production pragmas (WAL, synchronous=FULL), the production queue loader
 * and concurrent reads ([SqlDelightDeviceStore.read]). [android] is the production driver ([DurableDriver], under
 * Robolectric: reads really run beside an open write); [jdbc] is the plain JVM driver (benches). [snapshot] copies
 * the files as a process kill leaves them.
 */
class AsyncReviewDevice private constructor(t0: Long, val file: File, val driver: SqlDriver, create: Boolean) {
    val clock = TestClock(t0)
    val gate = CommitGate()
    val db: AppDatabase = AppDatabase(driver)
    val store = SqlDelightDeviceStore(driver, db)
    val stack = Stack(store, TestTransport(TestServer(t0)), clock, 1, applierWrap = { gate.wrap(it) })
    private val ids = UuidV7Source(Random(5))
    private var reviewIds = 0

    init {
        if (create) store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, DEVICE) }
    }

    val reads = object : ReviewReads<SqlLearningTables> {
        override fun <R> read(block: (StoreTransaction<SqlLearningTables>) -> R): R = store.read(block)
    }

    fun session(timings: (ReviewTiming) -> Unit = {}): ReviewSession<SqlLearningTables> = ReviewSession(
        store, stack.minter, LocalQueueLoader(db, { clock.now }).asQueueSource(), { clock.now }, { 540 },
        { "0193a1b2-0003-7000-8000-%012d".format(++reviewIds) }, { ReviewPrefs() },
        timings = timings, reads = reads,
    )

    private fun obj(vararg p: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*p))

    /** [n] vocab notes with one meaning card each; returns the card ids. */
    fun seed(n: Int): List<String> = List(n) { i ->
        val noteId = ids.newOpId(clock.now)
        val cardId = ids.newOpId(clock.now)
        assertIs<MintResult.Minted>(
            stack.minter.mint(
                OpType.NOTE_CREATED,
                obj(
                    "id" to JsonString(noteId),
                    "note_type_id" to JsonString(Reserved.NOTE_TYPE_VOCAB),
                    "fields" to obj("expression" to JsonString("語$i"), "reading" to JsonString("ご"), "meaning" to JsonString("word $i")),
                ),
            ),
        )
        assertIs<MintResult.Minted>(
            stack.minter.mint(OpType.CARD_CREATED, obj("id" to JsonString(cardId), "note_id" to JsonString(noteId), "kind" to JsonString("meaning"))),
        )
        clock.now += 10
        cardId
    }

    /** Committed outbox rows of [type], in HLC (= mint) order; read on a reader connection (sees only commits). */
    fun ops(type: String): List<PendingOpRow> = store.read { tx ->
        PendingState.entries.flatMap { tx.outbox.inState(it) }.filter { it.opType == type }.sortedBy { it.hlc }
    }

    fun payload(row: PendingOpRow): Map<String, JsonValue> = (DefaultCanonicalJson.decode(row.payload).value as JsonObject).members

    fun str(row: PendingOpRow, key: String): String? = (payload(row)[key] as? JsonString)?.value

    /** The database files as a process killed now leaves them (uncommitted frames are not part of any commit). */
    fun snapshot(): File {
        val copy = newFile()
        file.copyTo(copy)
        File(file.path + "-wal").takeIf { it.isFile }?.copyTo(File(copy.path + "-wal"))
        return copy
    }

    fun close() = driver.close()

    companion object {
        const val DEVICE = "01931f3e-0000-7000-8000-0000000000a1"

        /** On the JVM SQLite driver, a new file. */
        fun jdbc(t0: Long, wrap: (SqlDriver) -> SqlDriver = { it }): AsyncReviewDevice {
            val file = newFile()
            Class.forName("org.sqlite.JDBC")
            val driver = wrap(open(file))
            createAppDatabase(driver)
            return AsyncReviewDevice(t0, file, driver, create = true)
        }

        /** On the production driver (Robolectric); [from] replaces the database with a [snapshot] first. */
        fun android(t0: Long, context: android.content.Context, from: File? = null): AsyncReviewDevice {
            val file = context.getDatabasePath(kotobaya.android.db.APP_DB_FILE)
            if (from != null) {
                context.deleteDatabase(kotobaya.android.db.APP_DB_FILE)
                file.parentFile?.mkdirs()
                from.copyTo(file, overwrite = true)
                File(from.path + "-wal").takeIf { it.isFile }?.copyTo(File(file.path + "-wal"), overwrite = true)
            }
            return AsyncReviewDevice(t0, file, kotobaya.android.sync.DurableDriver.open(context), create = from == null)
        }

        fun newFile(): File = File.createTempFile("kotobaya-review", ".db").also {
            it.delete()
            it.deleteOnExit()
            File(it.path + "-wal").deleteOnExit()
            File(it.path + "-shm").deleteOnExit()
        }

        private fun open(file: File): SqlDriver = JdbcSqliteDriver(
            "jdbc:sqlite:${file.path}",
            Properties().apply {
                put("journal_mode", "WAL")
                put("synchronous", "FULL")
            },
        )
    }
}

/** Waits until [s] shows a card other than token [notToken]; returns it. */
fun awaitShowing(s: ReviewSession<*>, notToken: Long? = null): ShownCard {
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
        val st = s.state.value
        if (st is ReviewState.Showing && st.card.token != notToken) return st.card
        Thread.sleep(1)
    }
    error("no new card shown; state ${s.state.value}")
}

private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

/** Runs [block] on another thread (a tap's IO coroutine); get() rethrows its failure. */
fun <T> background(block: () -> T): java.util.concurrent.Future<T> = pool.submit(java.util.concurrent.Callable { block() })
