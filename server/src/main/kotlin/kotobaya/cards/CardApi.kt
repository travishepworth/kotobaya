package kotobaya.cards

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.sync.OpType
import kotobaya.notes.Errors
import kotobaya.notes.NoteViews
import kotobaya.notes.Reply
import kotobaya.notes.callerTokenId
import kotobaya.notes.gone
import kotobaya.notes.includeDeleted
import kotobaya.notes.jsonBody
import kotobaya.notes.pathId
import kotobaya.notes.payload
import kotobaya.notes.resolve
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.review.ReviewSettings
import kotobaya.review.ReviewViews
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64

private val SORTS = setOf(
    "created_at", "due_at", "updated_at", "lapses", "difficulty", "stability", "last_review_at",
)
private val PATCH_KEYS = setOf("suspended", "buried_until", "flags", "preset_id")

/** `listCards`, `createCard`, `getCard`, `updateCard`, `deleteCard`, `listCardReviews`. */
internal class CardApi(
    private val db: Database,
    private val opApply: OpApply,
    private val clock: () -> Instant,
    private val basePath: String,
) {
    suspend fun list(call: ApplicationCall) {
        val q = call.request.queryParameters
        val e = Errors()
        val query = q["query"]?.also { if (it.length > 2000) e.add("/query", "out_of_range", "At most 2000 characters.") }
        val sortParam = q["sort"] ?: "-created_at"
        val sortKey = sortParam.removePrefix("-")
        if (sortKey !in SORTS || sortParam.length > sortKey.length + 1) {
            e.add("/sort", "invalid_value", "Must be one of ${SORTS.joinToString(", ")}, optionally with a leading -.")
        }
        val limit = pageLimit(q["limit"], e)
        val hash = "${query ?: ""}|$sortParam".hashCode().toString(16)
        val offset = pageOffset(q["cursor"], hash, e)
        e.throwIfAny()

        val nowMs = clock().toEpochMilli()
        val body = db.tx { dsl ->
            val settings = ReviewSettings.load(dsl)
            val compiled = CardSearch.compile(dsl, query, nowMs, settings)
            val sql = compiled.select()
            val dir = if (sortParam.startsWith("-")) "DESC" else "ASC"
            val ids = dsl.fetch(
                "${sql.sql} ORDER BY c.$sortKey $dir NULLS LAST, c.id LIMIT ${limit + 1} OFFSET $offset",
                *sql.params.toTypedArray(),
            ).map { it.get(0).toString() }
            val views = ReviewViews(dsl, settings.leechThreshold).cards(ids.take(limit))
            linkedMapOf(
                "items" to ids.take(limit).mapNotNull { views[it]?.json },
                "next_cursor" to if (ids.size > limit) cursor(offset + limit, hash) else null,
            )
        }
        call.respondJson(body)
    }

    suspend fun get(call: ApplicationCall) {
        val id = call.pathId("card_id")
        val includeDeleted = call.includeDeleted()
        val (card, location) = db.tx { dsl ->
            val views = views(dsl)
            val survivor = views.survivorOf(id) ?: throw Problems.notFound("No such card.")
            val card = views.cards(listOf(survivor))[survivor] ?: throw Problems.notFound("No such card.")
            if (!includeDeleted) goneIfDeleted(dsl, card.json)
            card.json to if (survivor != id) "$basePath/cards/$survivor" else null
        }
        call.send(Reply(HttpStatusCode.OK, card, contentLocation = location))
    }

    suspend fun create(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("id", "note_id", "kind"))
        val id = e.uuid(body, "id")
        val noteId = e.uuid(body, "note_id", required = true)
        val kind = e.string(body, "kind", 1, 64, required = true)?.also {
            if (!kotobaya.notes.KEY_RE.matches(it)) e.add("/kind", "invalid_value", "A kind is a lowercase identifier.")
        }
        e.throwIfAny()

        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val dsl = tx.dsl
            val note = NoteViews(dsl).resolve(checkNotNull(noteId)) ?: throw Problems.notFound("No such note.")
            if (!note["deleted_at"].isNull) throw gone("note", note["deleted_at"].asText())
            val survivorNote = note["id"].asText()
            val views = views(dsl)

            fun existing(cardId: String): Reply? {
                val card = views.cards(listOf(cardId))[cardId] ?: return null
                return Reply(HttpStatusCode.OK, card.json)
            }
            if (id != null) {
                val row = dsl.fetchOne("select note_id::text, kind from app.card where id = ?::uuid", id)
                if (row != null) {
                    if (row.get(0, String::class.java) != survivorNote && row.get(0, String::class.java) != noteId || row.get(1, String::class.java) != kind) {
                        throw kotobaya.server.ApiException(HttpStatusCode.Conflict, "conflict", "Conflict", "A card with this id exists for another note or kind.")
                    }
                    return@rest checkNotNull(existing(id))
                }
            }
            val same = dsl.fetchValue(
                "select id::text from app.card where note_id = ?::uuid and kind = ? and deleted_at is null and merged_into_id is null",
                survivorNote, kind,
            ) as String?
            if (same != null) return@rest checkNotNull(existing(same))

            val templates = dsl.fetchValue("select templates::text from app.note_type where id = ?::uuid", note["note_type_id"].asText()) as String?
            if (templates == null || !json.readTree(templates).has(kind)) {
                Errors().apply { add("/kind", "invalid_value", "The note type has no card kind $kind.") }.throwIfAny()
            }
            val newId = id ?: Uuid7.generate().toString()
            tx.emit(OpType.CARD_CREATED, payload(json.createObjectNode().put("id", newId).put("note_id", survivorNote).put("kind", kind)))
            val shown = views.survivorOf(newId) ?: newId
            Reply(HttpStatusCode.Created, views.cards(listOf(shown))[shown]?.json ?: error("card $newId missing after card.created"), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun update(call: ApplicationCall) {
        val id = call.pathId("card_id")
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, PATCH_KEYS)
        if (body.isEmpty) e.add("", "required", "At least one property is required.")
        val suspended = body.get("suspended")?.let { if (it.isBoolean) it.asBoolean() else { e.add("/suspended", "invalid_type", "Must be a boolean."); null } }
        val buried = body.get("buried_until")
        var buriedMs: Long? = null
        if (buried != null && !buried.isNull) buriedMs = parseInstant(buried, "/buried_until", e)
        val flags = body.get("flags")?.let { e.long(it, "/flags", 0, 7) }
        val presetId = e.uuid(body, "preset_id")
        e.throwIfAny()

        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val dsl = tx.dsl
            val views = views(dsl)
            val survivor = views.survivorOf(id) ?: throw Problems.notFound("No such card.")
            val card = views.cards(listOf(survivor))[survivor] ?: throw Problems.notFound("No such card.")
            goneIfDeleted(dsl, card.json)
            if (presetId != null && !dsl.fetchExists(dsl.selectOne().from("app.fsrs_params").where("id = ?::uuid and deleted_at is null", presetId))) {
                Errors().apply { add("/preset_id", "invalid_value", "No such live preset.") }.throwIfAny()
            }
            if (suspended != null) {
                val type = if (suspended) OpType.CARD_SUSPENDED else OpType.CARD_UNSUSPENDED
                tx.emit(type, payload(json.createObjectNode().put("id", survivor)))
            }
            if (body.has("buried_until")) {
                if (buriedMs != null) {
                    tx.emit(OpType.CARD_BURIED, payload(json.createObjectNode().put("id", survivor).put("until", buriedMs)))
                } else {
                    tx.emit(OpType.CARD_UNBURIED, payload(json.createObjectNode().put("id", survivor)))
                }
            }
            if (flags != null) tx.emit(OpType.CARD_FLAG_SET, payload(json.createObjectNode().put("id", survivor).put("flags", flags.toInt())))
            if (presetId != null) tx.emit(OpType.CARD_PRESET_SET, payload(json.createObjectNode().put("id", survivor).put("preset_id", presetId)))
            Reply(
                HttpStatusCode.OK, views.cards(listOf(survivor))[survivor]?.json ?: error("card $survivor vanished"), tx.lastSeq,
                if (survivor != id) "$basePath/cards/$survivor" else null,
            )
        }
        call.send(reply)
    }

    suspend fun delete(call: ApplicationCall) {
        val id = call.pathId("card_id")
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val row = tx.dsl.fetchOne("select deleted_at is not null, merged_into_id is not null from app.card where id = ?::uuid", id)
                ?: throw Problems.notFound("No such card.")
            if (row.get(0, Boolean::class.java) || row.get(1, Boolean::class.java)) return@rest Reply(HttpStatusCode.NoContent, null)
            tx.emit(OpType.CARD_DELETED, payload(json.createObjectNode().put("id", id)))
            Reply(HttpStatusCode.NoContent, null, tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun reviews(call: ApplicationCall) {
        val id = call.pathId("card_id")
        val q = call.request.queryParameters
        val e = Errors()
        val limit = pageLimit(q["limit"], e)
        val hash = "reviews|$id".hashCode().toString(16)
        val offset = pageOffset(q["cursor"], hash, e)
        e.throwIfAny()
        val body = db.tx { dsl ->
            val survivor = ReviewViews(dsl, 0).survivorOf(id) ?: throw Problems.notFound("No such card.")
            val rows = dsl.fetch(
                "with recursive set(id) as (select ?::uuid union select c.id from app.card c join set s on c.merged_into_id = s.id) " +
                    "select jsonb_build_object('id', r.id, 'card_id', r.card_id, 'device_id', r.device_id, 'hlc', r.hlc::text, " +
                    "'reviewed_at', ${ts("r.reviewed_at")}, 'utc_offset_min', r.utc_offset_min, 'review_type', r.review_type, " +
                    "'rating', r.rating, 'button', r.button, 'duration_ms', r.duration_ms, 'manual_action', r.manual_action, " +
                    "'manual_due_at', ${ts("r.manual_due_at")}, 'sentence_id', r.sentence_id, 'import_source', r.import_source, " +
                    "'undone_at', ${ts("r.undone_at")})::text from app.review_log r where r.card_id in (select id from set) " +
                    "order by r.reviewed_at desc, r.hlc desc, r.id desc limit ${limit + 1} offset $offset",
                survivor,
            ).map { json.readTree(it.get(0, String::class.java)) as ObjectNode }
            linkedMapOf("items" to rows.take(limit), "next_cursor" to if (rows.size > limit) cursor(offset + limit, hash) else null)
        }
        call.respondJson(body)
    }

    private fun views(dsl: DSLContext) = ReviewViews(dsl, ReviewSettings.load(dsl).leechThreshold)

    /** 410 for a deleted card or a card of a deleted note. */
    private fun goneIfDeleted(dsl: DSLContext, card: ObjectNode) {
        if (!card["deleted_at"].isNull) throw gone("card", card["deleted_at"].asText())
        val noteDeleted = dsl.fetchValue(
            "select ${ts("deleted_at")} from app.note where id = ?::uuid", card["note_id"].asText(),
        ) as String?
        if (noteDeleted != null) throw gone("note", noteDeleted)
    }
}

private fun ts(col: String) = "to_char($col at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')"

internal fun pageLimit(raw: String?, e: Errors): Int =
    raw?.let { v -> v.toIntOrNull()?.takeIf { it in 1..500 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 500."); null } } ?: 50

/** Cursors are opaque: `offset|hash` of the request shape, base64url. A foreign or malformed one is a 422. */
internal fun pageOffset(raw: String?, hash: String, e: Errors): Int {
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

/** An RFC 3339 instant as epoch ms; records an error at [at] otherwise. */
internal fun parseInstant(n: com.fasterxml.jackson.databind.JsonNode, at: String, e: Errors): Long? {
    if (!n.isTextual) {
        e.add(at, "invalid_type", "Must be an RFC 3339 instant.")
        return null
    }
    return try {
        OffsetDateTime.parse(n.asText()).toInstant().toEpochMilli()
    } catch (x: DateTimeParseException) {
        e.add(at, "invalid_value", "Must be an RFC 3339 instant.")
        null
    }
}
