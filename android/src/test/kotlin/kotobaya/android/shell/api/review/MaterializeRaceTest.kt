package kotobaya.android.shell.api.review

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.db.APP_DB_FILE
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.NoteRequest
import kotobaya.android.review.InstalledRenderData
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.review.QueueTestSupport.liveNotesOf
import kotobaya.android.shell.api.review.QueueTestSupport.queue
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.android.shell.parity.ParityTemp
import kotobaya.android.sync.DurableDriver
import kotobaya.sync.client.store.OpIdSource
import kotobaya.sync.client.store.UuidV7Source
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-MATERIALIZE-RACE: two writers that both want a note for one deck entry (two queue fetches; a Takoboto add and a
 * queue fetch) create ONE live note, on the production driver ([DurableDriver]: AndroidSqliteDriver over a WAL file,
 * Robolectric's native SQLite with the framework's connection pool).
 *
 * [WindowGate] makes the race deterministic: it holds each writer at its first id request — after the in-transaction
 * duplicate check, before the mint — until the other writer is held there too (both passed the check: a duplicate
 * follows) or is parked waiting for the write connection (the driver serializes them), so the dangerous window is
 * exercised on purpose instead of hoping the scheduler hits it.
 *
 * [theSameRaceDuplicatesWhenCheckAndMintAreNotOneTransaction] is the control: the same driver with no-op transactions
 * duplicates every time, so the gate does catch a check-then-mint race when one exists. (The harness's default driver,
 * ONE in-memory JDBC connection whose transaction SQLDelight shares across threads, is such a store: a second thread's
 * "transaction" joins the first; that is where `twoConcurrentFetchesCreateOneNotePerDrawnEntry`'s one-off failure came
 * from.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MaterializeRaceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val iterations = 50
    private val entry = 1001010L
    private val drawn = listOf(1001010L, 1001150L)
    private val threads = CopyOnWriteArrayList<Thread>()

    @AfterTest
    fun cleanUp() {
        app.deleteDatabase(APP_DB_FILE)
    }

    /**
     * Holds each thread at its first [newOpId] (the check-then-mint window) until another writer is held there too
     * ([met]: both passed the duplicate check), another participant is parked in the Android connection pool waiting
     * for the primary (write) connection ([contested]; only with [releaseOnWait]), or no other participant is still
     * running; [MAX_WAIT_MS] at most.
     */
    private class WindowGate(private val participants: () -> List<Thread>, private val releaseOnWait: Boolean) : OpIdSource {
        private val delegate = UuidV7Source(Random(7))
        private val passed = ThreadLocal<Boolean>()
        private val held = AtomicInteger()
        private val bothHeld = AtomicBoolean()
        val met = AtomicInteger()
        val contested = AtomicInteger()

        /** Whether a writer is held in the window right now. */
        fun holding(): Boolean = held.get() > 0

        override fun newOpId(nowMs: Long): String {
            if (passed.get() != true) {
                passed.set(true)
                held.incrementAndGet()
                try {
                    hold()
                } finally {
                    held.decrementAndGet()
                }
            }
            return synchronized(delegate) { delegate.newOpId(nowMs) }
        }

        private fun hold() {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_WAIT_MS)
            val me = Thread.currentThread()
            while (System.nanoTime() < deadline) {
                if (held.get() >= 2) bothHeld.set(true)
                if (bothHeld.get()) {
                    met.incrementAndGet()
                    return
                }
                val others = participants().filter { it !== me }
                if (others.isEmpty()) return // the other writer is done: no race this time
                if (releaseOnWait && others.any(::waitingForWriteConnection)) {
                    contested.incrementAndGet()
                    return
                }
                Thread.sleep(1)
            }
        }

        private fun waitingForWriteConnection(t: Thread): Boolean =
            t.stackTrace.any { it.className == "android.database.sqlite.SQLiteConnectionPool" && it.methodName == "waitForConnection" }

        companion object {
            const val MAX_WAIT_MS = 3_000L
        }
    }

    /**
     * The control's driver: the production driver with transactions that do nothing (every statement autocommits), i.e.
     * a store whose duplicate check and mint are NOT one isolated write transaction.
     */
    private class NoTransactionDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.Value(
            object : Transacter.Transaction() {
                override val enclosingTransaction: Transacter.Transaction? = null

                override fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.Unit
            },
        )

        override fun currentTransaction(): Transacter.Transaction? = null
    }

    private class Run(val device: ParityDevice, val gate: WindowGate, private val dictDir: File, private val driver: SqlDriver) {
        fun close() {
            driver.close()
            dictDir.deleteRecursively()
        }
    }

    /** A fresh device on the production driver (a new database file); [transactions] false = [NoTransactionDriver]. */
    private fun device(transactions: Boolean = true): Run {
        // without transactions a held writer holds no connection, so a pool wait is not ours: wait for the other writer
        val gate = WindowGate({ threads.toList() }, releaseOnWait = transactions)
        val s = ParityScenario.load("queue-materialize-count")
        val dictDir = ParityTemp.next("race-dict")
        app.deleteDatabase(APP_DB_FILE)
        val production = DurableDriver.open(app)
        val driver = if (transactions) production else NoTransactionDriver(production)
        val d = ParityDevice(s.deviceId, s.clockMs, LocalRoutes.all, dictDir = dictDir, driver = driver, noteIds = gate)
        d.pull(s.seedLog, "seed")
        return Run(d, gate, dictDir, driver)
    }

    /** Runs [a] and [b] on two threads released together; returns their results. */
    private fun <A, B> race(a: () -> A, b: () -> B): Pair<A, B> {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val ready = CountDownLatch(2)
            fun <T> submit(f: () -> T) = pool.submit<T> {
                threads += Thread.currentThread()
                try {
                    ready.countDown()
                    start.await()
                    f()
                } finally {
                    threads -= Thread.currentThread()
                }
            }
            val fa = submit(a)
            val fb = submit(b)
            ready.await()
            start.countDown()
            return fa.get(60, TimeUnit.SECONDS) to fb.get(60, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            threads.clear()
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not reached" }
            Thread.sleep(1)
        }
    }

    /** The native add path's core (`TakobotoAnkiStore.add` → `NoteCreator.create`) for [entry]'s word. */
    private fun takobotoAdd(d: ParityDevice): () -> CreateResult {
        val dict = InstalledRenderData(d.installedDictionary).lookup(entry) ?: error("fixture lacks $entry")
        return { d.noteCreator.create(NoteRequest(word = dict.expression, reading = dict.reading, origin = "takoboto")) }
    }

    @Test
    fun twoConcurrentFetchesOnTheProductionDriverCreateOneNotePerDrawnEntry() {
        var contested = 0
        repeat(iterations) { i ->
            val run = device()
            try {
                val d = run.device
                val (s1, s2) = race({ d.queue().status }, { d.queue().status })
                assertEquals(200 to 200, s1 to s2, "iteration $i")
                drawn.forEach { assertEquals(1, d.liveNotesOf(it).size, "iteration $i: live notes of $it") }
                assertEquals(0, d.liveNotesOf(1001580L).size, "iteration $i: an undrawn entry is not materialized")
                assertEquals(0, run.gate.met.get(), "iteration $i: two writers were inside check-then-mint at once")
                contested += run.gate.contested.get()
            } finally {
                run.close()
            }
        }
        // the window was really contested: a writer held it while the other one waited for the write connection
        assertTrue(contested > 0, "no iteration had the second fetch waiting on the write connection")
    }

    @Test
    fun aTakobotoAddRacingAQueueMaterializeOnTheProductionDriverCreatesOneNote() {
        var contested = 0
        val outcomes = HashMap<String, Int>()
        repeat(iterations) { i ->
            val run = device()
            try {
                val d = run.device
                val add = takobotoAdd(d)
                // even iterations: both start together (the add reaches the window first); odd: the add starts once the
                // queue's materialize holds the window, so it must wait for the write connection and then see the note
                val (status, created) = race({ d.queue().status }) {
                    if (i % 2 == 1) awaitTrue { run.gate.holding() }
                    add()
                }
                assertEquals(200, status, "iteration $i")
                assertEquals(1, d.liveNotesOf(entry).size, "iteration $i: live notes of $entry ($created)")
                assertEquals(0, run.gate.met.get(), "iteration $i: two writers were inside check-then-mint at once")
                outcomes.merge(created::class.simpleName ?: "?", 1, Int::plus)
                contested += run.gate.contested.get()
            } finally {
                run.close()
            }
        }
        assertTrue(contested > 0, "no iteration had the second writer waiting on the write connection")
        assertEquals(setOf("Created", "Duplicate"), outcomes.keys, "both orders happened: $outcomes")
    }

    @Test
    fun theSameRaceDuplicatesWhenCheckAndMintAreNotOneTransaction() {
        repeat(10) { i ->
            val run = device(transactions = false)
            try {
                val d = run.device
                race({ d.queue().status }, { d.queue().status })
                assertTrue(run.gate.met.get() > 0, "iteration $i: both writers were held in the window")
                assertTrue(drawn.any { d.liveNotesOf(it).size > 1 }, "iteration $i: without the transaction the entry is duplicated")
            } finally {
                run.close()
            }
        }
    }
}
