package kotobaya.review

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.Scheduler
import kotobaya.contracts.queue.QueueBuilder
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.notes.Errors
import kotobaya.notes.Reply
import kotobaya.notes.UUID_RE
import kotobaya.notes.callerTokenId
import kotobaya.notes.gone
import kotobaya.notes.jsonBody
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.formatInstant
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

private val ANSWER_KEYS = setOf("id", "card_id", "rating", "button", "mode", "duration_ms", "reviewed_at", "utc_offset_min", "sentence_id")
private val BUTTON_RATING = mapOf("again" to 1, "nothing" to 1, "something" to 1, "hard" to 2, "good" to 3, "okay" to 3, "easy" to 4)
private val BLOCKED = setOf(WordStatus.KNOWN, WordStatus.NEVER_FORGET, WordStatus.BLACKLISTED, WordStatus.IGNORED)

/** `FLOOR` of `answerReview`: 2020-01-01T00:00:00Z. */
private const val CLOCK_FLOOR_MS = 1_577_836_800_000L
private const val FUTURE_TOLERANCE_MS = 5 * 60_000L
private const val BEFORE_CARD_MS = 24 * 3_600_000L

/** `getReviewQueue`, `answerReview`, `undoReview`. */
internal class ReviewApi(
    private val db: Database,
    private val opApply: OpApply,
    private val scheduler: Scheduler,
    private val builder: QueueBuilder,
    private val clock: () -> Instant,
) {
    suspend fun queue(call: ApplicationCall) {
        val q = call.request.queryParameters
        val e = Errors()
        val limit = q["limit"]?.let { v -> v.toIntOrNull()?.takeIf { it in 1..200 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 200."); null } } ?: 20
        val deckId = q["deck_id"]?.let { v -> if (UUID_RE.matches(v)) v else { e.add("/deck_id", "invalid_value", "Must be a lowercase UUID."); null } }
        val kinds = q["kinds"]?.split(',')?.let { parts ->
            parts.filter { it !in setOf("new", "learning", "review") }.forEach { e.add("/kinds", "invalid_value", "Unknown kind $it.") }
            parts.toSet()
        } ?: setOf("new", "learning", "review")
        e.throwIfAny()

        val nowMs = clock().toEpochMilli()
        val body = db.tx { dsl ->
            if (deckId != null && !dsl.fetchExists(dsl.selectOne().from("app.deck").where("id = ?::uuid and deleted_at is null", deckId))) {
                throw Problems.notFound("No live deck $deckId.")
            }
            val settings = ReviewSettings.load(dsl)
            val loaded = QueueLoader(dsl).load(nowMs, settings, deckId)
            val queue = builder.build(loaded.input)
            val day = studyDayOf(nowMs, settings.timezone, settings.dayRolloverHour)

            val items = queue.items
            // Learning cards due later today (outside learn-ahead) still count as remaining today.
            val shown = items.mapTo(HashSet()) { it.cardId }
            val laterLearning = loaded.candidates.count { c ->
                c.cardId !in shown && (c.state == CardState.LEARNING || c.state == CardState.RELEARNING) &&
                    (c.buriedUntil == null || c.buriedUntil!! <= nowMs) && c.wordOverride !in BLOCKED &&
                    c.dueAt != null && c.dueAt!! < day.day.endMs
            }
            val counts = json.createObjectNode()
                .put("new", items.count { it.kind == QueueItemKind.NEW })
                .put("learning", items.count { it.kind == QueueItemKind.LEARNING } + laterLearning)
                .put("review", items.count { it.kind == QueueItemKind.REVIEW })
            val wanted = items.filter { it.kind.name.lowercase() in kinds }.take(limit)
            val views = ReviewViews(dsl, settings.leechThreshold)
            val rendered = views.items(
                wanted.map { it.cardId },
                wanted.associate { it.cardId to if (it.kind == QueueItemKind.REVIEW) "scheduled" else "learning" },
                nowMs, scheduler,
            )
            linkedMapOf(
                "items" to rendered,
                "counts" to counts,
                "study_day" to day.date.toString(),
                "day_ends_at" to formatInstant(Instant.ofEpochMilli(day.day.endMs)),
                "generated_at" to formatInstant(Instant.ofEpochMilli(nowMs)),
            )
        }
        call.respondJson(body)
    }

    suspend fun answer(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, ANSWER_KEYS)
        val id = e.uuid(body, "id", required = true)
        val cardId = e.uuid(body, "card_id", required = true)
        val rating = body.get("rating")?.let { e.long(it, "/rating", 1, 4)?.toInt() } ?: run { if (!body.has("rating")) e.add("/rating", "required", "rating is required."); null }
        val button = e.enum(body, "button", BUTTON_RATING.keys)
        val mode = e.enum(body, "mode", setOf("schedule", "drill", "cram")) ?: "schedule"
        val duration = body.get("duration_ms")?.let { e.long(it, "/duration_ms", 0)?.coerceAtMost(ReviewOps.MAX_DURATION_MS.toLong())?.toInt() }
        val offset = body.get("utc_offset_min")?.let { e.long(it, "/utc_offset_min", -1080, 1080)?.toInt() }
        val sentenceId = e.uuid(body, "sentence_id")
        val reviewedAt = body.get("reviewed_at")?.let { n ->
            if (!n.isTextual) {
                e.add("/reviewed_at", "invalid_type", "Must be an RFC 3339 instant.")
                null
            } else {
                try {
                    OffsetDateTime.parse(n.asText()).toInstant().toEpochMilli()
                } catch (x: DateTimeParseException) {
                    e.add("/reviewed_at", "invalid_value", "Must be an RFC 3339 instant.")
                    null
                }
            }
        }
        if (button != null && rating != null && BUTTON_RATING[button] != rating) {
            e.add("/button", "button_rating_mismatch", "Button $button belongs to rating ${BUTTON_RATING[button]}, not $rating.")
        }
        e.throwIfAny()
        val reviewId = checkNotNull(id)

        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val dsl = tx.dsl
            val views = ReviewViews(dsl, ReviewSettings.load(dsl).leechThreshold)
            // Retry: the stored review and the card as it is now; nothing is emitted, whatever the body says.
            views.review(reviewId)?.let { return@rest Reply(HttpStatusCode.OK, result(views, it)) }

            val survivorId = views.survivorOf(checkNotNull(cardId)) ?: throw Problems.notFound("No such card.")
            val card = views.cards(listOf(survivorId))[survivorId] ?: throw Problems.notFound("No such card.")
            val deleted = card.json["deleted_at"]
            if (!deleted.isNull) throw gone("card", deleted.asText())
            val noteDeleted = dsl.fetchValue("select to_char(deleted_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') from app.note where id = ?::uuid", card.json["note_id"].asText()) as String?
            if (noteDeleted != null) throw gone("note", noteDeleted)
            if (card.json["suspended"].asBoolean() && mode == "schedule") {
                throw ApiException(HttpStatusCode.Conflict, "card_not_reviewable", "Card not reviewable", "The card is suspended.")
            }

            val nowMs = clock().toEpochMilli()
            val at = reviewedAt ?: nowMs
            val createdMs = (dsl.fetchValue("select ${msOf("created_at")} from app.card where id = ?::uuid", survivorId) as Number).toLong()
            val bound = Errors()
            when {
                at > nowMs + FUTURE_TOLERANCE_MS -> bound.add("/reviewed_at", "in_future", "More than 5 minutes after the server's now.")
                at < CLOCK_FLOOR_MS -> bound.add("/reviewed_at", "before_floor", "Before 2020-01-01T00:00:00Z.")
                at < minOf(createdMs, nowMs) - BEFORE_CARD_MS -> bound.add("/reviewed_at", "before_card", "More than 24 h before the card was created.")
            }
            bound.throwIfAny()

            val type = when {
                mode != "schedule" -> mode
                card.schedule.state == CardState.REVIEW -> "scheduled"
                else -> "learning"
            }
            ReviewOps.logReview(
                tx,
                ReviewEntry(reviewId, survivorId, at, offset, type, checkNotNull(rating), button, duration, sentenceId),
            )
            Reply(HttpStatusCode.OK, result(views, checkNotNull(views.review(reviewId))), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun undo(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("review_id"))
        val reviewId = checkNotNull(e.uuid(body, "review_id", required = true).also { e.throwIfAny() })
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val views = ReviewViews(tx.dsl, ReviewSettings.load(tx.dsl).leechThreshold)
            val row = views.review(reviewId) ?: throw Problems.notFound("No such review.")
            // An undo cannot be undone: a retry (or a second undo) answers with the current state.
            if (!row["undone_at"].isNull) return@rest Reply(HttpStatusCode.OK, result(views, row))
            ReviewOps.undo(tx, reviewId)
            Reply(HttpStatusCode.OK, result(views, checkNotNull(views.review(reviewId))), tx.lastSeq)
        }
        call.send(reply)
    }

    /** `ReviewResult`: the review and the card it was logged on (its survivor, if merged) as it is now. */
    private fun result(views: ReviewViews, review: ObjectNode): ObjectNode {
        val survivor = views.survivorOf(review["card_id"].asText()) ?: review["card_id"].asText()
        val card = views.cards(listOf(survivor))[survivor] ?: error("card $survivor vanished")
        return json.createObjectNode().also {
            it.set<ObjectNode>("review", review)
            it.set<ObjectNode>("card", card.json)
        }
    }
}
