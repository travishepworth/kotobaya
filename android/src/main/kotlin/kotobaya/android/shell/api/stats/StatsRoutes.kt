package kotobaya.android.shell.api.stats

import app.cash.sqldelight.db.QueryResult
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.Locale
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.queue.EntryMaterializer
import kotobaya.android.queue.KanjiSource
import kotobaya.android.queue.LoadGate
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.queue.QueueSettings
import kotobaya.android.queue.studyDayOf
import kotobaya.android.review.CardRows
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.api.identity.obj
import kotobaya.android.shell.api.identity.timestamp
import kotobaya.android.shell.api.review.ShellEntryMaterializer
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayReview
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.db.app.AppDatabase
import kotobaya.fsrs.FsrsScheduler
import kotobaya.replay.DefaultReplayEngine
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

/** The default `limit` of `getReviewQueue`: the batch the server's summary evaluates the loop with. */
private const val SUMMARY_BATCH = 20

/** `young` / `mature` split of retention: interval before the review under 21 days, or 21 days or more. */
private const val MATURE_DAYS = 21

private val GROUP_BY = setOf("card_kind", "day", "week", "month")

/** One non-undone, non-manual review with its study day (the server's `DayRow`). */
private class DayRow(val day: LocalDate, val type: String, val rating: Int?, val durationMs: Long, val introduced: Boolean)

/** A scheduled review annotated by replay: passed, and the card's interval just before it (the server's `ScheduledReview`). */
private class ScheduledReview(val day: LocalDate, val passed: Boolean, val intervalBefore: Int, val groupKey: String)

private fun n(v: Int): JsonNumber = JsonNumber(v.toDouble())

private fun n(v: Long): JsonNumber = JsonNumber(v.toDouble())

private fun settingsOf(db: AppDatabase): QueueSettings =
    QueueSettings.from(db.localQueueQueries.schedulerSettings().executeAsList().associate { it.key to it.value_ })

/**
 * `getStatsSummary` (SHELL.md 4.3, LF-A3): the server's `StatsModule.summary` over the replica. `due` and
 * `more_available` are the legacy block of the same core `ReviewLoop` evaluation `getReviewQueue` makes (no deck,
 * `keep_going=false`, batch 20) — never counted separately — and the build does **not** materialize deck entries
 * (they stay drawn and counted in `due.new`). `done_today`, `totals`, `streak_days` and `next_due_at` from
 * `review_log`, `card` and `note`; the study day by the device clock in the user's zone (L-5). Read only.
 */
internal fun getStatsSummary(call: LocalCall): ShellResponse {
    call.query(emptySet())
    val services = call.services
    val store = services.get<SqlDelightDeviceStore>()
    val db = services.get<AppDatabase>()
    val now = services.get<ShellIdentity>().nowMs()
    val body = store.read { _ ->
        val settings = settingsOf(db)
        val day = studyDayOf(now, settings.timezone, settings.dayRolloverHour)
        val legacy = summaryLoader(call, db, now).load(deckFilter = null, keepGoing = false, keepGoingNewBatch = SUMMARY_BATCH).state.legacy
        val today = dayRows(db, day.date, day.date, settings.timezone, settings.dayRolloverHour)
        val scheduled = today.filter { it.type == ReviewType.SCHEDULED.wire && it.rating != null }
        val totals = db.statsQueries.statsTotals(settings.leechThreshold.toLong()).executeAsOne()
        val next = db.statsQueries.statsNextDue(now, if (settings.readingEnabled) 1L else 0L).executeAsOne().next_due
        obj(
            "study_day" to JsonString(day.date.toString()),
            "day_ends_at" to JsonString(timestamp(day.day.endMs)),
            "due" to obj("new" to n(legacy.new), "learning" to n(legacy.learning), "review" to n(legacy.review)),
            "done_today" to obj(
                "reviews" to n(today.size),
                "new_cards" to n(today.count { it.introduced }),
                "time_ms" to n(today.sumOf { it.durationMs }),
                "retention" to if (scheduled.isEmpty()) JsonNull else JsonNumber(scheduled.count { (it.rating ?: 0) > 1 }.toDouble() / scheduled.size),
            ),
            "totals" to obj(
                "notes" to n(totals.notes),
                "cards" to n(totals.cards),
                "suspended" to n(totals.suspended),
                "leeches" to n(totals.leeches),
            ),
            "streak_days" to n(streak(db, day.date, settings.timezone, settings.dayRolloverHour)),
            "next_due_at" to (next?.let { JsonString(timestamp(it)) } ?: JsonNull),
            "more_available" to JsonBool(legacy.moreAvailable),
        )
    }
    return ShellResponse.json(body)
}

/**
 * The queue loader exactly as `getReviewQueue` wires it (same dictionary, kanji source and materializer planning, so
 * the loop sees the same input), except that its materializer never creates anything.
 */
