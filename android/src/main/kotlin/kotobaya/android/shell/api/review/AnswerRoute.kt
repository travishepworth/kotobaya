package kotobaya.android.shell.api.review

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotobaya.android.notify.DueTrigger
import kotobaya.android.review.CardRows
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.MergeReaders
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.store.StoreTransaction

private val ANSWER_KEYS = setOf("id", "card_id", "rating", "button", "mode", "duration_ms", "reviewed_at", "utc_offset_min", "sentence_id")

/** `button` to the FSRS rating it belongs to (openapi `answerReview`). */
internal val BUTTON_RATING: Map<String, Int> =
    mapOf("again" to 1, "nothing" to 1, "something" to 1, "hard" to 2, "good" to 3, "okay" to 3, "easy" to 4)

private val MODES = setOf("schedule", "drill", "cram")
private val LOWER_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** `duration_ms` above this is clamped (openapi `answerReview`; the same cap as `ReviewSession`). */
internal const val MAX_DURATION_MS: Long = 600_000L

/** 2020-01-01T00:00:00Z (SYNC.md 3.4 `FLOOR`). */
internal const val CLOCK_FLOOR_MS: Long = 1_577_836_800_000L
internal const val FUTURE_TOLERANCE_MS: Long = 5 * 60_000L
internal const val BEFORE_CARD_MS: Long = 24 * 3_600_000L

/** A validated `ReviewAnswerRequest`. */
internal data class AnswerRequest(
    val id: String,
    val cardId: String,
    val rating: Int,
    val button: String?,
    val mode: String,
    /** Already clamped to [MAX_DURATION_MS]. */
    val durationMs: Long?,
    val utcOffsetMin: Int?,
    val sentenceId: String?,
    /** Epoch ms; null = the device's now. */
    val reviewedAt: Long?,
)

// ---- body readers: the server's `Errors` helpers, same codes in the same order -----------------------------------

private fun FieldErrors.uuid(o: JsonObject, key: String, required: Boolean = false): String? {
    val v = o.members[key]
    if (v == null) {
        if (required) add("/$key", "required", "$key is required.")
        return null
    }
    val text = (v as? JsonString)?.value
    if (text == null || !LOWER_UUID.matches(text)) {
        add("/$key", "invalid_value", "Must be a lowercase UUID.")
        return null
    }
    return text
}

private fun FieldErrors.enum(o: JsonObject, key: String, values: Set<String>): String? {
    val v = o.members[key] ?: return null
    val text = (v as? JsonString)?.value
    if (text == null || text !in values) {
        add("/$key", "invalid_value", "Must be one of ${values.joinToString(", ")}.")
        return null
    }
    return text
}

/** An integer in `[min, max]`: not an integer → `invalid_type`, outside → `out_of_range`. */
private fun FieldErrors.long(v: JsonValue, at: String, min: Long, max: Long = Long.MAX_VALUE): Long? {
    val d = (v as? JsonNumber)?.value
    if (d == null || !d.isFinite() || d != Math.floor(d) || d < -9.007199254740992E15 || d > 9.007199254740992E15) {
        add(at, "invalid_type", "Must be an integer.")
        return null
    }
    val n = d.toLong()
    if (n < min || n > max) {
        add(at, "out_of_range", if (n < min) "Must be at least $min." else "Must be at most $max.")
        return null
    }
    return n
}

/**
 * The body exactly as the server reads it (`ReviewApi.answer`): every error of the body in one `422`; unknown
 * properties first, then `id`, `card_id`, `rating`, `button`, `mode`, `duration_ms`, `utc_offset_min`, `sentence_id`,
 * `reviewed_at`, and last `button` not belonging to `rating` (`/button`, `button_rating_mismatch`).
 */
internal fun answerRequest(call: LocalCall): AnswerRequest {
    val body = call.bodyObject()
    val e = FieldErrors()
    body.members.keys.forEach { if (it !in ANSWER_KEYS) e.add("/$it", "unknown_field", "Unknown property $it.") }
    val id = e.uuid(body, "id", required = true)
    val cardId = e.uuid(body, "card_id", required = true)
    val rating = body.members["rating"].let { v ->
        if (v == null) {
            e.add("/rating", "required", "rating is required.")
            null
        } else {
            e.long(v, "/rating", 1, 4)?.toInt()
        }
    }
    val button = e.enum(body, "button", BUTTON_RATING.keys)
    val mode = e.enum(body, "mode", MODES) ?: "schedule"
    val duration = body.members["duration_ms"]?.let { e.long(it, "/duration_ms", 0)?.coerceAtMost(MAX_DURATION_MS) }
    val offset = body.members["utc_offset_min"]?.let { e.long(it, "/utc_offset_min", -1080, 1080)?.toInt() }
    val sentenceId = e.uuid(body, "sentence_id")
    val reviewedAt = body.members["reviewed_at"]?.let { v ->
        val text = (v as? JsonString)?.value
        if (text == null) {
            e.add("/reviewed_at", "invalid_type", "Must be an RFC 3339 instant.")
            null
        } else {
            try {
                OffsetDateTime.parse(text).toInstant().toEpochMilli()
            } catch (x: DateTimeParseException) {
                e.add("/reviewed_at", "invalid_value", "Must be an RFC 3339 instant.")
                null
            } catch (x: ArithmeticException) {
                e.add("/reviewed_at", "invalid_value", "Must be an RFC 3339 instant.")
                null
            }
        }
    }
    if (button != null && rating != null && BUTTON_RATING[button] != rating) {
        e.add("/button", "button_rating_mismatch", "Button $button belongs to rating ${BUTTON_RATING[button]}, not $rating.")
    }
    e.throwIfAny()
    return AnswerRequest(
        id = checkNotNull(id),
        cardId = checkNotNull(cardId),
        rating = checkNotNull(rating),
        button = button,
        mode = mode,
        durationMs = duration,
        utcOffsetMin = offset,
        sentenceId = sentenceId,
        reviewedAt = reviewedAt,
    )
}

