package kotobaya.stats

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import kotobaya.auth.AuthFixture
import kotobaya.auth.TokenKind
import kotobaya.queue.DefaultQueueBuilder
import kotobaya.review.QueueLoader
import kotobaya.review.ReviewSettings
import kotobaya.server.StartupSequence
import kotobaya.server.json
import java.sql.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BUG-1: every stats endpoint answers in under 1 s on live-sized data (~90k review_log rows over ~10k cards, some merged),
 * and no stats query plans a nested loop of review_log against review_log.
 */
class StatsPerfTest : AuthFixture() {
    private val endMs = now.get().toEpochMilli()

    private fun seed() {
        StartupSequence(db).runOnce()
        val dev = insertDevice()
        // 4750 notes x 2 live cards + 475 merge losers = 9975 cards; x 9 reviews = 89,775 rows over two years.
        db.txBlocking { StatsSeed.seed(it, dev.toString(), notes = 4750, perCard = 9, spanDays = 730, endMs = endMs) }
    }

    private fun timed(paths: List<String>, extra: () -> Unit = {}): Map<String, List<Long>> {
        seed()
        extra()
        val rows = db.txBlocking { (it.fetchValue("select count(*) from app.review_log") as Number).toLong() }
        val cards = db.txBlocking { (it.fetchValue("select count(*) from app.card") as Number).toLong() }
        assertTrue(rows in 80_000..100_000 && cards > 9_000, "seeded $rows reviews over $cards cards")
        val timings = LinkedHashMap<String, MutableList<Long>>()
        app(extraHandlers = StatsModule(db, clock = { now.get() }).handlers) {
            val reader = mint(TokenKind.PERSONAL, listOf("read")).second
            repeat(2) {
                for (p in paths) {
                    val t0 = System.nanoTime()
                    val r = client.call(HttpMethod.Get, p, bearer = reader)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    assertEquals(200, r.status.value, p)
                    timings.getOrPut(p) { mutableListOf() } += ms
                }
            }
            println("BUG-1 stats timings at $rows reviews / $cards cards (ms, cold then warm):")
            timings.forEach { (p, t) -> println("  $p ${t.joinToString(" / ")}") }
        }
        return timings
    }

    private fun assertFast(timings: Map<String, List<Long>>) {
        for ((p, t) in timings) assertTrue(t.all { it < 1000 }, "$p took $t ms")
    }

    @Test
    fun everyStatsEndpointExceptSummaryAnswersWithinOneSecond() = assertFast(
        timed(
            listOf(
                "/stats/heatmap",
                "/stats/heatmap?from=2016-11-20&to=2026-11-12",
                "/stats/retention?group_by=card_kind",
                "/stats/retention?group_by=day",
                "/stats/forecast?days=30",
                "/stats/known-words?interval=week",
                "/stats/known-words?interval=day",
                "/stats/calibration",
            ),
        ),
    )

    /**
     * Today's answers for the queue: 1,000 cards get a `learning` row just before now, half of them right after a reset, so
     * `QueueLoader.loadToday` has learning roots to window (the seed alone puts every learning row two years back).
     */
    private fun answerToday() = db.txBlocking { dsl ->
        dsl.execute(
            "with picked as (select c.id, row_number() over (order by c.id) as i from app.card c " +
                "  where c.deleted_at is null order by c.id limit 1000), " +
                "rows as (select p.id as card_id, p.i, k, ?::bigint - 600000 + p.i * 100 + k * 10 as ms from picked p " +
                "  cross join generate_series(0, 1) k where k = 1 or p.i % 2 = 0) " +
                "insert into app.review_log (id, card_id, device_id, hlc, reviewed_at, utc_offset_min, review_type, rating, duration_ms, manual_action) " +
                "select gen_random_uuid(), card_id, d.id, lpad(to_hex(ms), 12, '0') || '-0000-' || d.id::text, to_timestamp(ms / 1000.0), null, " +
                "  case when k = 0 then 'manual' else 'learning' end, case when k = 0 then null else 3 end, 1000, case when k = 0 then 'reset' end " +
                "from rows cross join (select id from app.device limit 1) d",
            endMs,
        )
        dsl.execute("analyze app.review_log")
    }

