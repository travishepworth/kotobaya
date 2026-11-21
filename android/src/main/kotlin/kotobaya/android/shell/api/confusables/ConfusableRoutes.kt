package kotobaya.android.shell.api.confusables

import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.actions.ActionViews
import kotobaya.android.shell.api.actions.UUID_RE
import kotobaya.android.shell.api.actions.enumValue
import kotobaya.android.shell.api.actions.exclusive
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.pageLimit
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.timestamp
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.MergeReaders
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRequest

private val STATUSES = setOf("suggested", "confirmed", "rejected")
private val REASONS = setOf("homophone", "look_alike", "meaning")
private val SUMMARY_KEYS = listOf("id", "note_type_id", "type", "entry_seq", "kanji_literal", "grammar_point_id", "fields", "tags", "mnemonic", "source_sentence")

private fun Row.ms(column: String): Long = (this[column] as? JsonNumber)?.value?.toLong() ?: 0L

private fun Row.stamp(column: String): JsonValue = (this[column] as? JsonNumber)?.value?.toLong()?.let { JsonString(timestamp(it)) } ?: JsonNull

/**
 * A stored pair with both notes resolved to their survivors (server `ConfusableApi.base`, SYNC.md 8.5). [a] < [b] are
 * the survivors' ids.
 */
private class Pair2(val row: Row, val a: String, val b: String) {
    val reason: String get() = row["reason"].text().orEmpty()
    val status: String get() = row["status"].text().orEmpty()
}

/** The live survivor of note [id], or null when the note is absent, or it or its survivor is deleted. */
private fun survivor(t: LearningTables, id: String): Row? {
    val note = t.get(LearningTable.NOTE, listOf(JsonString(id))) ?: return null
    val s = (if (note["merged_into_id"].isNullish()) note else MergeReaders.resolveNote(t, id)) ?: return null
    return s.takeIf { it["deleted_at"].isNullish() }
}

/** Every visible pair: notes present, survivors live and different (the server's `base` query). */
private fun visiblePairs(t: LearningTables): Sequence<Pair2> = t.all(LearningTable.CONFUSABLE).asSequence().mapNotNull { r ->
    val sa = r["note_a_id"].text()?.let { survivor(t, it) }?.get("id").text() ?: return@mapNotNull null
    val sb = r["note_b_id"].text()?.let { survivor(t, it) }?.get("id").text() ?: return@mapNotNull null
    if (sa == sb) null else Pair2(r, minOf(sa, sb), maxOf(sa, sb))
}

/** `NoteSummary` (server `NoteViews.noteRow` restricted to its keys, as `getNote` builds them). */
private fun summary(t: LearningTables, id: String): JsonObject {
    val note = checkNotNull(t.get(LearningTable.NOTE, listOf(JsonString(id)))) { "note $id" }
    val full = ActionViews.note(t, note).members
    return JsonObject(SUMMARY_KEYS.associateWithTo(LinkedHashMap()) { full[it] ?: JsonNull })
}

/** The `Confusable` view of [p]. */
private fun view(t: LearningTables, p: Pair2): JsonObject = jsonObj(
    "note_a_id" to JsonString(p.a),
    "note_b_id" to JsonString(p.b),
    "reason" to JsonString(p.reason),
    "status" to JsonString(p.status),
    "created_at" to p.row.stamp("created_at"),
    "updated_at" to p.row.stamp("updated_at"),
    "note_a" to summary(t, p.a),
    "note_b" to summary(t, p.b),
)

private fun FieldErrors.status(raw: String?): String? = raw?.takeIf { it in STATUSES || run { add("/status", "invalid_value", "Must be one of ${STATUSES.joinToString(", ")}."); false } }

private fun store(call: LocalCall): SqlDelightDeviceStore = call.services.get()

/**
 * `listWordConfusables` (server `ConfusableApi.listForWord`): the pairs of the live, unmerged vocab note of entry
 * `seq`; `confirmed` first, then by `updated_at` descending; default statuses `suggested` and `confirmed`. No note:
 * empty. Not paginated.
 */
internal fun listWordConfusables(call: LocalCall): ShellResponse {
    val seq = call.path("seq").toLongOrNull()?.takeIf { it >= 1 }
    if (seq == null) FieldErrors().apply { add("/seq", "invalid_value", "Must be an integer of at least 1.") }.throwIfAny()
    val q = call.query("status")
    val e = FieldErrors()
    val status = e.status(q.first("status"))
    e.throwIfAny()
    val items = store(call).read { tx ->
        val t = tx.learning
        val note = t.where(LearningTable.NOTE, "entry_seq", JsonNumber(checkNotNull(seq).toDouble()))
            .filter { it["type"].text() == "vocab" && it["deleted_at"].isNullish() && it["merged_into_id"].isNullish() }
            .mapNotNull { it["id"].text() }.minOrNull()
        if (note == null) {
            emptyList()
        } else {
            val wanted = status?.let { setOf(it) } ?: setOf("suggested", "confirmed")
            visiblePairs(t).filter { (it.a == note || it.b == note) && it.status in wanted }
                .sortedWith(
                    compareByDescending<Pair2> { it.status == "confirmed" }.thenByDescending { it.row.ms("updated_at") }
                        .thenBy { it.row["note_a_id"].text() }.thenBy { it.row["note_b_id"].text() }.thenBy { it.reason },
                )
                .map { view(t, it) }.toList()
        }
    }
    return ShellResponse.json(jsonObj("items" to JsonArray(items)))
}