/** The request property a refused `review.logged` pointer (`/payload/<name>`) came from (SHELL.md L-3). */
private fun answerPointer(r: MintRefusal.Invalid): String {
    val name = r.pointer.removePrefix("/payload/").substringBefore('/')
    return when {
        !r.pointer.startsWith("/payload/") -> ""
        name == "review_type" -> "/mode"
        name in ANSWER_KEYS -> "/$name"
        else -> ""
    }
}

/**
 * Serializes this process's `answerReview` / `undoReview` writes. The contract's guarantee is the store transaction
 * (L-4: check and mint in one; SQLite admits one writer, so two retries of one answer cannot both miss the row). The
 * lock does not replace that; it keeps the check-then-mint of two bridge requests atomic whatever the driver's
 * transaction mode is, also on a store whose transactions do not exclude each other (the single-connection JVM
 * driver of the tests). Taken outside the transaction and never while holding another lock.
 */
private val writeLock = Any()

/** [LocalWrites.write] under [writeLock]. */
private fun <T> LocalWrites<SqlLearningTables>.exclusive(block: (StoreTransaction<SqlLearningTables>) -> WritePlan<SqlLearningTables, T>): T =
    synchronized(writeLock) { write(block) }

/**
 * After a committed answer or undo: the due alarm, the notification, the widget and the tile are recomputed through
 * the app's one seam ([DueTrigger], "after each review"), off the calling thread. Never fails the request: the write
 * is already durable, and a failure here would only make the SPA retry a request that is answered from the stored row.
 */
private fun dueChanged(call: LocalCall) {
    runCatching { call.services.get<DueTrigger>().fireInBackground() }
}

/**
 * `answerReview` (SHELL.md 4.1 L-2 … L-6, 4.3): stateless; one `review.logged` minted as this device through
 * [LocalWrites], whose local effect is the op-apply path — the card's schedule is the fold / full replay that path
 * runs (SYNC.md 9.2), never arithmetic here.
 *
 * Everything from the idempotency check to the answer is ONE store transaction (L-4), in the server's order:
 * 1. a `review_log` row with `id` exists (whichever card, undone or not) → `200` with the stored review and its
 *    card as it is now; nothing minted, whatever the rest of the body says. This is what makes a retry safe after a
 *    lost bridge response or a process death between the commit and the response: the commit either happened (the
 *    retry reads the row) or did not (the retry mints it), never half.
 * 2. the card: unknown → `404`; a merge loser is answered on its survivor; card or note tombstoned → `410`;
 *    suspended and `mode = schedule` → `409 card_not_reviewable`.
 * 3. `reviewed_at` (default: the device's now, L-5) against `in_future`, `before_floor`, `before_card`, in that order.
 * 4. mint. A wrong device clock is refused by the minter's guards (G1 / G2 / server skew) with the `422`s of L-3.
 *
 * `review_type`: `drill` / `cram` as sent; for `schedule`, `scheduled` when the card's state is `review`, else
 * `learning`. The payload is the one the server builds: `utc_offset_min` null when the request has none, `button`,
 * `duration_ms` and `sentence_id` only when sent.
 */
