package kotobaya.review

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.Scheduler
import kotobaya.contracts.queue.QueueBuilder
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.LoopResult
import kotobaya.contracts.queue.LoopState
import kotobaya.contracts.queue.ReviewLoop
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.queue.DefaultReviewLoop
import kotobaya.auth.authenticated
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
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

private val ANSWER_KEYS = setOf("id", "card_id", "rating", "button", "mode", "duration_ms", "reviewed_at", "utc_offset_min", "sentence_id")
private val BUTTON_RATING = mapOf("again" to 1, "nothing" to 1, "something" to 1, "hard" to 2, "good" to 3, "okay" to 3, "easy" to 4)

/** `FLOOR` of `answerReview`: 2020-01-01T00:00:00Z. */
private const val CLOCK_FLOOR_MS = 1_577_836_800_000L
private const val FUTURE_TOLERANCE_MS = 5 * 60_000L
private const val BEFORE_CARD_MS = 24 * 3_600_000L
private val ALL_KINDS = setOf("new", "learning", "review")

/** C-27: materialize-and-rebuild rounds per fetch. */
private const val MATERIALIZE_ROUNDS = 3

/** `getReviewQueue`, `answerReview`, `undoReview`. */
internal class ReviewApi(
    private val db: Database,
    private val opApply: OpApply,
    private val scheduler: Scheduler,
    private val builder: QueueBuilder,
    private val clock: () -> Instant,
    private val materializer: DeckMaterializer = DeckMaterializer(),
    private val loop: ReviewLoop = DefaultReviewLoop,
) {
    suspend fun queue(call: ApplicationCall) {
        val q = call.request.queryParameters
        val e = Errors()
        val limit = q["limit"]?.let { v -> v.toIntOrNull()?.takeIf { it in 1..200 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 200."); null } } ?: 20
        val deckId = q["deck_id"]?.let { v -> if (UUID_RE.matches(v)) v else { e.add("/deck_id", "invalid_value", "Must be a lowercase UUID."); null } }
        val kinds = q["kinds"]?.split(',')?.let { parts ->
            parts.filter { it !in ALL_KINDS }.forEach { e.add("/kinds", "invalid_value", "Unknown kind $it.") }
            parts.toSet()
        } ?: ALL_KINDS
        // C-26: only the literal values `true` / `false`.
        val keepGoing = when (val v = q["keep_going"]) {
            null, "false" -> false
            "true" -> true
            else -> { e.add("/keep_going", "invalid_value", "Must be true or false, not $v."); false }
        }
        e.throwIfAny()

        val nowMs = clock().toEpochMilli()
        val req = QueueRequest(nowMs, limit, deckId, kinds, keepGoing)
        // C-27 (ADR-032): deck entries the builder draws become notes and cards here, the one materialization path
        // for API clients. Only for a caller that may review (`review` or `write`), and only when new cards are asked for.
        val scopes = call.authenticated?.principal?.scopes.orEmpty()
        val mayMaterialize = "new" in kinds && ("review" in scopes || "write" in scopes)
        // A cheap read first: most fetches draw no pending entry and never take the sync lock.
        val first = db.tx { dsl ->
            if (deckId != null && !dsl.fetchExists(dsl.selectOne().from("app.deck").where("id = ?::uuid and deleted_at is null", deckId))) {
                throw Problems.notFound("No live deck $deckId.")
            }
            val built = buildQueue(dsl, req)
            if (mayMaterialize && built.queue.toMaterialize.isNotEmpty()) null else render(dsl, req, built)
        }
        if (first != null) {
            call.respondJson(first)
            return
        }
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            var built = buildQueue(tx.dsl, req)
            var rounds = 0
            // Each round creates notes for the entries drawn, then rebuilds: their cards now take those places (the core
            // drew each entry as the group its card will be, so they fit). A round can still free a slot (a sibling or
            // kanji card taken elsewhere), so a few rounds; anything left is reported in `to_materialize` and drawn by the
            // next fetch. Safety net: another round only if the last one's notes reached `items`, so a mismatch between
            // what was drawn and what shows can never create notes that are not shown.
            while (built.queue.toMaterialize.isNotEmpty() && rounds < MATERIALIZE_ROUNDS) {
                val created = materializer.materialize(tx, built.queue.toMaterialize).toSet()
                built = buildQueue(tx.dsl, req)
                rounds++
                val noteOf = built.loaded.candidates.associate { it.cardId to it.noteId }
                if (built.queue.items.none { noteOf[it.cardId] in created }) break
            }
            Reply(HttpStatusCode.OK, json.valueToTree(render(tx.dsl, req, built)), tx.lastSeq)
        }
        call.send(reply)
    }

    private class QueueRequest(val nowMs: Long, val limit: Int, val deckId: String?, val kinds: Set<String>, val keepGoing: Boolean)

    private class Built(val settings: ReviewSettings, val loaded: LoadedQueue, val result: LoopResult) {
        /** The build to serve (N, or K under keep going); the only one whose `toMaterialize` may be materialized. */
        val queue: ReviewQueue get() = result.queue
    }

    private fun buildQueue(dsl: DSLContext, r: QueueRequest): Built {
        val settings = ReviewSettings.load(dsl)
        // C-26: keep going is a per-request override (core rule 6); the batch is the page size.
        val loaded = QueueLoader(dsl).load(r.nowMs, settings, r.deckId, keepGoing = r.keepGoing, keepGoingNewBatch = r.limit)
        return Built(settings, loaded, loop.evaluate(loaded.input))
    }

    private fun render(dsl: DSLContext, r: QueueRequest, b: Built): Map<String, Any?> {
        val nowMs = r.nowMs
        val settings = b.settings
        val loaded = b.loaded
        val queue = b.queue
        val kinds = r.kinds
        val day = studyDayOf(nowMs, settings.timezone, settings.dayRolloverHour)

        val state = b.result.state
        val legacy = state.legacy
        // ADR-037: `counts` keep their frozen pre-loop meaning for old clients; they come from the loop's legacy block.
        val counts = json.createObjectNode()
            .put("new", legacy.new)
            .put("learning", legacy.learning)
            .put("review", legacy.review)
        val items = queue.items
        fun inKinds(i: QueueItem) = i.kind.name.lowercase() in kinds
        val wanted = items.filter(::inKinds).take(r.limit)
        // C-26 `more_available`: only for an empty normal response, by the same build under keep going.
        // All kinds: the core's own signal (it counts pending entries too). A kinds filter: one extra keep-going
        // build, only when the filtered page is empty; drawn entries count when `new` is asked for.
        val moreAvailable = if (kinds == ALL_KINDS) {
            // A keep-going request never reports more (the served build K is the "more").
            !r.keepGoing && legacy.moreAvailable
        } else {
            !r.keepGoing && wanted.isEmpty() &&
                builder.build(loaded.input.copy(limits = loaded.input.limits.copy(keepGoing = true))).let { kg ->
                    kg.items.any(::inKinds) || ("new" in kinds && kg.toMaterialize.isNotEmpty())
                }
        }
        val views = ReviewViews(dsl, settings.leechThreshold)
        val rendered = views.items(
            wanted.map { it.cardId },
            wanted.associate { it.cardId to if (it.kind == QueueItemKind.REVIEW) "scheduled" else "learning" },
            nowMs, scheduler,
        )
        val out = linkedMapOf<String, Any?>(
            "items" to rendered,
            "counts" to counts,
            "study_day" to day.date.toString(),
            "day_ends_at" to formatInstant(Instant.ofEpochMilli(day.day.endMs)),
            "generated_at" to formatInstant(Instant.ofEpochMilli(nowMs)),
            "more_available" to moreAvailable,
            "to_materialize" to if ("new" in kinds) queue.toMaterialize.size else 0,
        )
        out["loop"] = loopJson(state)
        // ADR-033: omitted when no learning card is waiting later today.
        state.nextDueAt?.let { out["next_learning_due_at"] = formatInstant(Instant.ofEpochMilli(it)) }
        return out
    }

    /** `ReviewQueue.loop`: the wire form of [LoopState]. */
    private fun loopJson(s: LoopState): Map<String, Any?> = linkedMapOf(
        "phase" to s.phase.wire,
        "keep_going" to s.keepGoing,
        "ready" to linkedMapOf("learning" to s.readyLearning, "review" to s.readyReviews, "total" to s.readyLearning + s.readyReviews),
        "later_today" to linkedMapOf("count" to s.laterToday, "next_due_at" to s.nextDueAt?.let { formatInstant(Instant.ofEpochMilli(it)) }),
        "backlog" to linkedMapOf("reviews" to s.backlog, "beyond_limit" to s.backlogBeyondLimit),
        "reviews" to linkedMapOf("today" to s.reviewsToday, "limit" to s.reviewsLimit),
        "new" to linkedMapOf("today" to s.newToday, "goal" to s.newGoal, "available" to s.newAvailable),
    )

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
