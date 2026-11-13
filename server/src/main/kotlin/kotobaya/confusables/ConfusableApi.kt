package kotobaya.confusables

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.cards.cursor
import kotobaya.cards.pageLimit
import kotobaya.cards.pageOffset
import kotobaya.contracts.sync.OpType
import kotobaya.notes.Errors
import kotobaya.notes.NoteViews
import kotobaya.notes.Reply
import kotobaya.notes.UUID_RE
import kotobaya.notes.callerTokenId
import kotobaya.notes.jsonBody
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import org.jooq.DSLContext

private val STATUSES = setOf("suggested", "confirmed", "rejected")
private val REASONS = setOf("homophone", "look_alike", "meaning")
private val SUMMARY_KEYS = listOf("id", "note_type_id", "type", "entry_seq", "kanji_literal", "grammar_point_id", "fields", "tags", "mnemonic", "source_sentence")

private fun ts(col: String) = "to_char($col at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')"

/**
 * `listWordConfusables`, `listConfusables`, `setConfusable` (T502). Reads resolve merge losers to survivors and hide
 * pairs with a deleted note (SYNC.md 8.5); the one write mints `confusable.confirmed` / `confusable.rejected`.
 */
internal class ConfusableApi(private val db: Database, private val opApply: OpApply) {
    // A pair row with both notes resolved to survivors. Pairs whose notes resolve to one note, or to a deleted one, are hidden.
    private val base =
        "select c.note_a_id::text, c.note_b_id::text, c.reason, c.status, ${ts("c.created_at")}, ${ts("c.updated_at")}, " +
            "sa.id::text, sb.id::text from app.confusable c " +
            "join app.note na on na.id = c.note_a_id join app.note sa on sa.id = coalesce(na.merged_into_id, na.id) " +
            "join app.note nb on nb.id = c.note_b_id join app.note sb on sb.id = coalesce(nb.merged_into_id, nb.id) " +
            "where sa.deleted_at is null and sb.deleted_at is null and sa.id <> sb.id"

    suspend fun listForWord(call: ApplicationCall) {
        val seq = call.parameters["seq"]?.toLongOrNull()?.takeIf { it >= 1 }
        if (seq == null) Errors().apply { add("/seq", "invalid_value", "Must be an integer of at least 1.") }.throwIfAny()
        val e = Errors()
        val status = call.request.queryParameters["status"]?.let { enumValue(e, it) }
        e.throwIfAny()
        val items = db.tx { dsl ->
            val note = dsl.fetchOne("select id::text from app.note where entry_seq = ? and type = 'vocab' and deleted_at is null and merged_into_id is null", seq)
                ?.get(0, String::class.java)
            if (note == null) {
                emptyList()
            } else {
                val statusSql = if (status != null) "c.status = ?" else "c.status in ('suggested', 'confirmed')"
                val args = listOfNotNull(note, note, status)
                val rows = dsl.fetch(
                    "$base and (sa.id = ?::uuid or sb.id = ?::uuid) and $statusSql " +
                        "order by (c.status = 'confirmed') desc, c.updated_at desc, c.note_a_id, c.note_b_id, c.reason",
                    *args.toTypedArray(),
                )
                render(dsl, rows)
            }
        }
        call.respondJson(mapOf("items" to items))
    }

    suspend fun list(call: ApplicationCall) {
        val q = call.request.queryParameters
        val e = Errors()
        val status = q["status"]?.let { enumValue(e, it) }
        val noteId = q["note_id"]?.let { v -> v.takeIf { UUID_RE.matches(it) } ?: run { e.add("/note_id", "invalid_value", "Must be a lowercase UUID."); null } }
        val limit = pageLimit(q["limit"], e)
        val hash = "confusables:${status ?: ""}:${noteId ?: ""}"
        val offset = pageOffset(q["cursor"], hash, e)
        e.throwIfAny()
        val body = db.tx { dsl ->
            val conds = StringBuilder()
            val args = mutableListOf<Any>()
            if (status != null) { conds.append(" and c.status = ?"); args += status }
            if (noteId != null) { conds.append(" and (sa.id = ?::uuid or sb.id = ?::uuid)"); args += noteId; args += noteId }
            args += limit + 1
            args += offset
            val rows = dsl.fetch("$base$conds order by c.updated_at desc, c.note_a_id, c.note_b_id, c.reason limit ? offset ?", *args.toTypedArray())
            linkedMapOf(
                "items" to render(dsl, rows.take(limit)),
                "next_cursor" to if (rows.size > limit) cursor(offset + limit, hash) else null,
            )
        }
        call.respondJson(body)
    }

