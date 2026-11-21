package kotobaya.android.shell.perf

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotobaya.android.db.APP_DB_FILE
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
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
 * PERF-LF: the Home screen's and the review screen's local handlers on a user-sized replica ([ReplicaScale], scaled to
 * the real replica's row counts), on the production driver (AndroidSqliteDriver over a WAL file with the framework's
 * connection pool, Robolectric native SQLite).
 *
 * Home opens with `getReviewQueue` (limit 30) and, in parallel (LF-W2), `listDecks`, `listHotWords`, `listHotKanji` and
 * the three stats; a `sync.changed` after the app-open pull fetches them all again while the pull applies. The review
 * queue must answer fast through all of that (it never waits behind the stats or hot lists), each Home handler must be
 * fast on its own, and an answer must be recorded at once.
 *
 * Developer use (nothing of it is committed): `PERF_LF_REPLICA` = a replica file built by [RealReplicaBuilder] from a
 * backup, `PERF_LF_DICT` = a dictionary package built from the same backup's `ref` schema, `PERF_LF_NOW` = the instant to
 * measure at, `PERF_LF_LOOP=<operationId>:<n>` = repeat one handler first (e.g. under a profiler).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HomeLoadBenchmarkTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val realReplica: String? = System.getenv("PERF_LF_REPLICA")
    private val realDict: String? = System.getenv("PERF_LF_DICT")
    private val driver = run {
        app.deleteDatabase(APP_DB_FILE)
        realReplica?.let { java.io.File(it).copyTo(app.getDatabasePath(APP_DB_FILE).also { f -> f.parentFile?.mkdirs() }, overwrite = true) }
        DurableDriver.open(app)
    }
    private val now = System.getenv("PERF_LF_NOW")?.toLong() ?: 1_795_000_000_000L // 2026-11-18T11:06:40Z
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

    private fun ms(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

    private fun timed(c: Call): Long {
        val t0 = System.nanoTime()
        val r = device.request("GET", c.path, c.query, null)
        val took = ms(t0)
        assertTrue(r.status in 200..299, "${c.name}: ${r.status} ${r.body?.take(300)}")
        return took
    }

    /** Each handler alone, warm (the first calls also build the process caches and load classes). */
    private fun alone(): Map<String, Long> {
        repeat(2) { (others + queue).forEach { timed(it) } }
        return (others + queue).associate { c -> c.name to (0 until 3).minOf { timed(c) } }
    }

    private val startedCards: List<String> by lazy {
        driver.executeQuery(null, "SELECT id FROM card WHERE state <> 'new' AND deleted_at IS NULL ORDER BY id LIMIT 500", { c: SqlCursor ->
            val out = ArrayList<String>()
            while (c.next().value) out += checkNotNull(c.getString(0))
            QueryResult.Value(out)
        }, 0).value
    }

    /**
     * A pull applying: short write transactions, each a few reviews another device logged (a new `review_log` row and
     * its card's new schedule) and a setting, through the learning tables as the applier writes them.
     */
    private fun syncWriter(running: AtomicBoolean): Thread = Thread {
        val rnd = Random(9)
        var n = 0
        while (running.get()) {
            device.store.transaction { tx ->
                repeat(5) {
                    val card = startedCards[rnd.nextInt(startedCards.size)]
                    val at = now - 60_000 + n
                    tx.learning.put(
                        LearningTable.REVIEW_LOG,
                        mapOf(
                            "id" to JsonString("0193ffff-0000-7000-8000-%012x".format(n++)), "card_id" to JsonString(card),
                            "device_id" to JsonString("00000000-0000-7000-8000-000000000001"), "hlc" to JsonString("%013d-000000-perf".format(at)),
                            "reviewed_at" to JsonNumber(at.toDouble()), "review_type" to JsonString("scheduled"), "rating" to JsonNumber(3.0),
                            "button" to JsonString("good"), "duration_ms" to JsonNumber(5000.0),
                        ),
                    )
                    val due = now + 86_400_000L * (1 + rnd.nextInt(30))
                    tx.learning.put(LearningTable.CARD, mapOf("id" to JsonString(card), "due_at" to JsonNumber(due.toDouble()), "updated_at" to JsonNumber(at.toDouble())))
                }
                tx.learning.put(
                    LearningTable.SETTINGS,
                    mapOf("key" to JsonString("perf.sync"), "value" to JsonNumber(n.toDouble()), "hlc" to JsonString("h$n"), "updated_at" to JsonNumber(now.toDouble())),
                )
            }
            Thread.sleep(20)
        }
    }

    /**
     * Home's open as the phone saw it: every Home request twice (the open, then the refetch on `sync.changed`), all at
     * once on an IO-sized pool, while a sync applies. Returns each request's time.
     */
    private fun homeOpen(): List<Pair<String, Long>> {
        val pool = Executors.newFixedThreadPool(32)
        val running = AtomicBoolean(true)
        val sync = syncWriter(running).also { it.start() }
        try {
            val calls = listOf(queue) + others + listOf(queue) + others
            val futures = calls.map { c -> pool.submit(Callable { c.name to timed(c) }) }
            return futures.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            running.set(false)
            sync.join()
            pool.shutdownNow()
        }
    }

    /** Answers the first card of the queue [n] times: (answerReview ms, the next getReviewQueue ms) each. */
    private fun answers(n: Int): List<Pair<Long, Long>> = (0 until n).map { i ->
        val q = device.request("GET", queue.path, queue.query, null)
        val items = ParityJson.arr(ParityJson.at(ParityJson.parse(q.body ?: "{}"), "/items"))
        val cardId = ParityJson.str(ParityJson.at(items.first(), "/card/id"))
        val body = """{"id":"0193eeee-0000-7000-8000-%012x","card_id":"$cardId","rating":3,"duration_ms":4200,"utc_offset_min":540}""".format(i)
        val t0 = System.nanoTime()
        val a = device.request("POST", "/api/v1/review/answer", "", ParityJson.parse(body))
        val answer = ms(t0)
        assertTrue(a.status in 200..299, "answerReview: ${a.status} ${a.body?.take(300)}")
        answer to timed(queue)
    }

    private fun p95(xs: List<Long>): Long = xs.sorted()[((xs.size - 1) * 95) / 100]

    @Test
    fun homeAndReviewHandlersOnAUserSizedReplica() {
        assertTrue(seeded.reviews > 20_000, "review_log rows: ${seeded.reviews}")
        if (realReplica != null) device.store.transaction { it.bookkeeping.lastSyncAt = now - 60_000 }
        System.getenv("PERF_LF_LOOP")?.split(':')?.let { (id, k) ->
            val c = (others + queue).first { it.name == id }
            println("PERF-LF loop $id: ${(0 until k.toInt()).map { timed(c) }.joinToString()}")
        }
        val single = alone()
        val rounds = (0 until 3).map { homeOpen() }
        val queueTimes = rounds.flatten().filter { it.first == queue.name }.map { it.second }
        val answered = answers(12).drop(2)
        val report = buildString {
            appendLine("PERF-LF replica: ${seeded.notes} notes, ${seeded.cards} cards, ${seeded.reviews} reviews, ${seeded.deckItems} deck items")
            single.forEach { (k, v) -> appendLine("  alone      $k: $v ms") }
            rounds.last().forEach { (k, v) -> appendLine("  concurrent $k: $v ms") }
            appendLine("  concurrent getReviewQueue over ${queueTimes.size} calls: p95 ${p95(queueTimes)} ms, max ${queueTimes.max()} ms")
            appendLine("  answerReview p95 ${p95(answered.map { it.first })} ms; next getReviewQueue p95 ${p95(answered.map { it.second })} ms")
        }
        println(report)
        assertTrue(single.values.all { it < ALONE_MS }, "a handler alone took over $ALONE_MS ms:\n$report")
        assertTrue(p95(queueTimes) < QUEUE_MS, "getReviewQueue during Home's load and a sync:\n$report")
        assertTrue(p95(answered.map { it.first }) < ANSWER_MS, "answerReview:\n$report")
    }

    companion object {
        /**
         * Regression tripwires on a JVM (generous: about twice what this replica measures here; the phone is slower, the
         * measured numbers are in the PERF-LF handoff). Before PERF-LF the same replica measured seconds.
         */
        const val QUEUE_MS = 300L
        const val ALONE_MS = 500L
        const val ANSWER_MS = 100L
    }
}
