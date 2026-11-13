package kotobaya.drill

import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.hot.HotCard
import kotobaya.contracts.hot.HotConfig
import kotobaya.contracts.replay.ReplayReview
import kotobaya.fsrs.FsrsScheduler
import kotobaya.review.ReviewViews
import kotobaya.struggle.DefaultHotScorer
import kotobaya.struggle.HotHistory
import org.jooq.DSLContext
import org.jooq.Record

private const val DAY_MS = 86_400_000L

/**
 * Loads the inputs of the core [DrillSelector] (T503): live cards, hot notes and confirmed confusable pairs.
 * Read-only. The hot-note scoring mirrors `listHotWords` (StruggleModule, T501): the core [DefaultHotScorer] over
 * [HotHistory]; kept here because that implementation is private to its package.
 */
internal class DrillSources(private val dsl: DSLContext, private val now: Long) {
    /** Live cards of live notes with `lapses >= threshold`. */
    fun leechCandidates(threshold: Int): List<DrillCandidate> =
        candidates("c.lapses >= ?", threshold)

    /** Live cards of the notes in [noteIds]. */
    fun candidatesOf(noteIds: Collection<String>): List<DrillCandidate> =
        if (noteIds.isEmpty()) emptyList() else candidates("c.note_id = any(?::uuid[])", noteIds.toTypedArray())

    private fun candidates(where: String, param: Any): List<DrillCandidate> = dsl.fetch(
        "select c.id::text, c.note_id::text, c.lapses from app.card c join app.note n on n.id = c.note_id " +
            "where c.deleted_at is null and c.merged_into_id is null and n.deleted_at is null and $where",
        param,
    ).map { DrillCandidate(it.get(0, String::class.java), it.get(1, String::class.java), (it.get(2) as Number).toInt()) }

    /** Confirmed pairs of live notes, one per pair even when confirmed for several reasons. */
    fun confirmedPairs(): List<NotePair> {
        val sql = "select distinct x.note_a_id::text, x.note_b_id::text from app.confusable x " +
            "join app.note a on a.id = x.note_a_id and a.deleted_at is null " +
            "join app.note b on b.id = x.note_b_id and b.deleted_at is null where x.status = 'confirmed'"
        return dsl.fetch(sql).map { NotePair(it.get(0, String::class.java), it.get(1, String::class.java)) }
    }

    /** Note id of the live note of dictionary entry [seq], if any. */
    fun noteOfEntry(seq: Long): List<String> =
        dsl.fetch("select id::text from app.note where entry_seq = ? and deleted_at is null", seq).map { it.get(0, String::class.java) }

    /** Hot vocab notes with their scores (the `listHotWords` rule), hottest first. */
    fun hotNotes(): List<ScoredNote> {
        val config = HotConfig.DEFAULT
        val since = now - config.windowDays * DAY_MS
        val cardRows = dsl.fetch(
            "select c.id::text, c.note_id::text, c.difficulty, c.reps, c.lapses, c.preset_id::text " +
                "from app.card c join app.note n on n.id = c.note_id " +
                "where n.type = 'vocab' and n.deleted_at is null and c.deleted_at is null and c.merged_into_id is null",
        )
        if (cardRows.isEmpty()) return emptyList()
        val cardsByNote = cardRows.groupBy { it.get(1, String::class.java) }
        val presetOf = cardRows.associate { it.get(0, String::class.java) to it.get(5, String::class.java) }
        val failed = dsl.fetch(
            "select distinct coalesce(c.merged_into_id, c.id)::text from app.review_log r join app.card c on c.id = r.card_id " +
                "where r.undone_at is null and r.review_type in ('scheduled', 'learning') and r.rating = 1 " +
                "and (extract(epoch from r.reviewed_at) * 1000)::bigint > ?",
            since,
        ).map { it.get(0, String::class.java) }.filter { it in presetOf }
        if (failed.isEmpty()) return emptyList()
        val presets = ReviewViews.loadPresets(dsl)
        val history = loadHistory(failed)
        val hotByCard = failed.associateWith { id ->
            val params = FsrsScheduler.paramsOf(ReviewViews.effectivePreset(presetOf.getValue(id), presets))
            HotHistory.hotReviews(history[id].orEmpty(), params)
        }
        return cardsByNote.mapNotNull { (noteId, rows) ->
            val reviews = rows.flatMap { hotByCard[it.get(0, String::class.java)].orEmpty() }
            if (reviews.isEmpty()) return@mapNotNull null
            val cards = rows.map {
                HotCard(it.get(0, String::class.java), (it.get(2) as Number?)?.toDouble(), (it.get(3) as Number).toInt(), (it.get(4) as Number).toInt())
            }
            val s = DefaultHotScorer.scoreNote(reviews, cards, now, config)
            if (!s.hot || s.score <= 0.0) null else ScoredNote(noteId, s.score)
        }.sortedWith(compareByDescending<ScoredNote> { it.score }.thenBy { it.noteId })
    }

    private fun loadHistory(survivors: List<String>): Map<String, List<ReplayReview>> {
        val out = HashMap<String, MutableList<ReplayReview>>()
        val rows = dsl.fetch(
            "select coalesce(c.merged_into_id, c.id)::text, r.id::text, r.card_id::text, r.hlc::text, " +
                "(extract(epoch from r.reviewed_at) * 1000)::bigint, r.utc_offset_min, r.review_type, r.rating, r.manual_action, " +
                "(extract(epoch from r.manual_due_at) * 1000)::bigint, r.undone_hlc::text " +
                "from app.review_log r join app.card c on c.id = r.card_id " +
                "where r.undone_at is null and coalesce(c.merged_into_id, c.id) = any(?::uuid[])",
            survivors.toTypedArray(),
        )
        for (r in rows) out.getOrPut(r.get(0, String::class.java)) { mutableListOf() } += replayReview(r)
        return out
    }

    private fun replayReview(r: Record): ReplayReview {
        val type = r.get(6, String::class.java)
        val action = r.get(8, String::class.java)
        return ReplayReview(
            id = r.get(1, String::class.java),
            cardId = r.get(0, String::class.java),
            hlc = r.get(3, String::class.java),
            reviewedAt = (r.get(4) as Number).toLong(),
            utcOffsetMin = (r.get(5) as Number?)?.toInt(),
            reviewType = ReviewType.entries.first { it.wire == type },
            rating = (r.get(7) as Number?)?.toInt()?.let { v -> Rating.entries.first { it.value == v } },
            manualAction = action?.let { a -> ManualAction.entries.first { it.wire == a } },
            manualDueAt = (r.get(9) as Number?)?.toLong(),
            undoneHlc = r.get(10, String::class.java),
        )
    }
}
