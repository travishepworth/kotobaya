package kotobaya.android.shell.perf

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.db.APP_DB_FILE
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityTemp
import kotobaya.android.sync.DurableDriver
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.store.bookkeeping
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PERF-LF: the Home screen's local handlers on a user-sized replica ([ReplicaScale]), on the production driver
 * (AndroidSqliteDriver over a WAL file with the framework's connection pool, Robolectric native SQLite).
 *
 * Home opens with `getReviewQueue` and, in parallel (LF-W2), `listDecks`, `listHotWords`, `listHotKanji` and the three
 * stats; a `sync.changed` after the app-open pull fetches them all again while a sync apply writes. The review queue
 * must answer fast through all of that: it never waits behind the stats or hot lists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HomeLoadBenchmarkTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    /** A real replica file (built by [RealReplicaBuilder], outside the repository) instead of the synthetic one. */
    private val realReplica: String? = System.getenv("PERF_LF_REPLICA")
    private val driver = run {
        app.deleteDatabase(APP_DB_FILE)
        realReplica?.let { java.io.File(it).copyTo(app.getDatabasePath(APP_DB_FILE).also { f -> f.parentFile?.mkdirs() }, overwrite = true) }
        DurableDriver.open(app)
    }
    private val now = System.getenv("PERF_LF_NOW")?.toLong() ?: 1_795_000_000_000L // 2026-11-18T11:06:40Z
    /** A real dictionary package (built from a backup's `ref` schema by the worker, outside the repository). */
    private val realDict: String? = System.getenv("PERF_LF_DICT")
    private val device = ParityDevice(
        "01931f3e-0000-7000-8000-0000000000d4", now, LocalRoutes.all, dictDir = ParityTemp.next("perf-dict"), driver = driver,
        withDictionary = realDict == null,
    ).also { d -> realDict?.let { java.io.File(it).copyTo(d.dictStore.installed.also { f -> f.parentFile?.mkdirs() }, overwrite = true) } }
    private val seeded = if (realReplica != null) ReplicaScale.counts(driver) else ReplicaScale.seed(driver, device.store, now)

    @AfterTest
    fun tearDown() {
        driver.close()
        app.deleteDatabase(APP_DB_FILE)
    }

    private class Call(val name: String, val path: String, val query: String)

    private val queue = Call("getReviewQueue", "/api/v1/review/queue", "limit=30")
    private val others = listOf(
        Call("listDecks", "/api/v1/decks", ""),
        Call("listHotWords", "/api/v1/words/hot", "limit=50"),
        Call("listHotKanji", "/api/v1/kanji/hot", "limit=30"),
        Call("getStatsSummary", "/api/v1/stats/summary", ""),
        Call("getStatsHeatmap", "/api/v1/stats/heatmap", ""),
        Call("getStatsRetention", "/api/v1/stats/retention", "group_by=card_kind"),
    )

    private fun timed(c: Call): Long {
        val t0 = System.nanoTime()
        val r = device.request("GET", c.path, c.query, null)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(r.status in 200..299, "${c.name}: ${r.status} ${r.body?.take(300)}")
        return ms
    }

    /** Each handler alone (warm: the first call of each also loads classes and the statement caches). */
    private fun alone(): Map<String, Long> {
        (others + queue).forEach { timed(it) }
        return (others + queue).associate { c -> c.name to (0 until 2).minOf { timed(c) } }
    }

    /**
     * Home's open as the phone saw it: every Home request twice (the open, then the refetch on `sync.changed`), all at
     * once on an IO-sized pool, while a sync apply commits short write transactions. Returns each request's time.
     */
    private fun homeOpen(): List<Pair<String, Long>> {
        val pool = Executors.newFixedThreadPool(32)
        val syncing = AtomicBoolean(true)
        val sync = Thread {
            var n = 0
            while (syncing.get()) {
                device.store.transaction { tx ->
                    // a pull sub-page: a few learning rows rewritten
                    repeat(20) {
                        tx.learning.put(
                            LearningTable.SETTINGS,
                            mapOf("key" to JsonString("perf.$it"), "value" to JsonNumber((n++).toDouble()), "hlc" to JsonString("h$n"), "updated_at" to JsonNumber(now.toDouble())),
                        )
                    }
                }
                Thread.sleep(5)
            }
        }
        sync.start()
        try {
            val calls = (listOf(queue) + others + listOf(queue) + others)
            val futures = calls.map { c -> pool.submit(Callable { c.name to timed(c) }) }
            return futures.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            syncing.set(false)
            sync.join()
            pool.shutdownNow()
        }
    }

    @Test
    fun homeHandlersOnAUserSizedReplica() {
        assertTrue(seeded.reviews > 20_000, "review_log rows: ${seeded.reviews}")
        if (realReplica != null) device.store.transaction { it.bookkeeping.lastSyncAt = now - 60_000 }
        // developer profiling: PERF_LF_LOOP=<operationId>:<n> repeats one handler first (e.g. under JFR)
        System.getenv("PERF_LF_LOOP")?.split(':')?.let { (id, n) ->
            val c = (others + queue).first { it.name == id }
            val t = (0 until n.toInt()).map { timed(c) }
            println("PERF-LF loop $id: ${t.joinToString()}")
        }
        val single = alone()
        val concurrent = homeOpen()
        val report = buildString {
            appendLine("PERF-LF replica: ${seeded.notes} notes, ${seeded.cards} cards, ${seeded.reviews} reviews, ${seeded.deckItems} deck items")
            single.forEach { (k, v) -> appendLine("  alone      $k: $v ms") }
            concurrent.forEach { (k, v) -> appendLine("  concurrent $k: $v ms") }
        }
        println(report)
        val queueConcurrent = concurrent.filter { it.first == queue.name }.maxOf { it.second }
        assertEquals(0, single.filterValues { it > ALONE_MS }.size, "a Home handler alone took over $ALONE_MS ms:\n$report")
        assertTrue(single.getValue(queue.name) < QUEUE_MS, "getReviewQueue alone:\n$report")
        assertTrue(queueConcurrent < QUEUE_MS, "getReviewQueue during Home's load and a sync:\n$report")
    }

    companion object {
        /** The ticket's bounds (JVM; the phone is slower, so these are the regression tripwires, not the phone budget). */
        const val QUEUE_MS = 300L
        const val ALONE_MS = 500L
    }
}
