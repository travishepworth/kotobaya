package kotobaya.cards.bulk

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.cards.CardSearch
import kotobaya.cards.parseInstant
import kotobaya.contracts.sync.OpType
import kotobaya.decks.DeckViews
import kotobaya.notes.CodePointOrder
import kotobaya.notes.Errors
import kotobaya.notes.Reply
import kotobaya.notes.callerTokenId
import kotobaya.notes.jsonBody
import kotobaya.notes.payload
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.notes.tagList
import kotobaya.review.ReviewEntry
import kotobaya.review.ReviewOps
import kotobaya.review.ReviewSettings
import kotobaya.review.studyDayOf
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.OpApply
import kotobaya.sync.OpTx
import java.time.Instant

private const val MAX_CARDS = 5000
private const val MAX_TAGS_PER_NOTE = 200
private const val STEP = 1024L
private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** Params each `POST /cards/bulk` action takes besides `type`; anything else is a 422. */
private val ACTION_PARAMS = mapOf(
    "suspend" to emptySet(),
    "unsuspend" to emptySet(),
    "bury" to setOf("until"),
    "unbury" to emptySet(),
    "flag" to setOf("flags"),
    "set_preset" to setOf("preset_id"),
    "add_tags" to setOf("tags"),
    "remove_tags" to setOf("tags"),
    "move" to setOf("to_deck_id", "from_deck_id"),
    "delete" to emptySet(),
)

/** What the bulk calls report (`CardBulkResult`). */
private class Counts(var matched: Int = 0, var changed: Int = 0, var skipped: Int = 0, var ops: Int = 0)

/** The cards a call selected: live cards ascending by id, and the selector ids that were not live. */
private class Selection(val ids: List<String>, val unusable: Int)

/**
 * `bulkCards` and `bulkScheduleCards` (synchronous, at most 5000 cards, one op transaction, ordered by card id).
 * Every emission goes through [OpTx.emit]; with `dry_run` nothing is emitted and the counts say what would happen.
 */
internal class BulkApi(private val db: Database, private val opApply: OpApply, private val clock: () -> Instant) {
    private class Request(val ids: List<String>?, val query: String?, val action: ObjectNode, val dryRun: Boolean)

