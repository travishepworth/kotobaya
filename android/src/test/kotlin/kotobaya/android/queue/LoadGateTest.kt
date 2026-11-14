package kotobaya.android.queue

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.db.createAppDatabase

/** AND-PERF: concurrent load triggers never run todayReviews in parallel and collapse into few builds. */
class LoadGateTest {
    private fun concurrently(n: Int, body: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        val failures = AtomicInteger()
        repeat(n) { i ->
            pool.execute {
                start.await()
                runCatching { body(i) }.onFailure { failures.incrementAndGet(); it.printStackTrace() }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(0, failures.get())
    }

    @Test
    fun concurrentTriggersOfSeveralConsumersRunOneTodayReviewsAtATime() {
        val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createAppDatabase(raw)
        val driver = RecordingDriver(raw, watch = "AS introduced_new", holdMs = 100)
        val db = kotobaya.db.app.AppDatabase(driver)
        SyntheticReviews(cards = 50, historyRows = 300, todayRows = 30, dayStart = NOW - 3_600_000, now = NOW).insert(db, raw)
        val gate = LoadGate()
        // home, widget, notifications, review: one loader each, one gate (as Koin wires them)
        val loaders = List(4) { LocalQueueLoader(db, { NOW }, gate = gate) }
        concurrently(16) { i -> loaders[i % 4].load() }
        assertEquals(1, driver.maxInFlight.get())
        assertEquals(0, gate.liveSlots(), "no queue retained once the loads are done")
        // all 16 arrive while the first build runs: that build and one more serve them
        assertTrue(driver.watchedRuns.get() <= 2, "builds: ${driver.watchedRuns.get()}")
    }

    @Test
    fun aCallerNeverGetsABuildThatStartedBeforeItsCall() {
        val gate = LoadGate()
        val builds = AtomicInteger()
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Thread { gate.run("k") { running.countDown(); release.await(); builds.incrementAndGet() } }
        first.start()
        running.await()
        var second = 0
        val t = Thread { second = gate.run("k") { builds.incrementAndGet() } }
        t.start()
        Thread.sleep(50)
        release.countDown()
        first.join()
        t.join()
        assertEquals(2, second, "the waiting caller got a fresh build, not the one already running")
        // a later caller with nothing in flight builds again; other keys are independent
        assertEquals(3, gate.run("k") { builds.incrementAndGet() })
        assertEquals(-1, gate.run("other") { -1 })
    }

    @Test
    fun aFailedBuildIsNotShared() {
        val gate = LoadGate()
        assertTrue(runCatching { gate.run("k") { error("boom") } }.isFailure)
        assertEquals(1, gate.run("k") { 1 })
    }

    @Test
    fun theGateRetainsNoResultOnceItsCallersLeft() {
        val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = createAppDatabase(raw)
        SyntheticReviews(cards = 50, historyRows = 300, todayRows = 30, dayStart = NOW - 3_600_000, now = NOW).insert(db, raw)
        val gate = LoadGate()
        val loader = LocalQueueLoader(db, { NOW }, gate = gate)
        // a review session: one key per answer (newShownThisSession), plus a per-deck load
        for (newShown in 0..20) loader.load(newShownThisSession = newShown)
        loader.load(deckFilter = "d1")
        assertEquals(0, gate.liveSlots())
        assertTrue(runCatching { gate.run("k") { error("boom") } }.isFailure)
        assertEquals(0, gate.liveSlots())
    }

    /** todayReviews pins `INDEXED BY review_log_replay_idx`: without this index (contract schema) the query throws. */
    @Test
    fun theReplayIndexTodayReviewsPinsExistsWithItsColumns() {
        val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createAppDatabase(raw)
        val cols = raw.executeQuery(null, "SELECT name FROM pragma_index_info('review_log_replay_idx') ORDER BY seqno", { c ->
            val out = ArrayList<String>()
            while (c.next().value) out += c.getString(0).orEmpty()
            app.cash.sqldelight.db.QueryResult.Value(out)
        }, 0).value
        assertEquals(listOf("card_id", "reviewed_at", "hlc", "id"), cols)
    }

    private companion object {
        const val NOW = 1_794_531_600_000L
    }
}