    @Test
    fun summaryAnswersWithinOneSecond() {
        val timings = timed(listOf("/stats/summary")) { answerToday() }
        assertFast(timings)
    }

    /** `GET /review/queue` does exactly this in its transaction (load + core build) before mapping the items. */
    @Test
    fun reviewQueueLoadsWithinOneSecond() {
        seed()
        answerToday()
        val times = (1..2).map {
            val t0 = System.nanoTime()
            val today = db.txBlocking { dsl ->
                val loaded = QueueLoader(dsl).load(endMs, ReviewSettings.load(dsl))
                DefaultQueueBuilder.build(loaded.input)
                loaded.input.today
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(today.size >= 1000 && today.count { it.introducedNew } >= 500, "today ${today.size} / ${today.count { it.introducedNew }}")
            ms
        }
        println("BUG-1 review queue load+build (ms, cold then warm): ${times.joinToString(" / ")}")
        assertTrue(times.all { it < 1000 }, "queue took $times ms")
    }

    /** True when [node]'s subtree scans app.review_log. */
    private fun scansReviewLog(node: JsonNode): Boolean =
        node["Relation Name"]?.asText() == "review_log" || node["Plans"]?.any(::scansReviewLog) == true

    /** Nested loops (or per-row subplans) whose both sides read review_log: the quadratic shape BUG-1 removed. */
    private fun quadratic(node: JsonNode, out: MutableList<String>) {
        val kids = node["Plans"]?.toList().orEmpty()
        val perRow = node["Node Type"].asText() == "Nested Loop" || kids.any { it["Parent Relationship"]?.asText() == "SubPlan" }
        if (perRow && kids.count(::scansReviewLog) >= 2) out += node["Node Type"].asText()
        kids.forEach { quadratic(it, out) }
    }

    @Test
    fun noStatsQueryPlansANestedLoopOverReviewLogTwice() {
        seed()
        db.txBlocking { dsl ->
            val lo = Timestamp(endMs - 366L * 86_400_000)
            val hi = Timestamp(endMs + 3 * 86_400_000)
            val todayLo = Timestamp(endMs - 2 * 86_400_000)
            val roots = dsl.fetch(SURVIVORS_SQL, lo, hi).map { it.get(0, String::class.java) }.toTypedArray()
            val cases = mapOf(
                "dayRows year" to (DAY_ROWS_SQL to arrayOf<Any>(lo, hi, lo, hi, lo, hi)),
                "dayRows today" to (DAY_ROWS_SQL to arrayOf<Any>(todayLo, hi, todayLo, hi, todayLo, hi)),
                "survivors" to (SURVIVORS_SQL to arrayOf<Any>(lo, hi)),
                "history" to (HISTORY_SQL to arrayOf<Any>(roots)),
                "history few" to (HISTORY_SQL to arrayOf<Any>(roots.take(5).toTypedArray())),
                "streak" to (STREAK_SQL to arrayOf()),
                "queue today" to (QueueLoader.TODAY_SQL to arrayOf<Any>(todayLo, hi, todayLo, hi)),
                // Every root has a learning row in two years: the window covers all of review_log.
                "queue today all" to (QueueLoader.TODAY_SQL to arrayOf<Any>(Timestamp(0), hi, Timestamp(0), hi)),
            )
            for ((name, q) in cases) {
                val text = dsl.fetchValue("explain (format json) " + q.first, *q.second).toString()
                val plan = json.readTree(text)[0]["Plan"]
                val bad = mutableListOf<String>()
                quadratic(plan, bad)
                if (bad.isNotEmpty()) fail("$name plans review_log x review_log: $bad\n$text")
            }
        }
    }
}
