package kotobaya.android.queue

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotobaya.db.app.AppDatabase

/** One todayReviews row, comparable across the old query, the new one and the Kotlin oracle. */
data class TodayRow(val cardId: String, val noteId: String, val reviewType: String, val introducedNew: Boolean)

/** A review_log row as generated. */
data class GenReview(
    val id: String,
    val cardId: String,
    val hlc: String,
    val reviewedAt: Long,
    val type: String,
    val manualAction: String?,
    val undone: Boolean,
)

/**
 * AND-PERF: synthetic history shaped like the phone that starved the pool (9.5k cards, 78k review_log rows), with
 * merged cards (incl. a merge chain), same-reviewed_at ties broken by hlc, same-hlc ties broken by id, undone rows,
 * manual resets / set_dues, drill and cram rows, and a burst of answers today. No personal data.
 */
class SyntheticReviews(
    val cards: Int,
    val historyRows: Int,
    val todayRows: Int,
    val dayStart: Long,
    val now: Long,
    seed: Int = 7,
) {
    private val rnd = Random(seed)

    /** card id -> merged_into_id (null = live). */
    val mergedInto = LinkedHashMap<String, String?>()
    val noteOf = HashMap<String, String>()
    val reviews = ArrayList<GenReview>()

    fun cardId(n: Int) = "0193a1b2-0002-7000-8000-%012d".format(n)
    private fun noteId(n: Int) = "0193a1b2-0001-7000-8000-%012d".format(n)
    private fun reviewId() = "0193a1b2-%04x-7%03x-8%03x-%012x".format(
        rnd.nextInt(0x10000), rnd.nextInt(0x1000), rnd.nextInt(0x1000), rnd.nextLong(0, 0xFFFFFFFFFFFFL),
    )

    init {
        for (n in 1..cards) {
            mergedInto[cardId(n)] = null
            noteOf[cardId(n)] = noteId(n)
        }
        // ~5% of the cards are merge losers; two of them form a chain (a loser's survivor is itself merged).
        val ids = mergedInto.keys.toList()
        for (n in 1..cards) {
            if (rnd.nextInt(20) != 0) continue
            val loser = cardId(n)
            var survivor = ids[rnd.nextInt(ids.size)]
            if (survivor == loser) continue
            if (mergedInto[survivor] != null) survivor = mergedInto[survivor] ?: survivor
            if (survivor == loser) continue
            mergedInto[loser] = survivor
        }
        val losers = mergedInto.filterValues { it != null }.keys.toList()
        if (losers.size >= 2) {
            val l0 = losers[0]
            val s = mergedInto[losers[1]]
            if (s != null && s != l0 && mergedInto[s] == null && mergedInto[l0] != s) mergedInto[s] = l0
        }

        val historyStart = dayStart - 200L * 86_400_000L
        var last: GenReview? = null
        repeat(historyRows) { last = row(ids, historyStart, dayStart - 1, last, today = false).also(reviews::add) }
        last = null
        repeat(todayRows) { last = row(ids, dayStart, now, last, today = true).also(reviews::add) }
    }

    private fun row(ids: List<String>, from: Long, to: Long, prev: GenReview?, today: Boolean): GenReview {
        val tie = prev != null && rnd.nextInt(6) == 0
        val card = when {
            tie && prev != null -> prev.cardId
            today && rnd.nextInt(4) == 0 -> mergedInto.filterValues { it != null }.keys.randomOrNull(rnd) ?: ids.random(rnd)
            else -> ids[rnd.nextInt(if (today) minOf(ids.size, 400) else ids.size)]
        }
        val at = if (tie && prev != null) prev.reviewedAt else rnd.nextLong(from, to + 1)
        val hlc = if (tie && prev != null && rnd.nextBoolean()) prev.hlc else "%015d-%04d-%s".format(at, rnd.nextInt(10), "dev")
        val type = when (rnd.nextInt(20)) {
            in 0..10 -> "scheduled"
            in 11..15 -> "learning"
            16 -> "drill"
            17 -> "cram"
            else -> "manual"
        }
        val action = if (type == "manual") (if (rnd.nextBoolean()) "reset" else "set_due") else null
        return GenReview(reviewId(), card, hlc, at, type, action, undone = rnd.nextInt(20) == 0)
    }

    /** Writes notes, cards and review_log in one transaction. */
    fun insert(db: AppDatabase, driver: SqlDriver) = db.transaction {
        for ((card, merged) in mergedInto) {
            val note = noteOf.getValue(card)
            driver.execute(
                null,
                "INSERT INTO note (id, note_type_id, type, fields, created_by_device_id, created_at, updated_at) " +
                    "VALUES (?, '00000000-0000-7000-8000-000000000101', 'vocab', '{}', 'dev', 0, 0)",
                1,
            ) { bindString(0, note) }
            driver.execute(
                null,
                "INSERT INTO card (id, note_id, kind, created_at, updated_at, deleted_at, merged_into_id) VALUES (?, ?, 'meaning', 0, 0, ?, ?)",
                4,
            ) {
                bindString(0, card)
                bindString(1, note)
                bindLong(2, if (merged != null) 1L else null)
                bindString(3, merged)
            }
        }
        for (r in reviews) {
            driver.execute(
                null,
                "INSERT INTO review_log (id, card_id, device_id, hlc, reviewed_at, review_type, rating, manual_action, undone_at, undone_hlc) " +
                    "VALUES (?, ?, 'dev', ?, ?, ?, ?, ?, ?, ?)",
                9,
            ) {
                bindString(0, r.id)
                bindString(1, r.cardId)
                bindString(2, r.hlc)
                bindLong(3, r.reviewedAt)
                bindString(4, r.type)
                bindLong(5, if (r.type == "manual") null else 3L)
                bindString(6, r.manualAction)
                bindLong(7, if (r.undone) r.reviewedAt + 1 else null)
                bindString(8, if (r.undone) r.hlc else null)
            }
        }
    }

    /** What todayReviews must return for [from]..[to], computed in Kotlin from the generated rows. */
    fun expected(from: Long, to: Long): List<TodayRow> {
        fun resolve(card: String) = mergedInto[card] ?: card
        val order = compareBy<GenReview>({ it.reviewedAt }, { it.hlc }, { it.id })
        val byGroup = reviews.groupBy { resolve(it.cardId) }.mapValues { (_, v) -> v.sortedWith(order) }
        return reviews.filter { !it.undone && it.type in TODAY_TYPES && it.reviewedAt in from..to }.map { r ->
            val sc = resolve(r.cardId)
            val prev = byGroup.getValue(sc).lastOrNull { p -> !p.undone && p.type in PREV_TYPES && order.compare(p, r) < 0 }
            TodayRow(sc, noteOf.getValue(sc), r.type, r.type == "learning" && (prev == null || prev.manualAction == "reset"))
        }.sortedWith(ROW_ORDER)
    }

    companion object {
        val TODAY_TYPES = setOf("scheduled", "learning", "drill", "cram")
        val PREV_TYPES = setOf("scheduled", "learning", "manual")
        val ROW_ORDER = compareBy<TodayRow>({ it.cardId }, { it.reviewType }, { it.introducedNew }, { it.noteId })

        /** The todayReviews query as shipped in 0.1.10 (before AND-PERF), for the equivalence test. */
        const val OLD_TODAY_REVIEWS = """
SELECT x.card_id, x.note_id, x.review_type,
       (x.review_type = 'learning' AND (x.prev_id IS NULL OR (SELECT manual_action FROM review_log WHERE id = x.prev_id) IS 'reset')) AS introduced_new
FROM (
  SELECT sc.id AS card_id, sc.note_id AS note_id, r.review_type AS review_type,
         (SELECT p.id FROM review_log p JOIN card pc ON pc.id = p.card_id
           WHERE COALESCE(pc.merged_into_id, pc.id) = sc.id AND p.undone_at IS NULL
             AND p.review_type IN ('scheduled', 'learning', 'manual')
             AND (p.reviewed_at, p.hlc, p.id) < (r.reviewed_at, r.hlc, r.id)
           ORDER BY p.reviewed_at DESC, p.hlc DESC, p.id DESC LIMIT 1) AS prev_id
  FROM review_log r
  JOIN card c ON c.id = r.card_id
  JOIN card sc ON sc.id = COALESCE(c.merged_into_id, c.id)
  WHERE r.undone_at IS NULL AND r.review_type IN ('scheduled', 'learning', 'drill', 'cram')
    AND r.reviewed_at >= ? AND r.reviewed_at <= ?
) x"""

        fun oldQuery(driver: SqlDriver, from: Long, to: Long): List<TodayRow> =
            driver.executeQuery(null, OLD_TODAY_REVIEWS, { c -> QueryResult.Value(rows(c)) }, 2) {
                bindLong(0, from)
                bindLong(1, to)
            }.value.sortedWith(ROW_ORDER)

        private fun rows(c: SqlCursor): List<TodayRow> {
            val out = ArrayList<TodayRow>()
            while (c.next().value) out += TodayRow(c.getString(0)!!, c.getString(1)!!, c.getString(2)!!, c.getLong(3) == 1L)
            return out
        }

        fun newQuery(db: AppDatabase, from: Long, to: Long): List<TodayRow> =
            db.localQueueQueries.todayReviews(from, to).executeAsList()
                .map { TodayRow(it.card_id, it.note_id, it.review_type, it.introduced_new) }.sortedWith(ROW_ORDER)
    }
}