    suspend fun bulk(call: ApplicationCall) {
        val req = parse(call.jsonBody()) { type, action, e -> validateAction(type, action, e) }
        val type = req.action["type"].asText()
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val nowMs = clock().toEpochMilli()
            val settings = ReviewSettings.load(tx.dsl)
            val sel = select(tx, req, nowMs, settings)
            val c = Counts(matched = sel.ids.size, skipped = sel.unusable)
            val emit: (OpType, ObjectNode) -> Unit = { t, p -> c.ops++; if (!req.dryRun) tx.emit(t, payload(p)) }
            when (type) {
                "suspend" -> perCard(sel, c, emit, OpType.CARD_SUSPENDED) { }
                "unsuspend" -> perCard(sel, c, emit, OpType.CARD_UNSUSPENDED) { }
                "unbury" -> perCard(sel, c, emit, OpType.CARD_UNBURIED) { }
                "delete" -> perCard(sel, c, emit, OpType.CARD_DELETED) { }
                "bury" -> {
                    val until = req.action.get("until")?.let { n -> parseInstant(n, "/action/until", Errors()) }
                        ?: studyDayOf(nowMs, settings.timezone, settings.dayRolloverHour).day.endMs
                    if (until <= nowMs) refuse("/action/until", "out_of_range", "Must be in the future.")
                    perCard(sel, c, emit, OpType.CARD_BURIED) { it.put("until", until) }
                }
                "flag" -> {
                    val flags = req.action["flags"].asInt()
                    perCard(sel, c, emit, OpType.CARD_FLAG_SET) { it.put("flags", flags) }
                }
                "set_preset" -> {
                    val preset = req.action["preset_id"].asText()
                    if (!tx.dsl.fetchExists(tx.dsl.selectOne().from("app.fsrs_params").where("id = ?::uuid and deleted_at is null", preset))) {
                        refuse("/action/preset_id", "invalid_value", "No such live preset.")
                    }
                    perCard(sel, c, emit, OpType.CARD_PRESET_SET) { it.put("preset_id", preset) }
                }
                "add_tags", "remove_tags" -> tags(tx, sel, c, emit, req.action, add = type == "add_tags")
                "move" -> move(tx, sel, c, emit, req.action)
                else -> error("validated action $type")
            }
            Reply(HttpStatusCode.OK, json.valueToTree<JsonNode>(result(c, req.dryRun)), tx.lastSeq.takeIf { !req.dryRun && c.ops > 0 })
        }
        call.send(reply)
    }

    suspend fun schedule(call: ApplicationCall) {
        val req = parse(call.jsonBody()) { type, action, e ->
            e.unknownKeys(action, setOf("type", "due_at"))
            when (type) {
                "reschedule" -> if (!action.has("due_at")) e.add("/action/due_at", "required", "due_at is required.") else parseInstant(action["due_at"], "/action/due_at", e)
                "reset" -> if (action.has("due_at")) e.add("/action/due_at", "unknown_field", "reset takes no parameters.")
                else -> e.add("/action/type", "invalid_value", "Must be reschedule or reset.")
            }
        }
        val type = req.action["type"].asText()
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val nowMs = clock().toEpochMilli()
            val settings = ReviewSettings.load(tx.dsl)
            val sel = select(tx, req, nowMs, settings)
            val c = Counts(matched = sel.ids.size, skipped = sel.unusable)
            val states = if (sel.ids.isEmpty()) emptyMap() else tx.dsl.fetch("select id::text, state from app.card where id = any(?::uuid[])", sel.ids.toTypedArray())
                .associate { it.get(0, String::class.java) to it.get(1, String::class.java) }
            val offset = settings.timezone.rules.getOffset(Instant.ofEpochMilli(nowMs)).totalSeconds / 60
            val dueMs = if (type == "reschedule") parseInstant(req.action["due_at"], "/action/due_at", Errors()) else null
            if (type == "reschedule") {
                val fresh = sel.ids.count { states[it] == "new" }
                if (fresh > 0) refuse("/action/type", "invalid_state", "$fresh matched card(s) are new and have no memory state to keep.")
            }
            for (id in sel.ids) {
                if (type == "reset" && states[id] == "new") {
                    c.skipped++
                    continue
                }
                c.changed++
                c.ops++
                if (req.dryRun) continue
                ReviewOps.logReview(
                    tx,
                    ReviewEntry(
                        id = Uuid7.generate().toString(), cardId = id, reviewedAtMs = nowMs, utcOffsetMin = offset, reviewType = "manual",
                        manualAction = if (type == "reschedule") "set_due" else "reset", manualDueAtMs = dueMs,
                    ),
                )
            }
            Reply(HttpStatusCode.OK, json.valueToTree<JsonNode>(result(c, req.dryRun)), tx.lastSeq.takeIf { !req.dryRun && c.ops > 0 })
        }
        call.send(reply)
    }

    // ---------------------------------------------------------------- actions

    private fun perCard(sel: Selection, c: Counts, emit: (OpType, ObjectNode) -> Unit, type: OpType, extra: (ObjectNode) -> Unit) {
        for (id in sel.ids) {
            emit(type, json.createObjectNode().put("id", id).also(extra))
            c.changed++
        }
    }

    /** One `note.updated` per distinct note of the matched cards, in card-id order of first appearance. */
    private fun tags(tx: OpTx, sel: Selection, c: Counts, emit: (OpType, ObjectNode) -> Unit, action: ObjectNode, add: Boolean) {
        val e = Errors()
        val wanted = action["tags"].let { e.tagList(it, "/action/tags", 1, 100) }
        e.throwIfAny()
        val change = checkNotNull(wanted).toSet()
        val plan = mutableListOf<Pair<String, List<String>>>()
        var tooMany = 0
        for ((noteId, current) in noteTags(tx, sel.ids)) {
            val before = current.toSortedSet(CodePointOrder)
            val next = (if (add) before + change else before - change).toSortedSet(CodePointOrder)
            if (next.size > MAX_TAGS_PER_NOTE) tooMany++
            if (next == before) c.skipped++ else plan += noteId to next.toList()
        }
        if (tooMany > 0) refuse("/action/tags", "out_of_range", "A note has at most $MAX_TAGS_PER_NOTE tags ($tooMany would exceed); nothing applied.")
        for ((noteId, next) in plan) {
            val set = json.createObjectNode()
            set.set<JsonNode>("tags", json.createArrayNode().also { a -> next.forEach(a::add) })
            emit(OpType.NOTE_UPDATED, json.createObjectNode().put("id", noteId).also { it.set<JsonNode>("set", set) })
            c.changed++
        }
    }

    private fun move(tx: OpTx, sel: Selection, c: Counts, emit: (OpType, ObjectNode) -> Unit, action: ObjectNode) {
        val to = action["to_deck_id"].asText()
        val from = action.get("from_deck_id")?.asText()
        for (deck in listOfNotNull(to, from)) {
            val type = tx.dsl.fetchValue("select type from app.deck where id = ?::uuid and deleted_at is null", deck) as String?
            if (type != "user") throw ApiException(HttpStatusCode.Conflict, "deck_read_only", "Deck is read-only", "Deck $deck is not a live user deck.")
        }
        val views = DeckViews(tx.dsl)
        var next = views.maxPosition(to)
        val rows = tx.dsl.fetch(
            "select c.id::text, n.id::text, n.entry_seq from app.card c join app.note n on n.id = c.note_id where c.id = any(?::uuid[])",
            sel.ids.toTypedArray(),
        ).associateBy { it.get(0, String::class.java) }
        val notes = LinkedHashMap<String, Long?>()
        for (id in sel.ids) rows[id]?.let { notes.putIfAbsent(it.get(1, String::class.java), it.get(2, Long::class.javaObjectType)) }
        for ((noteId, entrySeq) in notes) {
            val key = entrySeq?.let { "e:$it" } ?: "n:$noteId"
            val position = views.livePosition(to, key) ?: (next + STEP).also { next = it }
            emit(OpType.DECK_ITEM_PUT, json.createObjectNode().put("deck_id", to).put("item_key", key).put("position", position))
            if (from != null && from != to) {
                views.liveKeysOf(from, key).forEach {
                    emit(OpType.DECK_ITEM_REMOVED, json.createObjectNode().put("deck_id", from).put("item_key", it))
                }
            }
            c.changed++
        }
    }

    /** `note id -> tags` for the notes of [cardIds], in the order of the first card of each. */
    private fun noteTags(tx: OpTx, cardIds: List<String>): LinkedHashMap<String, List<String>> {
        val rows = tx.dsl.fetch(
            "select c.id::text, n.id::text, to_jsonb(n.tags)::text from app.card c join app.note n on n.id = c.note_id where c.id = any(?::uuid[])",
            cardIds.toTypedArray(),
        ).associateBy { it.get(0, String::class.java) }
        val out = LinkedHashMap<String, List<String>>()
        for (id in cardIds) {
            val r = rows[id] ?: continue
            out.getOrPut(r.get(1, String::class.java)) { json.readTree(r.get(2, String::class.java)).map(JsonNode::asText) }
        }
        return out
    }

    // ---------------------------------------------------------------- selection and parsing

    private fun select(tx: OpTx, req: Request, nowMs: Long, settings: ReviewSettings): Selection {
        val dsl = tx.dsl
        if (req.ids != null) {
            val live = dsl.fetch(
                "select c.id::text from app.card c join app.note n on n.id = c.note_id " +
                    "where c.id = any(?::uuid[]) and c.deleted_at is null and c.merged_into_id is null and n.deleted_at is null order by c.id",
                req.ids.toTypedArray(),
            ).map { it.get(0, String::class.java) }
            return Selection(live, req.ids.size - live.size)
        }
        val compiled = CardSearch.compile(dsl, req.query, nowMs, settings)
        val ids = CardSearch.ids(dsl, compiled, MAX_CARDS + 1)
        if (ids.size > MAX_CARDS) {
            throw ApiException(
                HttpStatusCode.UnprocessableEntity, "bulk_too_large", "Too many cards",
                "The query matches more than $MAX_CARDS cards; nothing was applied.",
                mapOf("errors" to listOf(mapOf("pointer" to "/query", "code" to "bulk_too_large", "detail" to "More than $MAX_CARDS cards."))),
            )
        }
        return Selection(ids, 0)
    }

    private fun parse(body: ObjectNode, validate: (String, ObjectNode, Errors) -> Unit): Request {
        val e = Errors()
        e.unknownKeys(body, setOf("card_ids", "query", "action", "dry_run"))
        val hasIds = body.has("card_ids")
        val hasQuery = body.has("query")
        if (hasIds == hasQuery) e.add("", "invalid_value", "Exactly one of card_ids and query.")
        var ids: List<String>? = null
        if (hasIds) {
            val n = body["card_ids"]
            if (!n.isArray || n.size() !in 1..MAX_CARDS) {
                e.add("/card_ids", "out_of_range", "An array of 1 to $MAX_CARDS ids.")
            } else {
                val list = n.mapIndexedNotNull { i, v -> e.uuidValue(v, "/card_ids/$i") }
                if (list.size == n.size() && list.toSet().size != list.size) e.add("/card_ids", "invalid_value", "Ids must be unique.")
                ids = list
            }
        }
        val query = if (hasQuery) e.string(body, "query", 1, 2000) else null
        val dry = body.get("dry_run")?.let { if (it.isBoolean) it.asBoolean() else { e.add("/dry_run", "invalid_type", "Must be a boolean."); false } } ?: false
        val action = body.get("action")
        var type: String? = null
        if (action == null) {
            e.add("/action", "required", "action is required.")
        } else if (action !is ObjectNode) {
            e.add("/action", "invalid_type", "Must be an object.")
        } else {
            type = e.enum(action, "type", ACTION_PARAMS.keys + setOf("reschedule", "reset"), required = true)
            if (type != null) validate(type, action, e)
        }
        e.throwIfAny()
        return Request(ids, query, action as ObjectNode, dry)
    }

    private fun validateAction(type: String, action: ObjectNode, e: Errors) {
        val allowed = ACTION_PARAMS[type]
        if (allowed == null) {
            e.add("/action/type", "invalid_value", "Not an action of this operation.")
            return
        }
        for (k in action.fieldNames().asSequence().filter { it != "type" && it !in allowed }) {
            e.add("/action/$k", "unknown_field", "$k does not apply to $type.")
        }
        fun required(key: String): JsonNode? = action.get(key).also { if (it == null) e.add("/action/$key", "required", "$key is required.") }
        when (type) {
            "bury" -> action.get("until")?.let { parseInstant(it, "/action/until", e) }
            "flag" -> required("flags")?.let {
                if (!it.isIntegralNumber || it.asInt() !in 0..7) e.add("/action/flags", "out_of_range", "Must be 0 to 7.")
            }
            "set_preset" -> required("preset_id")?.let { if (!it.isTextual || !UUID_RE.matches(it.asText())) e.add("/action/preset_id", "invalid_value", "Must be a lowercase UUID.") }
            "add_tags", "remove_tags" -> required("tags")?.let { e.tagList(it, "/action/tags", 1, 100) }
            "move" -> {
                required("to_deck_id")?.let { if (!it.isTextual || !UUID_RE.matches(it.asText())) e.add("/action/to_deck_id", "invalid_value", "Must be a lowercase UUID.") }
                action.get("from_deck_id")?.let { if (!it.isTextual || !UUID_RE.matches(it.asText())) e.add("/action/from_deck_id", "invalid_value", "Must be a lowercase UUID.") }
            }
        }
    }

    private fun result(c: Counts, dry: Boolean) = linkedMapOf(
        "matched" to c.matched, "changed" to c.changed, "skipped" to c.skipped,
        "ops_emitted" to if (dry) 0 else c.ops, "dry_run" to dry,
    )

    private fun refuse(pointer: String, code: String, detail: String): Nothing {
        Errors().apply { add(pointer, code, detail) }.throwIfAny()
        error("unreachable")
    }
}