internal fun answerReview(call: LocalCall): ShellResponse {
    val r = answerRequest(call)
    val now = call.services.get<ShellIdentity>().nowMs()
    val writes = call.services.get<LocalWrites<SqlLearningTables>>()
    var schedule = false
    val body = writes.exclusive<JsonObject> { tx ->
        val t = tx.learning
        t.get(LearningTable.REVIEW_LOG, listOf(JsonString(r.id)))?.let { stored ->
            return@exclusive WritePlan.Answer(AnswerViews.result(t, stored))
        }
        val card = MergeReaders.resolveCard(t, r.cardId) ?: throw ProblemException(Problems.notFound("No such card."))
        val cardId = CardRows.str(card["id"]) ?: throw ProblemException(Problems.notFound("No such card."))
        val note = CardRows.str(card["note_id"])?.let { CardRows.get(t, LearningTable.NOTE, it) }
            ?: throw ProblemException(Problems.notFound("No such card."))
        if ((card["deleted_at"] ?: JsonNull) != JsonNull) throw ProblemException(Problems.gone("The card was deleted."))
        if ((note["deleted_at"] ?: JsonNull) != JsonNull) throw ProblemException(Problems.gone("The note was deleted."))
        if ((card["suspended"] as? JsonBool)?.value == true && r.mode == "schedule") {
            throw ProblemException(Problems.conflict("card_not_reviewable", "The card is suspended."))
        }

        val at = r.reviewedAt ?: now
        val createdMs = (card["created_at"] as? JsonNumber)?.value?.toLong() ?: now
        when {
            at > now + FUTURE_TOLERANCE_MS ->
                throw ProblemException(Problems.validationFailed("/reviewed_at", "in_future", "More than 5 minutes after the device's now."))
            at < CLOCK_FLOOR_MS ->
                throw ProblemException(Problems.validationFailed("/reviewed_at", "before_floor", "Before 2020-01-01T00:00:00Z."))
            at < minOf(createdMs, now) - BEFORE_CARD_MS ->
                throw ProblemException(Problems.validationFailed("/reviewed_at", "before_card", "More than 24 h before the card was created."))
        }

        val type = when {
            r.mode != "schedule" -> r.mode
            CardRows.scheduleOf(card).state == CardState.REVIEW -> "scheduled"
            else -> "learning"
        }
        val payload = LinkedHashMap<String, JsonValue>()
        payload["id"] = JsonString(r.id)
        payload["card_id"] = JsonString(cardId)
        payload["reviewed_at"] = JsonNumber(at.toDouble())
        payload["utc_offset_min"] = r.utcOffsetMin?.let { JsonNumber(it.toDouble()) } ?: JsonNull
        payload["review_type"] = JsonString(type)
        payload["rating"] = JsonNumber(r.rating.toDouble())
        r.button?.let { payload["button"] = JsonString(it) }
        r.durationMs?.let { payload["duration_ms"] = JsonNumber(it.toDouble()) }
        r.sentenceId?.let { payload["sentence_id"] = JsonString(it) }
        WritePlan.Mint(
            requests = listOf(MintRequest(OpType.REVIEW_LOGGED, JsonObject(payload))),
            invalidPointer = ::answerPointer,
        ) { after, _ ->
            schedule = r.mode == "schedule"
            val stored = checkNotNull(after.learning.get(LearningTable.REVIEW_LOG, listOf(JsonString(r.id)))) { "the minted review ${r.id} was not applied" }
            AnswerViews.result(after.learning, stored)
        }
    }
    // drill / cram never touch a schedule (SYNC.md 9.1): nothing that is due changed
    if (schedule) dueChanged(call)
    return ShellResponse.json(body)
}

/**
 * `undoReview` (SHELL.md L-4): `review_id` required; unknown → `404`; already undone → `200` with the current state,
 * nothing minted (an undo cannot be undone, so a retry is safe); else one `review.undone` minted as this device. The
 * check and the mint share one store transaction; the card is recomputed by the full replay the apply path runs for
 * an undo of a schedule-feeding review (SYNC.md 9.2). Any review can be undone, whichever device logged it.
 */
internal fun undoReview(call: LocalCall): ShellResponse {
    val body = call.bodyObject()
    val e = FieldErrors()
    body.members.keys.forEach { if (it != "review_id") e.add("/$it", "unknown_field", "Unknown property $it.") }
    val reviewId = e.uuid(body, "review_id", required = true)
    e.throwIfAny()
    val id = checkNotNull(reviewId)
    val writes = call.services.get<LocalWrites<SqlLearningTables>>()
    var minted = false
    val result = writes.exclusive<JsonObject> { tx ->
        val t = tx.learning
        val row = t.get(LearningTable.REVIEW_LOG, listOf(JsonString(id))) ?: throw ProblemException(Problems.notFound("No such review."))
        if ((row["undone_at"] ?: JsonNull) != JsonNull) return@exclusive WritePlan.Answer(AnswerViews.result(t, row))
        WritePlan.Mint(
            requests = listOf(MintRequest(OpType.REVIEW_UNDONE, JsonObject(linkedMapOf<String, JsonValue>("review_id" to JsonString(id))))),
            invalidPointer = { "/review_id" },
        ) { after, _ ->
            // drill and cram reviews never feed the schedule (SYNC.md 9.2): undoing one changes nothing due
            minted = CardRows.str(row["review_type"]) !in setOf("drill", "cram")
            val stored = checkNotNull(after.learning.get(LearningTable.REVIEW_LOG, listOf(JsonString(id)))) { "review $id vanished" }
            AnswerViews.result(after.learning, stored)
        }
    }
    if (minted) dueChanged(call)
    return ShellResponse.json(result)
}
