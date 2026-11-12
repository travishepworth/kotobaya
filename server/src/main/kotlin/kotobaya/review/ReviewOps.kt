package kotobaya.review

import kotobaya.contracts.sync.OpType
import kotobaya.notes.payload
import kotobaya.server.json
import kotobaya.sync.LoggedOp
import kotobaya.sync.OpTx

/** One `review.logged` the server mints (REST answers here, manual reschedule/reset rows in T210a). */
internal data class ReviewEntry(
    /** `review_log.id`, minted once by the client. */
    val id: String,
    /** The card the review is logged on (the survivor, for a merge loser's id). */
    val cardId: String,
    val reviewedAtMs: Long,
    /** Null = unknown. */
    val utcOffsetMin: Int?,
    /** `scheduled`, `learning`, `drill`, `cram` or `manual`. */
    val reviewType: String,
    /** 1 to 4; null iff [reviewType] is `manual`. */
    val rating: Int? = null,
    val button: String? = null,
    val durationMs: Int? = null,
    val sentenceId: String? = null,
    /** `set_due` or `reset`, for `manual` rows. */
    val manualAction: String? = null,
    val manualDueAtMs: Long? = null,
)

/**
 * The server's two review ops, in one place so every REST path that writes `review_log` emits them the same way.
 * The card cache is folded by the [kotobaya.sync.CardCacheHook] inside the same transaction (SYNC.md 9.2).
 */
internal object ReviewOps {
    const val MAX_DURATION_MS = 600_000

    /** Emits `review.logged` for [e] through [tx]; the op layer validates it (button/rating agreement included). */
    fun logReview(tx: OpTx, e: ReviewEntry): LoggedOp {
        val p = json.createObjectNode()
        p.put("id", e.id).put("card_id", e.cardId).put("reviewed_at", e.reviewedAtMs)
        if (e.utcOffsetMin == null) p.putNull("utc_offset_min") else p.put("utc_offset_min", e.utcOffsetMin)
        p.put("review_type", e.reviewType)
        if (e.rating == null) p.putNull("rating") else p.put("rating", e.rating)
        e.button?.let { p.put("button", it) }
        e.durationMs?.let { p.put("duration_ms", minOf(it, MAX_DURATION_MS)) }
        e.sentenceId?.let { p.put("sentence_id", it) }
        e.manualAction?.let { p.put("manual_action", it) }
        e.manualDueAtMs?.let { p.put("manual_due_at", it) }
        return tx.emit(OpType.REVIEW_LOGGED, payload(p))
    }

    /** Emits `review.undone` for [reviewId]. */
    fun undo(tx: OpTx, reviewId: String): LoggedOp =
        tx.emit(OpType.REVIEW_UNDONE, payload(json.createObjectNode().put("review_id", reviewId)))
}
