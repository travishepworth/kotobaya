package kotobaya.stats

import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.PresetRow
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.queue.WordCardState
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayReview
import kotobaya.fsrs.FsrsScheduler
import kotobaya.queue.DefaultWordStatusRules
import kotobaya.replay.DefaultReplayEngine
import kotobaya.review.ReviewViews
import org.jooq.DSLContext
import org.jooq.Record
import java.time.LocalDate
import java.time.ZoneId

/**
 * The stats queries exactly as they were before BUG-1 (0.1.4 StatsQueries.kt, wrapped in an object): the oracle for the
 * equivalence tests. Quadratic; only run it on small data.
 */
internal object LegacyStatsQueries {
/** Non-undone, non-manual reviews with study day in `[from, to]`. `introduced`: the card's first schedule-feeding row (or first after a reset). */
internal fun dayRows(dsl: DSLContext, from: LocalDate, to: LocalDate, zone: ZoneId, hour: Int): List<DayRow> {
    val (lo, hi) = windowMs(from, to)
    return dsl.fetch(
        "select ${msOf("r.reviewed_at")}, r.utc_offset_min, r.review_type, r.rating, coalesce(r.duration_ms, 0), " +
            "(r.review_type in ('scheduled', 'learning') and (prev.id is null or prev.manual_action = 'reset')) " +
            "from app.review_log r join app.card c on c.id = r.card_id " +
            "left join lateral (select p.id, p.manual_action from app.review_log p join app.card pc on pc.id = p.card_id " +
            "  where coalesce(pc.merged_into_id, pc.id) = coalesce(c.merged_into_id, c.id) and p.undone_at is null " +
            "  and p.review_type in ('scheduled', 'learning', 'manual') and (p.reviewed_at, p.hlc, p.id) < (r.reviewed_at, r.hlc, r.id) " +
            "  order by p.reviewed_at desc, p.hlc desc, p.id desc limit 1) prev on true " +
            "where r.undone_at is null and r.review_type <> 'manual' " +
            "and r.reviewed_at >= ?::timestamptz and r.reviewed_at < ?::timestamptz",
        java.sql.Timestamp(lo), java.sql.Timestamp(hi),
    ).mapNotNull { r ->
        val day = studyDayOfReview((r.get(0) as Number).toLong(), (r.get(1) as Number?)?.toInt(), zone, hour)
        if (day < from || day > to) null
        else DayRow(day, r.get(2, String::class.java), (r.get(3) as Number?)?.toInt(), (r.get(4) as Number).toLong(), r.get(5, Boolean::class.java))
    }
}

private fun replayReview(r: Record, survivorId: String): ReplayReview {
    val type = r.get(6, String::class.java)
    val action = r.get(8, String::class.java)
    return ReplayReview(
        id = r.get(1, String::class.java),
        cardId = survivorId,
        hlc = r.get(3, String::class.java),
        reviewedAt = (r.get(4) as Number).toLong(),
        utcOffsetMin = (r.get(5) as Number?)?.toInt(),
        reviewType = ReviewType.entries.first { it.wire == type },
        rating = (r.get(7) as Number?)?.toInt()?.let { v -> Rating.entries.first { it.value == v } },
        manualAction = action?.let { a -> ManualAction.entries.first { it.wire == a } },
        manualDueAt = (r.get(9) as Number?)?.toLong(),
        undoneHlc = null,
    )
}

/** Every non-undone `review_log` row of each survivor card (and its merge losers), by survivor id. */
private fun loadHistory(dsl: DSLContext, survivors: Collection<String>): Map<String, List<ReplayReview>> {
    val out = HashMap<String, MutableList<ReplayReview>>()
    for (chunk in survivors.chunked(2000)) {
        val rows = dsl.fetch(
            "select coalesce(c.merged_into_id, c.id)::text, r.id::text, r.card_id::text, r.hlc::text, " +
                "${msOf("r.reviewed_at")}, r.utc_offset_min, r.review_type, r.rating, r.manual_action, " +
                "${msOf("r.manual_due_at")} " +
                "from app.review_log r join app.card c on c.id = r.card_id " +
                "where r.undone_at is null and coalesce(c.merged_into_id, c.id) = any(?::uuid[])",
            chunk.toTypedArray(),
        )
        for (r in rows) {
            val sid = r.get(0, String::class.java)
            out.getOrPut(sid) { mutableListOf() } += replayReview(r, sid)
        }
    }
    return out
}

private class CardInfo(val presetId: String, val groupKey: String)

private fun paramsFor(presetId: String, presets: Map<String, PresetRow>): SchedulerParams =
    FsrsScheduler.paramsOf(ReviewViews.effectivePreset(presetId, presets))

/**
 * Scheduled non-undone reviews with study day in `[from, to]`, each with the card's schedule just before it (replay of the
 * whole history of the card). Cards whose replay throws (a malformed row) are skipped.
 */
internal fun scheduledReviews(dsl: DSLContext, from: LocalDate, to: LocalDate, zone: ZoneId, hour: Int, presetFilter: String?): List<ScheduledReview> {
    val (lo, hi) = windowMs(from, to)
    val survivors = dsl.fetch(
        "select distinct coalesce(c.merged_into_id, c.id)::text from app.review_log r join app.card c on c.id = r.card_id " +
            "where r.undone_at is null and r.review_type = 'scheduled' and r.reviewed_at >= ?::timestamptz and r.reviewed_at < ?::timestamptz",
        java.sql.Timestamp(lo), java.sql.Timestamp(hi),
    ).map { it.get(0, String::class.java) }
    if (survivors.isEmpty()) return emptyList()
    val info = HashMap<String, CardInfo>()
    for (chunk in survivors.chunked(2000)) {
        for (r in dsl.fetch(
            "select c.id::text, c.preset_id::text, n.type, c.kind from app.card c join app.note n on n.id = c.note_id where c.id = any(?::uuid[])",
            chunk.toTypedArray(),
        )) info[r.get(0, String::class.java)] = CardInfo(r.get(1, String::class.java), "${r.get(2, String::class.java)}/${r.get(3, String::class.java)}")
    }
    val presets = ReviewViews.loadPresets(dsl)
    val history = loadHistory(dsl, survivors)
    val out = ArrayList<ScheduledReview>()
    for (id in survivors) {
        val ci = info[id] ?: continue
        if (presetFilter != null && ci.presetId != presetFilter) continue
        val params = paramsFor(ci.presetId, presets)
        val found = ArrayList<ScheduledReview>()
        try {
            var cache = CardCache(CardSchedule.NEW, null, params.weightsVersion)
            for (r in DefaultReplayEngine.replaySet(history[id].orEmpty())) {
                val rating = r.rating
                if (r.reviewType == ReviewType.SCHEDULED && rating != null) {
                    val day = studyDayOfReview(r.reviewedAt, r.utcOffsetMin, zone, hour)
                    if (day >= from && day <= to) {
                        found += ScheduledReview(
                            day, rating != Rating.AGAIN, cache.schedule.intervalDays ?: 0, ci.groupKey,
                            FsrsScheduler.retrievability(cache.schedule, r.reviewedAt, params),
                        )
                    }
                }
                cache = DefaultReplayEngine.fold(cache, r, params)
            }
        } catch (e: IllegalArgumentException) {
            continue
        }
        out += found
    }
    return out
}

/** Number of known-or-never-forget entries at each instant in [ends] (epoch ms), reconstructed from the review log and override times. */
internal fun knownSeries(dsl: DSLContext, ends: List<Long>): List<Int> {
    if (ends.isEmpty()) return emptyList()
    class Snap(val at: Long, val state: CardState, val interval: Int?)
    // Live vocab notes: lowest id per entry (as the auto-status hook picks it), with their live cards.
    val noteOfEntry = HashMap<Long, String>()
    for (r in dsl.fetch(
        "select n.entry_seq, n.id::text from app.note n join app.note_type t on t.id = n.note_type_id " +
            "where n.entry_seq is not null and t.base_type = 'vocab' and n.deleted_at is null and n.merged_into_id is null order by n.id::text desc",
    )) noteOfEntry[(r.get(0) as Number).toLong()] = r.get(1, String::class.java)
    val cardsOfNote = HashMap<String, MutableList<Pair<String, String>>>()
    for (r in dsl.fetch(
        "select c.note_id::text, c.id::text, c.preset_id::text from app.card c where c.deleted_at is null and c.merged_into_id is null " +
            "and c.note_id = any(?::uuid[])",
        noteOfEntry.values.toTypedArray(),
    )) cardsOfNote.getOrPut(r.get(0, String::class.java)) { mutableListOf() } += r.get(1, String::class.java) to r.get(2, String::class.java)
    val presets = ReviewViews.loadPresets(dsl)
    val allCards = cardsOfNote.values.flatten()
    val history = loadHistory(dsl, allCards.map { it.first })
    val snaps = HashMap<String, List<Snap>>()
    for ((id, preset) in allCards) {
        val params = paramsFor(preset, presets)
        val list = ArrayList<Snap>()
        try {
            var cache = CardCache(CardSchedule.NEW, null, params.weightsVersion)
            for (r in DefaultReplayEngine.replaySet(history[id].orEmpty())) {
                cache = DefaultReplayEngine.fold(cache, r, params)
                list += Snap(r.reviewedAt, cache.schedule.state, cache.schedule.intervalDays)
            }
        } catch (e: IllegalArgumentException) {
            list.clear()
        }
        snaps[id] = list
    }
    val overrides = dsl.fetch("select entry_seq, override_status, ${msOf("updated_at")} from app.word_status")
        .associate { (it.get(0) as Number).toLong() to (it.get(1, String::class.java) to (it.get(2) as Number).toLong()) }
    val entries = (noteOfEntry.keys + overrides.keys).toSortedSet()
    return ends.map { end ->
        entries.count { seq ->
            val ov = overrides[seq]?.takeIf { it.second <= end }?.first
            when (ov) {
                "known", "never_forget" -> true
                "blacklisted", "ignored" -> false
                else -> {
                    val noteId = noteOfEntry[seq]
                    if (noteId == null) false else {
                        val states = cardsOfNote[noteId].orEmpty().map { (cid, _) ->
                            val s = snaps[cid].orEmpty().lastOrNull { it.at <= end }
                            WordCardState(s?.state ?: CardState.NEW, s?.interval)
                        }
                        DefaultWordStatusRules.autoStatus(true, states) == WordStatus.KNOWN
                    }
                }
            }
        }
    }
}
}
