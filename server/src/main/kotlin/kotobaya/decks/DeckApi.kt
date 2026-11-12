package kotobaya.decks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.sync.OpType
import kotobaya.jobs.JobRepo
import kotobaya.notes.Errors
import kotobaya.notes.Reply
import kotobaya.notes.callerTokenId
import kotobaya.notes.gone
import kotobaya.notes.includeDeleted
import kotobaya.notes.jsonBody
import kotobaya.notes.pathId
import kotobaya.notes.payload
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import kotobaya.sync.OpTx

internal val DECK_TYPES = setOf("user", "media", "list", "smart")
private val SETTING_KEY = Regex("^[a-z][a-z0-9_]*$")
private val LIST_KEY = Regex("^[a-z0-9][a-z0-9-]*$")

/** `listDeckLists`, `listDecks`, `createDeck`, `getDeck`, `updateDeck`, `deleteDeck`. */
internal class DeckApi(private val db: Database, private val opApply: OpApply) {
    suspend fun lists(call: ApplicationCall) {
        val items = db.tx { dsl ->
            val views = DeckViews(dsl)
            DeckLists.catalogue(dsl).map {
                linkedMapOf("list_key" to it.key, "title" to it.title, "item_count" to it.itemCount, "deck_id" to views.liveListDeck(it.key)?.get("id")?.asText())
            }
        }
        call.respondJson(mapOf("items" to items))
    }

    suspend fun list(call: ApplicationCall) {
        val deleted = call.includeDeleted()
        val type = call.request.queryParameters["type"]
        if (type != null && type !in DECK_TYPES) {
            Errors().apply { add("/type", "invalid_value", "Must be one of ${DECK_TYPES.joinToString(", ")}.") }.throwIfAny()
        }
        call.respondJson(mapOf("items" to db.tx { DeckViews(it).decks(type, deleted) }))
    }

    suspend fun get(call: ApplicationCall) {
        val id = call.pathId("deck_id")
        val deleted = call.includeDeleted()
        val row = db.tx { DeckViews(it).deck(id) } ?: throw Problems.notFound("No such deck.")
        if (!deleted && !row["deleted_at"].isNull) throw gone("deck", row["deleted_at"].asText())
        call.respondJson(row)
    }