private fun summaryLoader(call: LocalCall, db: AppDatabase, now: Long): LocalQueueLoader {
    val services = call.services
    val dict = services.get<InstalledDictionary>()
    val kanji = runCatching { services.get<KanjiSource>() }.getOrNull() ?: KanjiSource(dict)
    val headwords = kotobaya.android.shell.api.review.shellHeadwords(services, dict)
    return LocalQueueLoader(
        db = db,
        clock = { now },
        headwordOf = headwords::headword,
        prefetchHeadwords = headwords::prefetch,
        gate = LoadGate(),
        materializer = ShellEntryMaterializer(services.get<EntryMaterializer>()) { false },
        kanji = kanji,
        cache = runCatching { services.get<kotobaya.android.queue.QueueReadCache>() }.getOrNull(),
    )
}

/** Consecutive study days with a review, ending today or yesterday; reads newest first and stops at the first gap. */
private fun streak(db: AppDatabase, today: LocalDate, zone: ZoneId, hour: Int): Int {
    val query = db.statsQueries.statsStreakRows()
    return query.execute { cursor ->
        var last: LocalDate? = null
        var count = 0
        while (cursor.next().value) {
            val r = query.mapper(cursor)
            val d = studyDayOfReview(r.reviewed_at, r.utc_offset_min?.toInt(), zone, hour)
            if (d.isAfter(today)) continue
            val l = last
            if (l == null) {
                if (d.isBefore(today.minusDays(1))) break
                last = d
                count = 1
            } else if (!d.isBefore(l)) {
                continue
            } else if (d == l.minusDays(1)) {
                last = d
                count++
            } else {
                break
            }
        }
        QueryResult.Value(count)
    }.value
}

/** Non-undone, non-manual reviews with study day in `[from, to]` (the server's `dayRows`). */
private fun dayRows(db: AppDatabase, from: LocalDate, to: LocalDate, zone: ZoneId, hour: Int): List<DayRow> {
    val (lo, hi) = windowMs(from, to)
    return db.statsQueries.statsDayRows(lo, hi).executeAsList().mapNotNull { r ->
        val day = studyDayOfReview(r.reviewed_at, r.utc_offset_min?.toInt(), zone, hour)
        if (day < from || day > to) null else DayRow(day, r.review_type, r.rating?.toInt(), r.duration_ms, r.introduced == true)
    }
}

/**
 * `getStatsHeatmap` (SHELL.md 4.3): per study day in `[from, to]` with at least one review, ascending; undone and
 * `manual` rows excluded, drill and cram rows counted (never as new cards). Reviews minted here and not yet pushed are
 * included. Read only.
 */
internal fun getStatsHeatmap(call: LocalCall): ShellResponse {
    val q = call.query("from", "to")
    val services = call.services
    val db = services.get<AppDatabase>()
    val now = services.get<ShellIdentity>().nowMs()
    val body = services.get<SqlDelightDeviceStore>().read { _ ->
        val settings = settingsOf(db)
        val e = FieldErrors()
        val range = dayRange(q, studyDayOf(now, settings.timezone, settings.dayRolloverHour).date, e)
        e.throwIfAny()
        val r = checkNotNull(range)
        val days = dayRows(db, r.from, r.to, settings.timezone, settings.dayRolloverHour)
            .groupBy { it.day }.toSortedMap().map { (d, rows) ->
                obj(
                    "date" to JsonString(d.toString()),
                    "reviews" to n(rows.size),
                    "new_cards" to n(rows.count { it.introduced }),
                    "again" to n(rows.count { it.rating == 1 }),
                    "time_ms" to n(rows.sumOf { it.durationMs }),
                )
            }
        obj("from" to JsonString(r.from.toString()), "to" to JsonString(r.to.toString()), "days" to JsonArray(days))
    }
    return ShellResponse.json(body)
}

/**
 * `getStatsRetention` (SHELL.md 4.3): over non-undone `scheduled` reviews whose study day is in `[from, to]`, each
 * with the interval its card had just before it (a replay of the card's whole history, as the server). Read only.
 */
internal fun getStatsRetention(call: LocalCall): ShellResponse {
    val q = call.query("from", "to", "group_by")
    val services = call.services
    val db = services.get<AppDatabase>()
    val now = services.get<ShellIdentity>().nowMs()
    val e = FieldErrors()
    val groupBy = q.first("group_by") ?: "card_kind"
    if (groupBy !in GROUP_BY) e.add("/group_by", "invalid_value", "Must be card_kind, day, week or month.")
    val body = services.get<SqlDelightDeviceStore>().read { tx ->
        val settings = settingsOf(db)
        val range = dayRange(q, studyDayOf(now, settings.timezone, settings.dayRolloverHour).date, e)
        e.throwIfAny()
        val r = checkNotNull(range)
        val rows = scheduledReviews(db, tx.learning, r, settings.timezone, settings.dayRolloverHour)
        val keyOf: (ScheduledReview) -> String = when (groupBy) {
            "day" -> { x -> x.day.toString() }
            "week" -> { x -> String.format(Locale.ROOT, "%d-W%02d", x.day.get(IsoFields.WEEK_BASED_YEAR), x.day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)) }
            "month" -> { x -> String.format(Locale.ROOT, "%04d-%02d", x.day.year, x.day.monthValue) }
            else -> { x -> x.groupKey }
        }
        obj(
            "from" to JsonString(r.from.toString()),
            "to" to JsonString(r.to.toString()),
            "group_by" to JsonString(groupBy),
            "overall" to group("all", rows),
            "groups" to JsonArray(rows.groupBy(keyOf).toSortedMap().map { (k, v) -> group(k, v) }),
        )
    }
    return ShellResponse.json(body)
}