private val PAIR_ORDER = Keyset.columns(Keyset.LONG_DESC, Keyset.TEXT_ASC, Keyset.TEXT_ASC, Keyset.TEXT_ASC)

private fun pairKey(p: Pair2): List<String> =
    listOf(p.row.ms("updated_at").toString(), p.row["note_a_id"].text().orEmpty(), p.row["note_b_id"].text().orEmpty(), p.reason)

/**
 * `listConfusables` (server `ConfusableApi.list`): every visible pair, optionally of one `status` and involving one
 * note (by survivor), ordered by `updated_at` descending then `(note_a_id, note_b_id, reason)`, keyset-paged.
 */
internal fun listConfusables(call: LocalCall): ShellResponse {
    val q = call.query("status", "note_id", "limit", "cursor")
    val e = FieldErrors()
    val status = e.status(q.first("status"))
    val noteId = q.first("note_id")?.takeIf { UUID_RE.matches(it) || run { e.add("/note_id", "invalid_value", "Must be a lowercase UUID."); false } }
    val limit = pageLimit(q.first("limit"), e)
    val hash = "confusables:${status ?: ""}:${noteId ?: ""}".hashCode().toString(16)
    val after = Keyset.decode(q.first("cursor"), 4, hash, e) { Keyset.isLong(it[0]) }
    e.throwIfAny()
    val body = store(call).read { tx ->
        val t = tx.learning
        val pairs = visiblePairs(t).filter { (status == null || it.status == status) && (noteId == null || it.a == noteId || it.b == noteId) }
        val (shown, last) = Keyset.page(pairs, ::pairKey, PAIR_ORDER, after, limit)
        jsonObj(
            "items" to JsonArray(shown.map { view(t, it) }),
            "next_cursor" to (last?.let { JsonString(Keyset.encode(it, hash)) } ?: JsonNull),
        )
    }
    return ShellResponse.json(body)
}

/**
 * `setConfusable` (server `ConfusableApi.set`): path and body checks (`422`); both notes resolved to live survivors
 * (`404` otherwise), the same survivor twice `422` at `/note_b_id`; one `confusable.confirmed` / `.rejected` for the
 * pair ordered `note_a_id < note_b_id` (a register write: repeating it mints again, SHELL.md 10.11). The answer is the
 * row as stored after the apply (a newer decision from another replica may still hold it).
 */
internal fun setConfusable(call: LocalCall): ShellResponse {
    val a = call.path("note_a_id")
    val b = call.path("note_b_id")
    val reason = call.path("reason")
    call.query()
    val body = call.bodyObject()
    val e = FieldErrors()
    if (!UUID_RE.matches(a)) e.add("/note_a_id", "invalid_value", "Must be a lowercase UUID.")
    if (!UUID_RE.matches(b)) e.add("/note_b_id", "invalid_value", "Must be a lowercase UUID.")
    if (reason !in REASONS) e.add("/reason", "invalid_value", "Must be one of ${REASONS.joinToString(", ")}.")
    e.unknownKeys(body, setOf("status"))
    val status = e.enumValue(body, "status", setOf("confirmed", "rejected"), required = true)
    e.throwIfAny()
    val writes: LocalWrites<SqlLearningTables> = call.services.get()
    val pair = writes.exclusive<JsonObject> { tx ->
        val t = tx.learning
        val ra = survivor(t, a)?.get("id").text() ?: throw ProblemException(Problems.notFound("Note $a does not exist."))
        val rb = survivor(t, b)?.get("id").text() ?: throw ProblemException(Problems.notFound("Note $b does not exist."))
        if (ra == rb) FieldErrors().apply { add("/note_b_id", "invalid_value", "Both notes are the same note.") }.throwIfAny()
        val (x, y) = if (ra < rb) ra to rb else rb to ra
        val type = if (status == "confirmed") OpType.CONFUSABLE_CONFIRMED else OpType.CONFUSABLE_REJECTED
        val payload = jsonObj("note_a_id" to JsonString(x), "note_b_id" to JsonString(y), "reason" to JsonString(reason))
        WritePlan.Mint(listOf(MintRequest(type, payload))) { after, _ ->
            val row = checkNotNull(after.learning.get(LearningTable.CONFUSABLE, listOf(JsonString(x), JsonString(y), JsonString(reason)))) { "pair vanished" }
            view(after.learning, Pair2(row, x, y))
        }
    }
    return ShellResponse.json(pair)
}