    /**
     * `createDeck`. Idempotent on `id`: a deck with that id (live or deleted) is returned with `200` and nothing is
     * emitted, even when the retry names a different `type` or other properties. openapi defines no conflict code for
     * that case, so it is deliberately not a 409; the stored deck wins.
     */
    suspend fun create(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("id", "type", "name", "priority", "enabled", "new_cards_per_day", "reviews_per_day", "settings", "query", "list_key"))
        val id = e.uuid(body, "id")
        val type = e.enum(body, "type", setOf("user", "smart", "list"), required = true)
        val name = e.string(body, "name", 1, 200, required = type == "user" || type == "smart")
        val priority = body.get("priority")?.let { e.int(it, "/priority", -1_000_000, 1_000_000) }
        val enabled = body.get("enabled")?.let { if (it.isBoolean) it.asBoolean() else { e.add("/enabled", "invalid_type", "Must be a boolean."); null } }
        val newPerDay = body.get("new_cards_per_day")?.let { e.nullableInt(it, "/new_cards_per_day", 9999) }
        val reviewsPerDay = body.get("reviews_per_day")?.let { e.nullableInt(it, "/reviews_per_day", 99999) }
        val settings = body.get("settings")?.let { e.settings(it, create = true) }
        var query: String? = null
        var listKey: String? = null
        when (type) {
            "smart" -> {
                query = e.string(body, "query", 1, 2000, required = true)
                if (body.has("list_key")) e.add("/list_key", "unexpected", "list_key belongs to list decks.")
            }
            "list" -> {
                listKey = body.get("list_key")?.let { v ->
                    if (v.isTextual && LIST_KEY.matches(v.asText()) && v.asText().length <= 200) v.asText()
                    else { e.add("/list_key", "invalid_value", "A lowercase slug."); null }
                } ?: run { if (!body.has("list_key")) e.add("/list_key", "required", "list_key is required."); null }
                if (body.has("query")) e.add("/query", "unexpected", "query belongs to smart decks.")
            }
            "user" -> {
                if (body.has("query")) e.add("/query", "unexpected", "query belongs to smart decks.")
                if (body.has("list_key")) e.add("/list_key", "unexpected", "list_key belongs to list decks.")
            }
        }
        e.throwIfAny()
        if (query != null) SmartDecks.parse(query)

        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val views = DeckViews(tx.dsl)
            if (id != null) views.deck(id)?.let { return@rest Reply(HttpStatusCode.OK, it) }
            var deckName = name
            if (type == "list") {
                views.liveListDeck(listKey ?: error("list_key checked"))?.let { return@rest Reply(HttpStatusCode.OK, it) }
                val entry = DeckLists.catalogue(tx.dsl).firstOrNull { it.key == listKey }
                if (entry == null) {
                    Errors().apply { add("/list_key", "invalid_value", "$listKey is not in GET /deck-lists.") }.throwIfAny()
                }
                deckName = deckName ?: entry?.title
            }
            val newId = id ?: Uuid7.generate().toString()
            val p = json.createObjectNode().put("id", newId).put("name", deckName).put("type", type)
            priority?.let { p.put("priority", it) }
            enabled?.let { p.put("enabled", it) }
            if (body.has("new_cards_per_day")) p.set<JsonNode>("new_cards_per_day", json.nodeFactory.let { f -> newPerDay?.let { f.numberNode(it) } ?: f.nullNode() })
            if (body.has("reviews_per_day")) p.set<JsonNode>("reviews_per_day", json.nodeFactory.let { f -> reviewsPerDay?.let { f.numberNode(it) } ?: f.nullNode() })
            settings?.takeIf { !it.isEmpty }?.let { p.set<JsonNode>("settings", it) }
            query?.let { p.put("query", it) }
            listKey?.let { p.put("list_key", it) }
            tx.emit(OpType.DECK_CREATED, payload(p))
            val seq = tx.lastSeq
            if (type == "list") {
                JobRepo.enqueue(tx.dsl, "deck-build", json.createObjectNode().put("deck_id", newId), dedupeKey = "deck-build:$newId", createdByTokenId = tx.tokenId)
            }
            Reply(HttpStatusCode.Created, views.deck(newId) ?: error("deck $newId missing after deck.created"), seq)
        }
        call.send(reply)
    }

    suspend fun update(call: ApplicationCall) {
        val id = call.pathId("deck_id")
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(
            body, setOf("name", "priority", "enabled", "new_cards_per_day", "reviews_per_day", "query", "settings"),
            immutable = setOf("id", "type", "media_id", "list_key"),
        )
        if (body.isEmpty) e.add("", "required", "At least one property is required.")
        val set = json.createObjectNode()
        e.string(body, "name", 1, 200)?.let { set.put("name", it) }
        body.get("priority")?.let { v -> e.int(v, "/priority", -1_000_000, 1_000_000)?.let { set.put("priority", it) } }
        body.get("enabled")?.let { v -> if (v.isBoolean) set.put("enabled", v.asBoolean()) else e.add("/enabled", "invalid_type", "Must be a boolean.") }
        for ((k, max) in listOf("new_cards_per_day" to 9999, "reviews_per_day" to 99999)) {
            val v = body.get(k) ?: continue
            if (v.isNull) set.putNull(k) else e.nullableInt(v, "/$k", max)?.let { set.put(k, it) }
        }
        val query = e.string(body, "query", 1, 2000)
        query?.let { set.put("query", it) }
        body.get("settings")?.let { v ->
            e.settings(v, create = false)?.let { s ->
                if (s.isEmpty) e.add("/settings", "required", "At least one settings key is required.") else set.set<JsonNode>("settings", s)
            }
        }
        e.throwIfAny()
        if (query != null) SmartDecks.parse(query)

        val reply = opApply.rest(call.callerTokenId(), { it.removePrefix("/set") }) { tx ->
            val row = DeckViews(tx.dsl).deck(id) ?: throw Problems.notFound("No such deck.")
            if (!row["deleted_at"].isNull) throw gone("deck", row["deleted_at"].asText())
            if (query != null && row["type"].asText() != "smart") {
                Errors().apply { add("/query", "invalid_value", "Only smart decks have a query.") }.throwIfAny()
            }
            val p = json.createObjectNode().put("id", id)
            p.set<JsonNode>("set", set)
            tx.emit(OpType.DECK_UPDATED, payload(p))
            Reply(HttpStatusCode.OK, DeckViews(tx.dsl).deck(id) ?: error("deck $id vanished"), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun delete(call: ApplicationCall) {
        val id = call.pathId("deck_id")
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val row = DeckViews(tx.dsl).deck(id) ?: throw Problems.notFound("No such deck.")
            if (row["type"].asText() == "media") {
                throw ApiException(HttpStatusCode.Conflict, "deck_read_only", "Deck is read-only", "Delete the media (DELETE /media/{media_id}); that tombstones its deck.")
            }
            if (!row["deleted_at"].isNull) return@rest Reply(HttpStatusCode.NoContent, null)
            tx.emit(OpType.DECK_DELETED, payload(json.createObjectNode().put("id", id)))
            Reply(HttpStatusCode.NoContent, null, tx.lastSeq)
        }
        call.send(reply)
    }
}

/** An integer property in `[min, max]`; out of range is `invalid_value` (the same code the op layer uses for int4). */
internal fun Errors.int(v: JsonNode, at: String, min: Int, max: Int): Int? {
    if (!v.isIntegralNumber || !v.canConvertToLong()) {
        add(at, "invalid_type", "Must be an integer.")
        return null
    }
    val n = v.asLong()
    if (n < min || n > max) {
        add(at, "invalid_value", "Must be between $min and $max.")
        return null
    }
    return n.toInt()
}

/** `null` is allowed (no per-deck limit); otherwise an integer in `0..max`. Returns null for both null and an error. */
internal fun Errors.nullableInt(v: JsonNode, at: String, max: Int): Int? = if (v.isNull) null else int(v, at, 0, max)

/**
 * A deck `settings` object: keys are lowercase identifiers, known keys are checked. On create a null value is
 * dropped; on update it is kept (it removes the key).
 */
internal fun Errors.settings(v: JsonNode, create: Boolean): ObjectNode? {
    if (v !is ObjectNode) {
        add("/settings", "invalid_type", "Must be an object.")
        return null
    }
    val out = json.createObjectNode()
    for ((k, x) in v.fields().asSequence().toList()) {
        val at = "/settings/$k"
        if (!SETTING_KEY.matches(k) || k.length > 64) {
            add(at, "invalid_value", "A settings key is a lowercase identifier of at most 64 characters.")
            continue
        }
        if (x.isNull) {
            if (!create) out.putNull(k)
            continue
        }
        val ok = when (k) {
            "order" -> x.isTextual && x.asText() in setOf("deck_frequency", "global_frequency", "position", "chronological")
            "add_kanji" -> x.isTextual && x.asText() in setOf("off", "before", "alongside")
            "card_kinds" -> x.isArray && x.all { it.isTextual && SETTING_KEY.matches(it.asText()) }
            else -> true
        }
        if (!ok) add(at, "invalid_value", "Not a valid value for $k.") else out.set<JsonNode>(k, x)
    }
    return out
}

/** Shared by the item API. */
internal fun OpTx.liveDeckOrThrow(id: String): ObjectNode {
    val row = DeckViews(dsl).deck(id) ?: throw Problems.notFound("No such deck.")
    if (!row["deleted_at"].isNull) throw gone("deck", row["deleted_at"].asText())
    return row
}
