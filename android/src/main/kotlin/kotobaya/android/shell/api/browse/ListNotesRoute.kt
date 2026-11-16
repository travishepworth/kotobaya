package kotobaya.android.shell.api.browse

import java.util.Base64
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.actions.ActionViews
import kotobaya.android.shell.api.actions.UUID_RE
import kotobaya.android.shell.api.actions.instant
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.pageLimit
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.get
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row

private val BASE_TYPES = setOf("vocab", "kanji", "phrase", "grammar", "sentence", "custom")

private fun Row.ms(column: String): Long = (this[column] as? JsonNumber)?.value?.toLong() ?: 0L

/** `note.search_text` of the server (`app.note_search_text`): field values in key order, mnemonic, source sentence. */
private fun searchText(n: Row): String {
    val fields = (n["fields"] as? JsonObject)?.members.orEmpty()
    val values = fields.entries.sortedBy { it.key }.joinToString(" ") { (it.value as? JsonString)?.value.orEmpty() }
    return values + " " + n["mnemonic"].text().orEmpty() + " " + n["source_sentence"].text().orEmpty()
}

/**
 * `listNotes` (SHELL.md L-1): the filters of the server's `NoteApi.list` over the replica's notes, ordered by
 * `created_at` descending then `id` descending, with a keyset cursor `(created_at, id)` so a note created between
 * two pages never repeats or hides another. Merge losers and tombstones only with `include_deleted=true`. Every note
 * embeds its live cards ([ActionViews.note], as `getNote`).
 */
internal fun listNotes(call: LocalCall): ShellResponse {
    val q = call.query(
        "q", "type", "note_type_id", "tag", "entry_seq", "kanji_literal", "grammar_point_id", "updated_since",
        "include_deleted", "limit", "cursor",
    )
    val e = FieldErrors()
    val includeDeleted = q.bool("include_deleted", false, e)
    val text = q.first("q")?.takeIf { v ->
        (v.codePointCount(0, v.length) in 1..200).also { if (!it) e.add("/q", if (v.isEmpty()) "invalid_value" else "too_long", "1 to 200 characters.") }
    }?.lowercase()
    val type = q.first("type")?.takeIf { it in BASE_TYPES || run { e.add("/type", "invalid_value", "Must be one of ${BASE_TYPES.joinToString(", ")}."); false } }
    fun uuid(name: String): String? = q.first(name)?.takeIf { UUID_RE.matches(it) || run { e.add("/$name", "invalid_value", "Must be a lowercase UUID."); false } }
    val noteTypeId = uuid("note_type_id")
    val grammarPointId = uuid("grammar_point_id")
    val tag = q.first("tag")?.also {
        when {
            it.isEmpty() -> e.add("/tag", "invalid_value", "Must not be empty.")
            it == "leech" -> e.add("/tag", "invalid_value", "The virtual tag leech is not accepted here; use GET /cards?query=tag:leech.")
        }
    }
    val entrySeq = q.first("entry_seq")?.let { v -> v.toLongOrNull()?.takeIf { it >= 1 } ?: run { e.add("/entry_seq", "invalid_value", "Must be an integer of at least 1."); null } }
    val kanji = q.first("kanji_literal")?.takeIf { v ->
        (v.isNotEmpty() && v.codePointCount(0, v.length) <= 2).also { if (!it) e.add("/kanji_literal", "invalid_value", "One kanji.") }
    }
    val updatedSince = q.first("updated_since")?.let { e.instant(JsonString(it), "/updated_since") }
    val limit = pageLimit(q.first("limit"), e)
    val hash = listOf(text, type, noteTypeId, grammarPointId, tag, entrySeq, kanji, updatedSince, includeDeleted).joinToString("|").hashCode().toString(16)
    val after: Pair<Long, String>? = q.first("cursor")?.let { c ->
        val parts = runCatching { String(Base64.getUrlDecoder().decode(c), Charsets.UTF_8).split('|') }.getOrNull()
        val at = parts?.getOrNull(0)?.toLongOrNull()
        if (parts == null || parts.size != 3 || at == null || parts[2] != hash || !UUID_RE.matches(parts[1])) {
            e.add("/cursor", "invalid_value", "Malformed or foreign cursor.")
            null
        } else {
            at to parts[1]
        }
    }
    e.throwIfAny()

    val body = call.services.get<SqlDelightDeviceStore>().read { tx ->
        val t = tx.learning
        fun matches(n: Row): Boolean =
            (includeDeleted || (n["deleted_at"].isNullish() && n["merged_into_id"].isNullish())) &&
                (type == null || n["type"].text() == type) &&
                (noteTypeId == null || n["note_type_id"].text() == noteTypeId) &&
                (grammarPointId == null || n["grammar_point_id"].text() == grammarPointId) &&
                (entrySeq == null || (n["entry_seq"] as? JsonNumber)?.value?.toLong() == entrySeq) &&
                (kanji == null || n["kanji_literal"].text() == kanji) &&
                (tag == null || (n["tags"] as? JsonArray)?.items?.any { (it as? JsonString)?.value == tag } == true) &&
                (updatedSince == null || n.ms("updated_at") > updatedSince) &&
                (text == null || searchText(n).lowercase().contains(text))
        // newest first: created_at desc, id desc; "after" the cursor = strictly smaller in that order
        val order = compareByDescending<Row> { it.ms("created_at") }.thenByDescending { it["id"].text().orEmpty() }
        val window = t.all(LearningTable.NOTE).asSequence().filter(::matches).sortedWith(order)
            .filter { n -> after == null || n.ms("created_at") < after.first || (n.ms("created_at") == after.first && n["id"].text().orEmpty() < after.second) }
            .take(limit + 1).toList()
        val shown = window.take(limit)
        val next = if (window.size > limit) {
            shown.last().let {
                JsonString(Base64.getUrlEncoder().withoutPadding().encodeToString("${it.ms("created_at")}|${it["id"].text()}|$hash".toByteArray()))
            }
        } else {
            JsonNull
        }
        jsonObj("items" to JsonArray(shown.map { ActionViews.note(t, it) }), "next_cursor" to next)
    }
    return ShellResponse.json(body)
}