private fun group(key: String, rows: List<ScheduledReview>): JsonObject {
    val young = rows.filter { it.intervalBefore < MATURE_DAYS }
    val mature = rows.filter { it.intervalBefore >= MATURE_DAYS }
    val passed = rows.count { it.passed }
    return obj(
        "key" to JsonString(key),
        "total" to n(rows.size),
        "passed" to n(passed),
        "retention" to if (rows.isEmpty()) JsonNull else JsonNumber(passed.toDouble() / rows.size),
        "young_total" to n(young.size),
        "young_passed" to n(young.count { it.passed }),
        "mature_total" to n(mature.size),
        "mature_passed" to n(mature.count { it.passed }),
    )
}

/**
 * Scheduled non-undone reviews with study day in [range], each with its card's schedule just before it: the replay set
 * of the card's root (the card and every card merged into it) folded in order with the root's effective preset. A card
 * whose replay is refused (a malformed row) is skipped, as on the server.
 *
 * PERF-LF: three set reads (roots, every card's kind / preset / note type, every non-undone row with its root) instead
 * of a card, note, preset and history read per root (tens of thousands of statements for a year of reviews).
 */
private fun scheduledReviews(db: AppDatabase, t: LearningTables, range: DayRange, zone: ZoneId, hour: Int): List<ScheduledReview> {
    val (lo, hi) = windowMs(range.from, range.to)
    val roots = db.statsQueries.statsScheduledRoots(lo, hi).executeAsList()
    if (roots.isEmpty()) return emptyList()
    val wanted = roots.toHashSet()
    val info = HashMap<String, kotobaya.db.app.StatsCardInfo>()
    for (c in db.statsQueries.statsCardInfo().executeAsList()) if (c.id in wanted) info[c.id] = c
    val histories = HashMap<String, MutableList<ReplayReview>>()
    for (r in db.statsQueries.statsReplayRows().executeAsList()) {
        if (r.root !in wanted) continue
        val rv = replayReview(r) ?: continue
        histories.getOrPut(r.root) { ArrayList() } += rv
    }
    val params = HashMap<String, kotobaya.contracts.fsrs.SchedulerParams>()
    val out = ArrayList<ScheduledReview>()
    for (root in roots) {
        val card = info[root] ?: continue
        val groupKey = "${card.type}/${card.kind}"
        val p = params.getOrPut(card.preset_id) { FsrsScheduler.paramsOf(CardRows.presetOf(t, mapOf("preset_id" to JsonString(card.preset_id)))) }
        val found = ArrayList<ScheduledReview>()
        try {
            var cache = CardCache(CardSchedule.NEW, null, p.weightsVersion)
            for (rv in DefaultReplayEngine.replaySet(histories[root].orEmpty())) {
                val rating = rv.rating
                if (rv.reviewType == ReviewType.SCHEDULED && rating != null) {
                    val day = studyDayOfReview(rv.reviewedAt, rv.utcOffsetMin, zone, hour)
                    if (day >= range.from && day <= range.to) {
                        found += ScheduledReview(day, rating != Rating.AGAIN, cache.schedule.intervalDays ?: 0, groupKey)
                    }
                }
                cache = DefaultReplayEngine.fold(cache, rv, p)
            }
        } catch (e: IllegalArgumentException) {
            continue
        }
        out += found
    }
    return out
}


private fun replayReview(r: kotobaya.db.app.StatsReplayRows): ReplayReview? {
    val type = ReviewType.entries.firstOrNull { it.wire == r.review_type } ?: return null
    val rating = r.rating?.toInt()
    return ReplayReview(
        id = r.id,
        cardId = r.root,
        hlc = r.hlc,
        reviewedAt = r.reviewed_at,
        utcOffsetMin = r.utc_offset_min?.toInt(),
        reviewType = type,
        rating = Rating.entries.firstOrNull { it.value == rating },
        manualAction = ManualAction.entries.firstOrNull { it.wire == r.manual_action },
        manualDueAt = r.manual_due_at,
        undoneHlc = null,
        importSource = r.import_source,
    )
}
