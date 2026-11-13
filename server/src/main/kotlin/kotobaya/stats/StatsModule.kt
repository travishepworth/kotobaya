package kotobaya.stats

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.QueueBuilder
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.notes.Errors
import kotobaya.queue.DefaultQueueBuilder
import kotobaya.review.QueueLoader
import kotobaya.review.ReviewSettings
import kotobaya.review.rolloverMs
import kotobaya.review.studyDayOf
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.formatInstant
import kotobaya.server.respondJson
import org.jooq.DSLContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import kotlin.math.min

private val BLOCKED = setOf("known", "never_forget", "blacklisted", "ignored")
private const val HOUR_MS = 3_600_000L
private const val MAX_RANGE_DAYS = 3660L
private const val BINS = 20

/**
 * Handlers for the six `stats` operations (T605), keyed by operationId for `AppDeps.handlers`. Read-only: no ops.
 * Summary counts come from the core queue builder through the review module's loader, so they equal `GET /review/queue`.
 * Wiring (Main.kt): `handlers += StatsModule(db).handlers`.
 */
class StatsModule(
    private val db: Database,
    private val clock: () -> Instant = Instant::now,
    private val builder: QueueBuilder = DefaultQueueBuilder,
) {
    val handlers: Map<String, OperationHandler> = mapOf(
        "getStatsSummary" to { summary(this) },
        "getStatsHeatmap" to { heatmap(this) },
        "getStatsRetention" to { retention(this) },
        "getStatsForecast" to { forecast(this) },
        "getStatsKnownWords" to { knownWords(this) },
        "getStatsCalibration" to { calibration(this) },
    )

    /** Soft memory guard: at most one history-replaying computation runs at a time, however many panels ask at once. */
    private val heavy = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class ReplayKey(val from: LocalDate, val to: LocalDate, val zone: ZoneId, val hour: Int)
    private val inflight = HashMap<ReplayKey, Deferred<List<ScheduledReview>>>()

    /**
     * Every scheduled review in the window, replayed once. Concurrent callers (retention and calibration of one stats page)
     * join the same in-flight computation; the entry is dropped as soon as it finishes, so nothing is served stale.
     */
    private suspend fun scheduledShared(from: LocalDate, to: LocalDate, zone: ZoneId, hour: Int): List<ScheduledReview> {
        val key = ReplayKey(from, to, zone, hour)
        val job = synchronized(inflight) {
            inflight.getOrPut(key) {
                scope.async {
                    try {
                        heavy.withPermit { db.tx { dsl -> scheduledReviews(dsl, from, to, zone, hour, null) } }
                    } finally {
                        synchronized(inflight) { inflight.remove(key) }
                    }
                }
            }
        }
        return job.await()
    }

    private class Range(val from: LocalDate, val to: LocalDate)

    /** `from`/`to` query parameters with the contract defaults; 422 on a bad date, `from > to` or a range over 3660 days. */
    private fun range(call: ApplicationCall, today: LocalDate, e: Errors): Range? {
        fun date(name: String): LocalDate? = call.request.queryParameters[name]?.let { v ->
            try {
                LocalDate.parse(v)
            } catch (x: DateTimeParseException) {
                e.add("/$name", "invalid_value", "Must be a date YYYY-MM-DD.")
                null
            }
        }
        val to = date("to")
        val from = date("from")
        if (e.any) return null
        val t = to ?: today
        val f = from ?: t.minusDays(364)
        if (f.isAfter(t)) e.add("/from", "out_of_range", "from is after to.")
        else if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_RANGE_DAYS) e.add("/from", "out_of_range", "The range may span at most $MAX_RANGE_DAYS days.")
        if (e.any) return null
        return Range(f, t)
    }

    private fun todayOf(settings: ReviewSettings, nowMs: Long) = studyDayOf(nowMs, settings.timezone, settings.dayRolloverHour)

    private suspend fun summary(call: ApplicationCall) {
        val nowMs = clock().toEpochMilli()
        val body = db.tx { dsl ->
            val settings = ReviewSettings.load(dsl)
            val day = todayOf(settings, nowMs)
            val loaded = QueueLoader(dsl).load(nowMs, settings)
            val items = builder.build(loaded.input).items
            val shown = items.mapTo(HashSet()) { it.cardId }
            val laterLearning = loaded.candidates.count { c ->
                c.cardId !in shown && (c.state == CardState.LEARNING || c.state == CardState.RELEARNING) &&
                    (c.buriedUntil == null || c.buriedUntil!! <= nowMs) && c.wordOverride?.wire !in BLOCKED &&
                    c.dueAt != null && c.dueAt!! < day.day.endMs
            }
            val today = dayRows(dsl, day.date, day.date, settings.timezone, settings.dayRolloverHour)
            val scheduled = today.filter { it.type == "scheduled" && it.rating != null }
            val streak = streak(dsl, day.date, settings)
            val totals = dsl.fetchOne(
                "select (select count(*) from app.note where deleted_at is null), " +
                    "count(*), count(*) filter (where c.suspended), count(*) filter (where c.lapses >= ?) " +
                    "from app.card c join app.note n on n.id = c.note_id " +
                    "where c.deleted_at is null and c.merged_into_id is null and n.deleted_at is null",
                settings.leechThreshold,
            )!!
            val next = dsl.fetchOne(
                "select ${msOf("min(c.due_at)")} from app.card c join app.note n on n.id = c.note_id " +
                    "where c.deleted_at is null and c.merged_into_id is null and n.deleted_at is null and not c.suspended and c.due_at > ?::timestamptz",
                java.sql.Timestamp(nowMs),
            )!!.get(0) as Number?
            linkedMapOf(
                "study_day" to day.date.toString(),
                "day_ends_at" to formatInstant(Instant.ofEpochMilli(day.day.endMs)),
                "due" to linkedMapOf(
                    "new" to items.count { it.kind == QueueItemKind.NEW },
                    "learning" to items.count { it.kind == QueueItemKind.LEARNING } + laterLearning,
                    "review" to items.count { it.kind == QueueItemKind.REVIEW },
                ),
                "done_today" to linkedMapOf(
                    "reviews" to today.size,
                    "new_cards" to today.count { it.introduced },
                    "time_ms" to today.sumOf { it.durationMs },
                    "retention" to if (scheduled.isEmpty()) null else scheduled.count { it.rating!! > 1 }.toDouble() / scheduled.size,
                ),
                "totals" to linkedMapOf(
                    "notes" to (totals.get(0) as Number).toLong(),
                    "cards" to (totals.get(1) as Number).toLong(),
                    "suspended" to (totals.get(2) as Number).toLong(),
                    "leeches" to (totals.get(3) as Number).toLong(),
                ),
                "streak_days" to streak,
                "next_due_at" to next?.let { formatInstant(Instant.ofEpochMilli(it.toLong())) },
            )
        }
        call.respondJson(body)
    }

    /** Consecutive study days with a review, ending today or yesterday; reads newest-first and stops at the first gap. */
    private fun streak(dsl: DSLContext, today: LocalDate, s: ReviewSettings): Int {
        var last: LocalDate? = null
        var n = 0
        // fetchSize streams (we are inside a transaction), so a short streak reads a few pages instead of the whole log.
        dsl.resultQuery(STREAK_SQL).fetchSize(500).fetchLazy().use { cur ->
            while (cur.hasNext()) {
                val r = cur.fetchNext() ?: break
                val d = studyDayOfReview((r.get(0) as Number).toLong(), (r.get(1) as Number?)?.toInt(), s.timezone, s.dayRolloverHour)
                if (d.isAfter(today)) continue
                val l = last
                if (l == null) {
                    if (d.isBefore(today.minusDays(1))) return 0
                    last = d
                    n = 1
                } else if (!d.isBefore(l)) {
                    continue
                } else if (d == l.minusDays(1)) {
                    last = d
                    n++
                } else {
                    break
                }
            }
        }
        return n
    }

    private suspend fun heatmap(call: ApplicationCall) {
        val e = Errors()
        val nowMs = clock().toEpochMilli()
        val body = heavy.withPermit { db.tx { dsl ->
            val settings = ReviewSettings.load(dsl)
            val r = range(call, todayOf(settings, nowMs).date, e)
            e.throwIfAny()
            r!!
            val days = dayRows(dsl, r.from, r.to, settings.timezone, settings.dayRolloverHour)
                .groupBy { it.day }.toSortedMap().map { (d, rows) ->
                    linkedMapOf(
                        "date" to d.toString(),
                        "reviews" to rows.size,
                        "new_cards" to rows.count { it.introduced },
                        "again" to rows.count { it.rating == 1 },
                        "time_ms" to rows.sumOf { it.durationMs },
                    )
                }
            linkedMapOf("from" to r.from.toString(), "to" to r.to.toString(), "days" to days)
        } }
        call.respondJson(body)
    }

    private fun group(key: String, rows: List<ScheduledReview>): Map<String, Any?> {
        val young = rows.filter { it.intervalBefore < 21 }
        val mature = rows.filter { it.intervalBefore >= 21 }
        val passed = rows.count { it.passed }
        return linkedMapOf(
            "key" to key,
            "total" to rows.size,
            "passed" to passed,
            "retention" to if (rows.isEmpty()) null else passed.toDouble() / rows.size,
            "young_total" to young.size,
            "young_passed" to young.count { it.passed },
            "mature_total" to mature.size,
            "mature_passed" to mature.count { it.passed },
        )
    }

    private suspend fun retention(call: ApplicationCall) {
        val e = Errors()
        val nowMs = clock().toEpochMilli()
        val groupBy = call.request.queryParameters["group_by"] ?: "card_kind"
        if (groupBy !in setOf("card_kind", "day", "week", "month")) e.add("/group_by", "invalid_value", "Must be card_kind, day, week or month.")
        val (settings, r) = db.tx { dsl ->
            val settings = ReviewSettings.load(dsl)
            val r = range(call, todayOf(settings, nowMs).date, e)
            e.throwIfAny()
            settings to r!!
        }
        val rows = scheduledShared(r.from, r.to, settings.timezone, settings.dayRolloverHour)
        val body = run {
            val keyOf: (ScheduledReview) -> String = when (groupBy) {
                "day" -> { x -> x.day.toString() }
                "week" -> { x -> "%d-W%02d".format(x.day.get(IsoFields.WEEK_BASED_YEAR), x.day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)) }
                "month" -> { x -> "%04d-%02d".format(x.day.year, x.day.monthValue) }
                else -> { x -> x.groupKey }
            }
            linkedMapOf(
                "from" to r.from.toString(),
                "to" to r.to.toString(),
                "group_by" to groupBy,
                "overall" to group("all", rows),
                "groups" to rows.groupBy(keyOf).toSortedMap().map { (k, v) -> group(k, v) },
            )
        }
        call.respondJson(body)
    }

    private suspend fun forecast(call: ApplicationCall) {
        val e = Errors()
        val days = call.request.queryParameters["days"]?.let { v ->
            v.toIntOrNull()?.takeIf { it in 1..365 } ?: run { e.add("/days", "out_of_range", "Must be 1 to 365."); null }
        } ?: 30
        e.throwIfAny()
        val nowMs = clock().toEpochMilli()
        val body = db.tx { dsl ->
            val settings = ReviewSettings.load(dsl)
            val today = todayOf(settings, nowMs)
            val due = dsl.fetch(
                "select ${msOf("c.due_at")} from app.card c join app.note n on n.id = c.note_id " +
                    "where c.deleted_at is null and c.merged_into_id is null and n.deleted_at is null and not c.suspended and c.due_at is not null",
            ).map { (it.get(0) as Number).toLong() }
            val ends = (1..days).map { rolloverMs(settings.timezone, settings.dayRolloverHour, today.date.plusDays(it.toLong())) }
            val perDay = IntArray(days)
            val hour0 = nowMs - Math.floorMod(nowMs, HOUR_MS)
            val perHour = IntArray(24)
            for (d in due) {
                val i = ends.indexOfFirst { d < it }
                if (i >= 0) perDay[i]++
                val h = ((d - hour0).floorDiv(HOUR_MS)).coerceAtLeast(0)
                if (h < 24) perHour[h.toInt()]++
            }
            linkedMapOf(
                "days" to (0 until days).map { linkedMapOf("date" to today.date.plusDays(it.toLong()).toString(), "due" to perDay[it]) },
                "hours" to (0 until 24).map {
                    linkedMapOf("starts_at" to formatInstant(Instant.ofEpochMilli(hour0 + it * HOUR_MS)), "due" to perHour[it])
                },
            )
        }
        call.respondJson(body)
    }

    private suspend fun knownWords(call: ApplicationCall) {
        val e = Errors()
        val interval = call.request.queryParameters["interval"] ?: "week"
        if (interval !in setOf("day", "week", "month")) e.add("/interval", "invalid_value", "Must be day, week or month.")
        val nowMs = clock().toEpochMilli()
        val body = heavy.withPermit { db.tx { dsl ->
            val settings = ReviewSettings.load(dsl)
            val today = todayOf(settings, nowMs).date
            val r = range(call, today, e)
            e.throwIfAny()
            r!!
            val current = linkedMapOf("new" to 0, "learning" to 0, "known" to 0, "never_forget" to 0, "blacklisted" to 0, "ignored" to 0)
            for (row in dsl.fetch("select status, count(*) from app.word_status group by status")) {
                current[row.get(0, String::class.java)] = (row.get(1) as Number).toInt()
            }
            val dates = periodEnds(r.from, r.to, interval)
            val ends = dates.map { d ->
                // The last point is "now" when `to` is the current study day, so it equals `current`.
                if (d == today) nowMs else min(rolloverMs(settings.timezone, settings.dayRolloverHour, d.plusDays(1)) - 1, nowMs)
            }
            val counts = knownSeries(dsl, ends)
            linkedMapOf(
                "current" to current,
                "series" to dates.indices.map { linkedMapOf("date" to dates[it].toString(), "known" to counts[it]) },
            )
        } }
        call.respondJson(body)
    }

    /** Period end dates in `[from, to]` for [interval]; the last one is always [to]. */
    private fun periodEnds(from: LocalDate, to: LocalDate, interval: String): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = from
        while (d.isBefore(to)) {
            val end = when (interval) {
                "day" -> d
                "week" -> d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                else -> d.with(TemporalAdjusters.lastDayOfMonth())
            }
            if (end.isBefore(to)) out += end
            d = end.plusDays(1)
        }
        out += to
        return out
    }

    private suspend fun calibration(call: ApplicationCall) {
        val e = Errors()
        val nowMs = clock().toEpochMilli()
        val preset = call.request.queryParameters["preset_id"]?.also {
            if (!kotobaya.notes.UUID_RE.matches(it)) e.add("/preset_id", "invalid_value", "Must be a lowercase UUID.")
        }
        val (settings, r) = db.tx { dsl ->
            val settings = ReviewSettings.load(dsl)
            val r = range(call, todayOf(settings, nowMs).date, e)
            e.throwIfAny()
            settings to r!!
        }
        val rows = scheduledShared(r.from, r.to, settings.timezone, settings.dayRolloverHour)
            .filter { it.predicted != null && (preset == null || it.presetId == preset) }
        val body = run {
            val bins = rows.groupBy { min(BINS - 1, (it.predicted!! * BINS).toInt()) }.toSortedMap().map { (i, v) ->
                linkedMapOf(
                    "lower" to i.toDouble() / BINS,
                    "upper" to (i + 1).toDouble() / BINS,
                    "predicted" to v.sumOf { it.predicted!! } / v.size,
                    "actual" to v.count { it.passed }.toDouble() / v.size,
                    "count" to v.size,
                )
            }
            linkedMapOf(
                "from" to r.from.toString(),
                "to" to r.to.toString(),
                "preset_id" to preset,
                "review_count" to rows.size,
                "bins" to bins,
            )
        }
        call.respondJson(body)
    }
}
