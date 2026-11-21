package kotobaya.android.shell.api.stats

import kotobaya.android.review.CardRows
import kotobaya.android.sync.ReplicaVersions
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayReview
import kotobaya.contracts.sync.JsonString
import kotobaya.db.app.AppDatabase
import kotobaya.fsrs.FsrsScheduler
import kotobaya.replay.DefaultReplayEngine
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables

/** One non-undone `review_log` row with its replay root (`statsReplayRows`). */
internal class HistoryRow(
    val root: String,
    val id: String,
    val hlc: String,
    val reviewedAt: Long,
    val utcOffsetMin: Int?,
    val type: String,
    val rating: Int?,
    val manualAction: String?,
    val manualDueAt: Long?,
    val importSource: String?,
    val durationMs: Long,
) {
    companion object {
        @Suppress("LongParameterList")
        fun of(
            root: String, id: String, hlc: String, reviewedAt: Long, utcOffsetMin: Long?, type: String, rating: Long?, manualAction: String?,
            manualDueAt: Long?, importSource: String?, durationMs: Long,
        ): HistoryRow = HistoryRow(root, id, hlc, reviewedAt, utcOffsetMin?.toInt(), type, rating?.toInt(), manualAction, manualDueAt, importSource, durationMs)
    }
}

/** A non-manual review of a root as the day stats count it (the server's `DayRow` before its study day is known). */
internal class DayReview(val reviewedAt: Long, val utcOffsetMin: Int?, val type: String, val rating: Int?, val durationMs: Long, val introduced: Boolean)

/** A rated scheduled review of a root in replay order: passed, and the root's interval just before it. */
internal class ReplayedReview(val reviewedAt: Long, val utcOffsetMin: Int?, val passed: Boolean, val intervalBefore: Int)

/** What the stats read of one replay root. [scheduled] is empty when its replay is refused or its card is gone. */
internal class RootHistory(val days: List<DayReview>, val scheduled: List<ReplayedReview>)

/** The per-root histories at one moment; [newestFirst] (the streak's input) is built on first use. */
internal class StatsSnapshot(val roots: Map<String, RootHistory>) {
    val newestFirst: List<DayReview> by lazy { roots.values.flatMap { it.days }.sortedByDescending { it.reviewedAt } }
}

/**
 * PERF-LF: the replica's review history per replay root, derived once and then kept current: the three local stats
 * (`getStatsSummary`'s done today and streak, `getStatsHeatmap`, `getStatsRetention`) used to read and replay a year of
 * `review_log` on every call (seconds on a phone). After a write only the roots whose rows or cards it touched are
 * read and replayed again ([ReplicaVersions] keys of `review_log` and `card`); an untracked write, a preset change or a
 * big batch rebuilds everything. Every derived value is what the per-call SQL computed:
 * - `introduced` (scheduled / learning rows): no earlier schedule-feeding row (scheduled, learning, manual) of the root
 *   in replay order (reviewed_at, hlc, id), or the earlier one is a manual reset (Stats.sq statsDayRows);
 * - the interval before each rated scheduled review: the root's replay set folded with its own card's preset; a refused
 *   replay (IllegalArgumentException) gives no reviews, as the server skips the card.
 * With [versions] null (no store) every call rebuilds.
 */
class StatsHistoryCache(private val versions: ReplicaVersions?) {
    private class State(val reviewVersion: Long, val cardVersion: Long, val presetVersion: Long, val snapshot: StatsSnapshot)

    private var state: State? = null

    /** Full rebuilds and partial refreshes so far (tests). */
    @Volatile var fullLoads: Int = 0
        private set

    @Volatile var partialLoads: Int = 0
        private set

    @Synchronized
    internal fun snapshot(db: AppDatabase, t: LearningTables): StatsSnapshot {
        val v = versions ?: return StatsSnapshot(build(db, t, db.statsQueries.statsReplayRows(HistoryRow::of).executeAsList(), null))
        val reviewVersion = v.tableVersion(LearningTable.REVIEW_LOG)
        val cardVersion = v.tableVersion(LearningTable.CARD)
        val presetVersion = v.version(LearningTable.FSRS_PARAMS)
        val s = state
        if (s != null && s.presetVersion == presetVersion) {
            if (s.reviewVersion == reviewVersion && s.cardVersion == cardVersion) return s.snapshot
            val reviews = v.changedKeys(LearningTable.REVIEW_LOG, s.reviewVersion)
            val cards = v.changedKeys(LearningTable.CARD, s.cardVersion)
            if (reviews != null && cards != null && reviews.size + cards.size <= MAX_PARTIAL) {
                val q = db.statsQueries
                val roots = HashSet<String>()
                reviews.chunked(CHUNK).forEach { roots += q.statsRootsOfReviews(it).executeAsList() }
                cards.chunked(CHUNK).forEach { ids ->
                    for (c in q.statsCardMergeOf(ids).executeAsList()) {
                        roots += c.id
                        c.merged_into_id?.let(roots::add)
                    }
                }
                roots += cards // a card that vanished still names its old root
                val rows = roots.toList().chunked(CHUNK / 2).flatMap { ids -> q.statsReplayRowsOf(ids, ids, HistoryRow::of).executeAsList() }
                    .filter { it.root in roots }
                val fresh = build(db, t, rows, roots)
                val map = HashMap(s.snapshot.roots)
                for (r in roots) {
                    val h = fresh[r]
                    if (h == null) map.remove(r) else map[r] = h
                }
                val snap = StatsSnapshot(map)
                state = State(reviewVersion, cardVersion, presetVersion, snap)
                partialLoads++
                return snap
            }
        }
        val snap = StatsSnapshot(build(db, t, db.statsQueries.statsReplayRows(HistoryRow::of).executeAsList(), null))
        state = State(reviewVersion, cardVersion, presetVersion, snap)
        fullLoads++
        return snap
    }

