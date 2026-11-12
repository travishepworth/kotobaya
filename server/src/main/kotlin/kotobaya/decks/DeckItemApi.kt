package kotobaya.decks

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotobaya.contracts.sync.OpType
import kotobaya.notes.Errors
import kotobaya.notes.Reply
import kotobaya.notes.callerTokenId
import kotobaya.notes.gone
import kotobaya.notes.jsonBody
import kotobaya.notes.pathId
import kotobaya.notes.payload
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import kotobaya.sync.OpTx
import java.util.Base64

private val ITEM_KEY = Regex("^(e:[1-9][0-9]{0,15}|n:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$")
private const val STEP = 1024L
private const val MAX_POSITION = 9_007_199_254_740_991L

/** `listDeckItems`, `addDeckItems`, `putDeckItem`, `removeDeckItem`. */
internal class DeckItemApi(private val db: Database, private val opApply: OpApply) {
    suspend fun list(call: ApplicationCall) {
        val id = call.pathId("deck_id")
        val q = call.request.queryParameters
        val e = Errors()
        val statuses = q["status"]?.let { v ->
            val all = v.split(',')
            if (all.all { it in STATUSES }) all.toSet() else { e.add("/status", "invalid_value", "Comma-separated: ${STATUSES.joinToString(", ")}."); null }
        }
        val sort = q["sort"]?.let { v -> ItemSort.entries.firstOrNull { it.wire == v } ?: run { e.add("/sort", "invalid_value", "Must be one of ${ItemSort.entries.joinToString(", ") { it.wire }}."); null } }
            ?: ItemSort.POSITION
        val limit = q["limit"]?.let { v -> v.toIntOrNull()?.takeIf { it in 1..500 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 500."); null } } ?: 50
        val hash = ((statuses?.sorted() ?: emptyList<String>()).joinToString(",") + "|" + sort.wire + "|" + id).hashCode().toString(16)
        var offset = 0
        q["cursor"]?.let { c ->
            val parts = runCatching { String(Base64.getUrlDecoder().decode(c), Charsets.UTF_8).split('|') }.getOrNull()
            val n = parts?.getOrNull(0)?.toIntOrNull()
            if (parts == null || parts.size != 2 || n == null || n < 0 || parts[1] != hash) e.add("/cursor", "invalid_value", "Malformed or foreign cursor.") else offset = n
        }
        e.throwIfAny()
        val page = db.tx { dsl ->
            val views = DeckViews(dsl)
            val deck = views.deck(id) ?: throw Problems.notFound("No such deck.")
            if (!deck["deleted_at"].isNull) throw gone("deck", deck["deleted_at"].asText())
            if (deck["type"].asText() == "smart") throw notApplicable()
            val rows = views.items(id, statuses, sort, offset, limit)
            val next = if (rows.size > limit) Base64.getUrlEncoder().withoutPadding().encodeToString("${offset + limit}|$hash".toByteArray()) else null
            mapOf("items" to rows.take(limit), "next_cursor" to next)
        }
        call.respondJson(page)
    }

    suspend fun add(call: ApplicationCall) {
        val id = call.pathId("deck_id")
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("items"))
        val node = body.get("items")
        if (node == null || !node.isArray) e.add("/items", if (node == null) "required" else "invalid_type", "items must be an array.")
        else if (node.isEmpty || node.size() > 1000) e.add("/items", "out_of_range", "Between 1 and 1000 items.")
        e.throwIfAny()
        class Element(val entrySeq: Long?, val noteId: String?, val position: Long?)
        val elements = node!!.mapIndexed { i, it ->
            if (it !is ObjectNode) { e.add("/items/$i", "invalid_type", "Must be an object."); return@mapIndexed null }
            e.unknownKeys(it, setOf("entry_seq", "note_id", "position"))
            val hasE = it.has("entry_seq")
            val hasN = it.has("note_id")
            if (hasE == hasN) { e.add("/items/$i", "invalid_value", "Exactly one of entry_seq and note_id."); return@mapIndexed null }
            val seq = it.get("entry_seq")?.let { v -> if (v.isIntegralNumber && v.canConvertToLong() && v.asLong() >= 1 && v.asText().length <= 16) v.asLong() else { e.add("/items/$i/entry_seq", "invalid_value", "A positive entry_seq."); null } }
            val note = it.get("note_id")?.let { v -> e.uuidValue(v, "/items/$i/note_id") }
            val pos = it.get("position")?.let { v -> if (v.isIntegralNumber && v.canConvertToLong() && v.asLong() in -MAX_POSITION..MAX_POSITION) v.asLong() else { e.add("/items/$i/position", "invalid_value", "An integer within ±$MAX_POSITION."); null } }
            Element(seq, note, pos)
        }
        e.throwIfAny()

        val reply = opApply.rest(call.callerTokenId(), { it }) { tx ->
            requireUserDeck(tx, id)
            val views = DeckViews(tx.dsl)
            // key -> position, the last element of a duplicated key wins (and takes its slot in the order)
            val wanted = LinkedHashMap<String, Long?>()
            elements.forEachIndexed { i, el ->
                el ?: error("validated")
                val key = if (el.entrySeq != null) {
                    if (!views.entryExists(el.entrySeq)) fail("/items/$i/entry_seq", "No such dictionary entry.")
                    "e:${el.entrySeq}"
                } else {
                    val s = views.survivor(el.noteId ?: error("validated")) ?: fail("/items/$i/note_id", "No such note.")
                    if (s.deleted) fail("/items/$i/note_id", "The note is deleted.")
                    if (s.entrySeq != null) "e:${s.entrySeq}" else "n:${s.id}"
                }
                wanted.remove(key)
                wanted[key] = el.position
            }
            var next = views.maxPosition(id)
            val keys = wanted.entries.map { (key, pos) ->
                val position = pos ?: (next + STEP).also { next = it }
                if (position !in -MAX_POSITION..MAX_POSITION) fail("/items", "Position out of range.")
                tx.emit(OpType.DECK_ITEM_PUT, payload(json.createObjectNode().put("deck_id", id).put("item_key", key).put("position", position)))
                key
            }
            Reply(HttpStatusCode.OK, json.createObjectNode().also { o -> o.putArray("items").also { a -> keys.forEach { k -> a.add(views.item(id, k) ?: error("item $k missing")) } } }, tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun put(call: ApplicationCall) {
        val id = call.pathId("deck_id")
        val key = call.itemKey()
        val text = call.receiveText()
        var position: Long? = null
        if (text.isNotBlank()) {
            val body = runCatching { json.readTree(text) }.getOrNull() as? ObjectNode ?: throw Problems.invalidRequest("The body must be a JSON object.")
            val e = Errors()
            e.unknownKeys(body, setOf("position"))
            body.get("position")?.let { v ->
                if (v.isIntegralNumber && v.canConvertToLong() && v.asLong() in -MAX_POSITION..MAX_POSITION) position = v.asLong()
                else e.add("/position", "invalid_value", "An integer within ±$MAX_POSITION.")
            }
            e.throwIfAny()
        }
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            requireUserDeck(tx, id)
            val views = DeckViews(tx.dsl)
            val canonical = if (key.startsWith("e:")) {
                if (!views.entryExists(key.substring(2).toLong())) fail("/item_key", "No such dictionary entry.")
                key
            } else {
                val s = views.survivor(key.substring(2)) ?: fail("/item_key", "No such note.")
                if (s.deleted) fail("/item_key", "The note is deleted.")
                if (s.entrySeq != null) fail("/item_key", "The note has an entry; use the key e:${s.entrySeq}.")
                "n:${s.id}"
            }
            val pos = position ?: views.livePosition(id, canonical) ?: (views.maxPosition(id) + STEP)
            if (pos !in -MAX_POSITION..MAX_POSITION) fail("/position", "Position out of range.")
            tx.emit(OpType.DECK_ITEM_PUT, payload(json.createObjectNode().put("deck_id", id).put("item_key", canonical).put("position", pos)))
            Reply(HttpStatusCode.OK, views.item(id, canonical) ?: error("item $canonical missing"), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun remove(call: ApplicationCall) {
        val id = call.pathId("deck_id")
        val key = call.itemKey()
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            requireUserDeck(tx, id)
            val views = DeckViews(tx.dsl)
            // The identity the user sees (SYNC.md 8.5): resolve a merge loser to its survivor, an `n:` key with an entry to `e:`.
            val identity = if (key.startsWith("e:")) {
                key
            } else {
                val s = views.survivor(key.substring(2))
                when {
                    s == null -> key
                    s.entrySeq != null -> "e:${s.entrySeq}"
                    else -> "n:${s.id}"
                }
            }
            // One removal for every live key of that identity, in one transaction.
            views.liveKeysOf(id, identity).forEach {
                tx.emit(OpType.DECK_ITEM_REMOVED, payload(json.createObjectNode().put("deck_id", id).put("item_key", it)))
            }
            Reply(HttpStatusCode.NoContent, null, tx.lastSeq)
        }
        call.send(reply)
    }

    private fun ApplicationCall.itemKey(): String {
        val key = parameters["item_key"] ?: ""
        if (!ITEM_KEY.matches(key)) fail("/item_key", "Must be e:<entry_seq> or n:<lowercase note uuid>.")
        return key
    }

    /** 404 / 410 / 409 for a deck that cannot take item writes. */
    private fun requireUserDeck(tx: OpTx, id: String) {
        val deck = tx.liveDeckOrThrow(id)
        when (deck["type"].asText()) {
            "smart" -> throw notApplicable()
            "media", "list" -> throw ApiException(HttpStatusCode.Conflict, "deck_read_only", "Deck is read-only", "Items of a ${deck["type"].asText()} deck are server-built.")
        }
    }

    private fun notApplicable() = ApiException(HttpStatusCode.Conflict, "not_applicable", "Not applicable", "A smart deck has no items; use GET /cards?query=.")

    private fun fail(pointer: String, detail: String, code: String = "invalid_value"): Nothing {
        Errors().apply { add(pointer, code, detail) }.throwIfAny()
        error("unreachable")
    }

    private companion object {
        val STATUSES = setOf("new", "learning", "known", "never_forget", "blacklisted", "ignored")
    }
}
