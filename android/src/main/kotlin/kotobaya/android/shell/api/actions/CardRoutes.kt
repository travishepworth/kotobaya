package kotobaya.android.shell.api.actions

import java.util.Base64
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellHeaders
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.review.AnswerViews
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.MergeReaders
import kotobaya.sync.client.apply.Row

private fun noCard(): Nothing = throw ProblemException(Problems.notFound("No such card."))

/**
 * `getCard`: the card with its note summary; a merge loser's id answers with the survivor (`content-location`); a
 * deleted card or a card of a deleted note is `410` unless `include_deleted=true` (server `CardApi.get`).
 */
internal fun getCard(call: LocalCall): ShellResponse {
    val e = FieldErrors()
    val includeDeleted = call.query("include_deleted").bool("include_deleted", false, e)
    val id = call.pathId("card_id", "card")
    e.throwIfAny()
    val (shown, body) = call.services.get<SqlDelightDeviceStore>().read { tx ->
        val t = tx.learning
        val card = MergeReaders.resolveCard(t, id) ?: noCard()
        val note = card["note_id"].text()?.let { t.get(LearningTable.NOTE, listOf(JsonString(it))) } ?: noCard()
        if (!includeDeleted) {
            if (!card["deleted_at"].isNullish()) throw ProblemException(Problems.gone("The card was deleted."))
            if (!note["deleted_at"].isNullish()) throw ProblemException(Problems.gone("The note was deleted."))
        }
        card["id"].text().orEmpty() to AnswerViews.card(card, note, AnswerViews.leechThreshold(t))
    }
    val headers = if (shown != id) mapOf(ShellHeaders.CONTENT_LOCATION to "/api/v1/cards/$shown") else emptyMap()
    return ShellResponse.json(body, headers = headers)
}

/** `limit` (server `pageLimit`): 1..500, default 50. */
internal fun pageLimit(raw: String?, e: FieldErrors): Int =
    raw?.let { v -> v.toIntOrNull()?.takeIf { it in 1..500 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 500."); null } } ?: 50

/** The server's opaque cursor (`offset|hash` of the request shape, base64url); a foreign or malformed one is `422`. */
internal fun pageOffset(raw: String?, hash: String, e: FieldErrors): Int {
    if (raw == null) return 0
    val parts = runCatching { String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8).split('|') }.getOrNull()
    val n = parts?.getOrNull(0)?.toIntOrNull()
    if (parts == null || parts.size != 2 || n == null || n < 0 || parts[1] != hash) {
        e.add("/cursor", "invalid_value", "Malformed or foreign cursor.")
        return 0
    }
    return n
}

internal fun cursor(offset: Int, hash: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$offset|$hash".toByteArray())

/**
 * `listCardReviews`: every `review_log` row of the card (a merge loser's id resolves to the survivor) and of every
 * card merged into it, every type, undone rows included, newest first by `(reviewed_at, hlc, id)` descending; works
 * for deleted cards. Offset cursors as the server's.
 */
internal fun listCardReviews(call: LocalCall): ShellResponse {
    val q = call.query("limit", "cursor")
    val id = call.pathId("card_id", "card")
    val e = FieldErrors()
    val limit = pageLimit(q.first("limit"), e)
    val hash = "reviews|$id".hashCode().toString(16)
    val offset = pageOffset(q.first("cursor"), hash, e)
    e.throwIfAny()
    val page = call.services.get<SqlDelightDeviceStore>().read { tx ->
        val t = tx.learning
        val survivor = MergeReaders.resolveCard(t, id)?.get("id").text() ?: noCard()
        val set = LinkedHashSet<String>().apply { add(survivor) }
        val frontier = ArrayDeque(listOf(survivor))
        while (frontier.isNotEmpty()) {
            for (c in t.where(LearningTable.CARD, "merged_into_id", JsonString(frontier.removeFirst()))) {
                c["id"].text()?.let { if (set.add(it)) frontier.addLast(it) }
            }
        }
        val rows = t.whereIn(LearningTable.REVIEW_LOG, "card_id", set.map(::JsonString))
            .sortedWith(
                compareByDescending<Row> { (it["reviewed_at"] as? JsonNumber)?.value ?: 0.0 }
                    .thenByDescending { it["hlc"].text().orEmpty() }
                    .thenByDescending { it["id"].text().orEmpty() },
            )
        val window = rows.drop(offset).take(limit + 1)
        jsonObj(
            "items" to JsonArray(window.take(limit).map(AnswerViews::review)),
            "next_cursor" to if (window.size > limit) JsonString(cursor(offset + limit, hash)) else JsonNull,
        )
    }
    return ShellResponse.json(page)
}