/**
 * Records every SQL text it runs (with its binders, for EXPLAIN QUERY PLAN of a generated query) and counts how many
 * statements whose text contains [watch] run at once.
 */
class RecordingDriver(private val d: SqlDriver, private val watch: String = "", private val holdMs: Long = 0) : SqlDriver by d {
    val queries = ArrayList<Triple<String, Int, (SqlPreparedStatement.() -> Unit)?>>()
    private val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()
    val watchedRuns = AtomicInteger()

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        synchronized(queries) { queries += Triple(sql, parameters, binders) }
        if (watch.isEmpty() || !sql.contains(watch)) return d.executeQuery(identifier, sql, mapper, parameters, binders)
        watchedRuns.incrementAndGet()
        val n = inFlight.incrementAndGet()
        maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
        try {
            if (holdMs > 0) Thread.sleep(holdMs)
            return d.executeQuery(identifier, sql, mapper, parameters, binders)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** EXPLAIN QUERY PLAN detail lines of the last recorded statement containing [marker]. */
    fun plan(marker: String): List<String> {
        val (sql, parameters, binders) = synchronized(queries) { queries.last { it.first.contains(marker) } }
        return d.executeQuery(null, "EXPLAIN QUERY PLAN $sql", { c ->
            val out = ArrayList<String>()
            while (c.next().value) out += c.getString(3).orEmpty()
            QueryResult.Value(out)
        }, parameters, binders).value
    }
}