    suspend fun set(call: ApplicationCall) {
        val a = call.parameters["note_a_id"].orEmpty()
        val b = call.parameters["note_b_id"].orEmpty()
        val reason = call.parameters["reason"].orEmpty()
        val body = call.jsonBody()
        val e = Errors()
        if (!UUID_RE.matches(a)) e.add("/note_a_id", "invalid_value", "Must be a lowercase UUID.")
        if (!UUID_RE.matches(b)) e.add("/note_b_id", "invalid_value", "Must be a lowercase UUID.")
        if (reason !in REASONS) e.add("/reason", "invalid_value", "Must be one of ${REASONS.joinToString()}.")
        e.unknownKeys(body, setOf("status"))
        val status = e.enum(body, "status", setOf("confirmed", "rejected"), required = true)
        e.throwIfAny()
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val ra = survivor(tx.dsl, a) ?: throw notFound(a)
            val rb = survivor(tx.dsl, b) ?: throw notFound(b)
            if (ra == rb) Errors().apply { add("/note_b_id", "invalid_value", "Both notes are the same note.") }.throwIfAny()
            val (x, y) = if (ra < rb) ra to rb else rb to ra
            val type = if (status == "confirmed") OpType.CONFUSABLE_CONFIRMED else OpType.CONFUSABLE_REJECTED
            tx.emit(type, pairPayload(x, y, reason))
            val row = tx.dsl.fetch("$base and c.note_a_id = ?::uuid and c.note_b_id = ?::uuid and c.reason = ?", x, y, reason).single()
            Reply(HttpStatusCode.OK, json.valueToTree(render(tx.dsl, listOf(row)).single()), tx.lastSeq)
        }
        call.send(reply)
    }

    /** The live note a (possibly merged) id resolves to; null when the note is missing or deleted. */
    private fun survivor(dsl: DSLContext, id: String): String? =
        dsl.fetchOne(
            "select s.id::text from app.note n join app.note s on s.id = coalesce(n.merged_into_id, n.id) where n.id = ?::uuid and s.deleted_at is null",
            id,
        )?.get(0, String::class.java)

    private fun notFound(id: String) = ApiException(HttpStatusCode.NotFound, "not_found", "Not found", "Note $id does not exist.")

    private fun enumValue(e: Errors, v: String): String? =
        v.takeIf { it in STATUSES } ?: run { e.add("/status", "invalid_value", "Must be one of ${STATUSES.joinToString()}."); null }

    private fun render(dsl: DSLContext, rows: List<org.jooq.Record>): List<Map<String, Any?>> {
        val views = NoteViews(dsl)
        return rows.map { r ->
            val sa = r.get(6, String::class.java)
            val sb = r.get(7, String::class.java)
            val (x, y) = if (sa < sb) sa to sb else sb to sa
            linkedMapOf(
                "note_a_id" to x,
                "note_b_id" to y,
                "reason" to r.get(2, String::class.java),
                "status" to r.get(3, String::class.java),
                "created_at" to r.get(4, String::class.java),
                "updated_at" to r.get(5, String::class.java),
                "note_a" to summary(views, x),
                "note_b" to summary(views, y),
            )
        }
    }

    private fun summary(views: NoteViews, id: String): Map<String, Any?> {
        val n = checkNotNull(views.noteRow(id)) { "note $id" }
        return SUMMARY_KEYS.associateWith { n.get(it) }.mapValues { (_, v) -> json.treeToValue(v, Any::class.java) }
    }
}
