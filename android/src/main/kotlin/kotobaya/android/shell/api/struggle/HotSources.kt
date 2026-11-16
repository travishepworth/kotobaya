package kotobaya.android.shell.api.struggle

import kotobaya.android.review.CardRows
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.hot.HotCard
import kotobaya.contracts.hot.HotConfig
import kotobaya.contracts.hot.HotReview
import kotobaya.contracts.replay.ReplayReview
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.fsrs.FsrsScheduler
import kotobaya.struggle.DefaultHotScorer
import kotobaya.struggle.HotHistory
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.MergeReaders
import kotobaya.sync.client.apply.Row

private const val DAY_MS = 86_400_000L

/** A hot vocab note: its score and its Again count in the window. */
internal class ScoredHot(val noteId: String, val score: Double, val failures: Int)

private fun isNull(v: JsonValue?): Boolean = v == null || v == JsonNull

private fun long(v: JsonValue?): Long? = (v as? JsonNumber)?.value?.toLong()

/**
 * The hot vocab notes of the replica, exactly the server's `HotWords.scoredNotes` rule over the replica instead of
 * Postgres: the core [DefaultHotScorer] over [HotHistory] (replay gives each failure's state). Only cards with a
 * non-undone Again in the window (scheduled or learning review) can make a note hot, so only their history is
 * replayed; the note's reviews are those of those cards, its [HotCard]s all its live cards. Read-only.
 */
internal class HotSources(private val t: LearningTables, private val now: Long) {
    private val config = HotConfig.DEFAULT
    private val since = now - config.windowDays * DAY_MS

    /** Every hot live vocab note, hottest first (ties by note id). */
    fun scoredNotes(): List<ScoredHot> {
        val failedCardIds = t.where(LearningTable.REVIEW_LOG, "rating", JsonNumber(1.0))
            .filter {
                isNull(it["undone_at"]) && CardRows.str(it["review_type"]) in FAILING_TYPES && (long(it["reviewed_at"]) ?: Long.MIN_VALUE) > since
            }
            .mapNotNull { CardRows.str(it["card_id"]) }.distinct()

        // survivors of the failed cards that are live cards of live vocab notes, by note
        val failedByNote = LinkedHashMap<String, MutableMap<String, Row>>()
        for (id in failedCardIds) {
            val card = MergeReaders.resolveCard(t, id) ?: continue
            if (!CardRows.live(card)) continue
            val cardId = CardRows.str(card["id"]) ?: continue
            val noteId = CardRows.str(card["note_id"]) ?: continue
            val note = CardRows.get(t, LearningTable.NOTE, noteId) ?: continue
            if (!isNull(note["deleted_at"]) || CardRows.str(note["type"]) != "vocab") continue
            failedByNote.getOrPut(noteId) { LinkedHashMap() }[cardId] = card
        }

        val scored = ArrayList<ScoredHot>()
        for ((noteId, failed) in failedByNote) {
            val reviews = failed.flatMap { (cardId, card) ->
                HotHistory.hotReviews(history(cardId), FsrsScheduler.paramsOf(CardRows.presetOf(t, card)))
            }
            if (reviews.isEmpty()) continue
            val cards = liveCards(noteId).map {
                HotCard(
                    CardRows.str(it["id"]).orEmpty(),
                    (it["difficulty"] as? JsonNumber)?.value,
                    long(it["reps"])?.toInt() ?: 0,
                    long(it["lapses"])?.toInt() ?: 0,
                )
            }
            val s = DefaultHotScorer.scoreNote(reviews, cards, now, config)
            if (!s.hot || s.score <= 0.0) continue
            scored += ScoredHot(noteId, s.score, failuresIn(reviews))
        }
        return scored.sortedWith(compareByDescending<ScoredHot> { it.score }.thenBy { it.noteId })
    }

    /** Live cards of [noteId] (not deleted, not merged away). */
    fun liveCards(noteId: String): List<Row> =
        t.where(LearningTable.CARD, "note_id", JsonString(noteId)).filter(CardRows::live)

    private fun failuresIn(reviews: List<HotReview>): Int =
        reviews.count { it.rating == Rating.AGAIN && it.reviewedAt > since && it.reviewedAt <= now }

    /** Every non-undone `review_log` row of [survivor] and of the cards merged into it, as the survivor's. */
    private fun history(survivor: String): List<ReplayReview> {
        val ids = listOf(survivor) + t.where(LearningTable.CARD, "merged_into_id", JsonString(survivor)).mapNotNull { CardRows.str(it["id"]) }
        return ids.flatMap { id -> t.where(LearningTable.REVIEW_LOG, "card_id", JsonString(id)) }
            .filter { isNull(it["undone_at"]) }
            .mapNotNull { replay(it, survivor) }
    }

    private fun replay(row: Row, survivor: String): ReplayReview? {
        val type = ReviewType.entries.firstOrNull { it.wire == CardRows.str(row["review_type"]) } ?: return null
        val rating = long(row["rating"])?.toInt()
        return ReplayReview(
            id = CardRows.str(row["id"]) ?: return null,
            cardId = survivor,
            hlc = CardRows.str(row["hlc"]) ?: return null,
            reviewedAt = long(row["reviewed_at"]) ?: return null,
            utcOffsetMin = long(row["utc_offset_min"])?.toInt(),
            reviewType = type,
            rating = Rating.entries.firstOrNull { it.value == rating },
            manualAction = ManualAction.entries.firstOrNull { it.wire == CardRows.str(row["manual_action"]) },
            manualDueAt = long(row["manual_due_at"]),
            undoneHlc = CardRows.str(row["undone_hlc"]),
            importSource = CardRows.str(row["import_source"]),
        )
    }

    private companion object {
        val FAILING_TYPES = setOf("scheduled", "learning")
    }
}