    /** The histories of the roots in [rows] ([only]: the root set they were read for; null = every card's info). */
    private fun build(db: AppDatabase, t: LearningTables, rows: List<HistoryRow>, only: Set<String>?): Map<String, RootHistory> {
        val q = db.statsQueries
        val presetOf = HashMap<String, String>()
        if (only == null) {
            for (c in q.statsCardInfo().executeAsList()) presetOf[c.id] = c.preset_id
        } else {
            only.toList().chunked(CHUNK).forEach { ids -> for (c in q.statsCardInfoOf(ids).executeAsList()) presetOf[c.id] = c.preset_id }
        }
        val params = HashMap<String, SchedulerParams>()
        val out = HashMap<String, RootHistory>()
        for ((root, list) in rows.groupBy { it.root }) {
            val p = presetOf[root]?.let { id -> params.getOrPut(id) { FsrsScheduler.paramsOf(CardRows.presetOf(t, mapOf("preset_id" to JsonString(id)))) } }
            out[root] = history(list, p)
        }
        return out
    }

    private fun history(rows: List<HistoryRow>, params: SchedulerParams?): RootHistory {
        val sorted = rows.sortedWith(ORDER)
        val days = ArrayList<DayReview>(sorted.size)
        var fed = false
        var lastAction: String? = null
        for (r in sorted) {
            if (r.type != MANUAL) {
                val introduced = (r.type == SCHEDULED || r.type == LEARNING) && (!fed || lastAction == RESET)
                days += DayReview(r.reviewedAt, r.utcOffsetMin, r.type, r.rating, r.durationMs, introduced)
            }
            if (r.type == SCHEDULED || r.type == LEARNING || r.type == MANUAL) {
                fed = true
                lastAction = r.manualAction ?: ""
            }
        }
        return RootHistory(days, if (params == null) emptyList() else replayed(sorted, params))
    }

    private fun replayed(rows: List<HistoryRow>, params: SchedulerParams): List<ReplayedReview> {
        val reviews = rows.mapNotNull(::replayReview)
        val out = ArrayList<ReplayedReview>()
        return try {
            var cache = CardCache(CardSchedule.NEW, null, params.weightsVersion)
            for (rv in DefaultReplayEngine.replaySet(reviews)) {
                val rating = rv.rating
                if (rv.reviewType == ReviewType.SCHEDULED && rating != null) {
                    out += ReplayedReview(rv.reviewedAt, rv.utcOffsetMin, rating != Rating.AGAIN, cache.schedule.intervalDays ?: 0)
                }
                cache = DefaultReplayEngine.fold(cache, rv, params)
            }
            out
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun replayReview(r: HistoryRow): ReplayReview? {
        val type = ReviewType.entries.firstOrNull { it.wire == r.type } ?: return null
        return ReplayReview(
            id = r.id,
            cardId = r.root,
            hlc = r.hlc,
            reviewedAt = r.reviewedAt,
            utcOffsetMin = r.utcOffsetMin,
            reviewType = type,
            rating = Rating.entries.firstOrNull { it.value == r.rating },
            manualAction = ManualAction.entries.firstOrNull { it.wire == r.manualAction },
            manualDueAt = r.manualDueAt,
            undoneHlc = null,
            importSource = r.importSource,
        )
    }

    private companion object {
        const val MAX_PARTIAL = 2_000
        const val CHUNK = 500
        const val MANUAL = "manual"
        const val SCHEDULED = "scheduled"
        const val LEARNING = "learning"
        const val RESET = "reset"

        /** Replay order (SYNC.md 9.1): (reviewed_at, hlc, id); SQLite compares the ASCII texts bytewise, as here. */
        val ORDER: Comparator<HistoryRow> = compareBy<HistoryRow>({ it.reviewedAt }, { it.hlc }, { it.id })
    }
}
