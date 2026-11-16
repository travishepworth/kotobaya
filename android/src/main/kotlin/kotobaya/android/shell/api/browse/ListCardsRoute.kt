package kotobaya.android.shell.api.browse

import java.util.Base64
import kotobaya.android.shell.api.Fallthrough
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.Problem
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.actions.CardQuery
import kotobaya.android.shell.api.actions.CardQueryResult
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.pageLimit
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.api.review.AnswerViews
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row

private val SORTS = setOf("created_at", "due_at", "updated_at", "lapses", "difficulty", "stability", "last_review_at")

/** One card of a result, positioned by its sort key. */
internal class CardPos(val value: Double?, val id: String)

/**
 * The order of `listCards`: by the sort key (NULL last in either direction), ties by `id` ascending. [descending]
 * flips the key only.
 */
internal fun cardOrder(descending: Boolean): Comparator<CardPos> = Comparator { a, b ->
    val av = a.value
    val bv = b.value
    val byKey = when {
        av == null && bv == null -> 0
        av == null -> 1
        bv == null -> -1
        else -> if (descending) bv.compareTo(av) else av.compareTo(bv)
    }
    if (byKey != 0) byKey else a.id.compareTo(b.id)
}

private fun encodeCursor(p: CardPos, hash: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("${p.value?.toString().orEmpty()}|${p.id}|$hash".toByteArray())

/** The position a cursor names, or null when it is malformed or from another request shape. */
private fun decodeCursor(raw: String, hash: String): CardPos? {
    val parts = runCatching { String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8).split('|') }.getOrNull() ?: return null
    if (parts.size != 3 || parts[2] != hash || parts[1].isEmpty()) return null
    val value = if (parts[0].isEmpty()) null else parts[0].toDoubleOrNull() ?: return null
    return CardPos(value, parts[1])
}

/** The server's `422 invalid_query`: one error per parser error, with its UTF-16 `position`. */
internal fun invalidQuery(errors: List<Pair<String, Int>>): Problem {
    val list = errors.map { (code, pos) ->
        jsonObj(
            "pointer" to JsonString("/query"), "code" to JsonString(code),
            "detail" to JsonString("Search error $code at $pos."), "position" to JsonNumber(pos.toDouble()),
        )
    }
    val detail = errors.firstOrNull()?.let { (code, pos) -> "Search error $code at $pos." } ?: "The query does not parse."
    return Problem(422, "invalid_query", "Invalid search query", detail, extra = mapOf("errors" to JsonArray(list)))
}

/**
 * `listCards` (SHELL.md L-1): the card search language over the replica through [CardQuery] (core parser and SQL
 * compiler, live smart decks, residual check, dictionary forms: the same selection `bulkCards` acts on), then sorted
 * by `sort` (ties by id ascending, NULL last) and paged with an opaque keyset cursor `(sort value, id)`: a card
 * created or changed between two pages never makes another card repeat or vanish. Parse errors are `422
 * invalid_query` with `errors[0].position`. A query the device cannot select exactly as the server would (a text
 * term matching more dictionary entries than the device's guard trusts) is forwarded ([Fallthrough]). Retired kinds
 * (ADR-035) are listed like any card.
 */
internal fun listCards(call: LocalCall): ShellResponse {
    val q = call.query("query", "sort", "limit", "cursor")
    val e = FieldErrors()
    val query = q.first("query")?.also { if (it.length > 2000) e.add("/query", "out_of_range", "At most 2000 characters.") }
    val sortParam = q.first("sort") ?: "-created_at"
    val sortKey = sortParam.removePrefix("-")
    if (sortKey !in SORTS || sortParam.length > sortKey.length + 1) {
        e.add("/sort", "invalid_value", "Must be one of ${SORTS.joinToString(", ")}, optionally with a leading -.")
    }
    val limit = pageLimit(q.first("limit"), e)
    val hash = "${query.orEmpty()}|$sortParam".hashCode().toString(16)
    val after = q.first("cursor")?.let { c -> decodeCursor(c, hash).also { if (it == null) e.add("/cursor", "invalid_value", "Malformed or foreign cursor.") } }
    e.throwIfAny()

    val selector = runCatching { call.services.get<CardQuery>() }.getOrNull() ?: return Fallthrough
    val now = call.services.get<ShellIdentity>().nowMs()
    val ids = when (val r = selector.select(query.orEmpty(), now)) {
        is CardQueryResult.Invalid -> throw ProblemException(invalidQuery(r.errors))
        is CardQueryResult.Forward -> return Fallthrough
        is CardQueryResult.Ids -> r.ids
    }
    val order = cardOrder(sortParam.startsWith("-"))
    val body = call.services.get<SqlDelightDeviceStore>().read { tx ->
        val t = tx.learning
        val rows = HashMap<String, Row>()
        val notes = HashMap<String, Row>()
        val positions = ArrayList<CardPos>()
        for (id in ids) {
            val card = t.get(LearningTable.CARD, listOf(JsonString(id))) ?: continue
            if (!card["deleted_at"].isNullish() || !card["merged_into_id"].isNullish()) continue
            val noteId = card["note_id"].text() ?: continue
            val note = notes[noteId] ?: t.get(LearningTable.NOTE, listOf(JsonString(noteId)))?.also { notes[noteId] = it } ?: continue
            if (!note["deleted_at"].isNullish()) continue
            rows[id] = card
            positions += CardPos((card[sortKey] as? JsonNumber)?.value, id)
        }
        val window = positions.sortedWith(order).asSequence()
            .filter { after == null || order.compare(it, after) > 0 }
            .take(limit + 1).toList()
        val shown = window.take(limit)
        val threshold = AnswerViews.leechThreshold(t)
        val items = shown.map { p ->
            val card = checkNotNull(rows[p.id])
            AnswerViews.card(card, checkNotNull(notes[card["note_id"].text()]), threshold)
        }
        jsonObj(
            "items" to JsonArray(items),
            "next_cursor" to if (window.size > limit) JsonString(encodeCursor(shown.last(), hash)) else JsonNull,
        )
    }
    return ShellResponse.json(body)
}
