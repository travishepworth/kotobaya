package kotobaya.android.shell.api.actions

import kotobaya.android.dict.DictLookup
import kotobaya.android.dict.Resolved
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellHeaders
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.get
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
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest

private const val MAX_TAGS_PER_NOTE = 200
private const val NOTES = "/api/v1/notes"

private val PATCH_KEYS = setOf(
    "fields", "tags", "add_tags", "remove_tags", "mnemonic", "source_sentence", "source_sentence_id", "source_media_id", "entry_seq",
)
private val PATCH_IMMUTABLE = setOf("note_type_id", "kanji_literal", "grammar_point_id", "origin")

private fun gone(): Nothing = throw ProblemException(Problems.gone("The note was deleted."))

private fun notFound(): Nothing = throw ProblemException(Problems.notFound("No such note."))

private fun semantic(pointer: String, code: String, detail: String): Nothing =
    throw ProblemException(Problems.validationFailed(pointer, code, detail))

private fun located(body: JsonObject, shownId: String, requestedId: String): ShellResponse =
    ShellResponse.json(body, headers = if (shownId != requestedId) mapOf(ShellHeaders.CONTENT_LOCATION to "$NOTES/$shownId") else emptyMap())

/**
 * `getNote`: a merge loser answers with its survivor (`content-location`), with or without `include_deleted`; a
 * deleted, unmerged note is `410` unless `include_deleted=true` (L-6).
 */
internal fun getNote(call: LocalCall): ShellResponse {
    val e = FieldErrors()
    val includeDeleted = call.query("include_deleted").bool("include_deleted", false, e)
    val id = call.pathId("note_id", "note")
    e.throwIfAny()
    val (shown, body) = call.services.get<SqlDelightDeviceStore>().read { tx ->
        val t = tx.learning
        val row = ActionViews.resolveNote(t, id) ?: notFound()
        if (!includeDeleted && !row["deleted_at"].isNullish()) gone()
        row["id"].text().orEmpty() to ActionViews.note(t, row)
    }
    return located(body, shown, id)
}

/** A validated `NotePatch` (server `NoteApi.update`, body checks). */
private class NotePatch(
    val body: JsonObject,
    val fields: Map<String, String>?,
    val tags: List<String>?,
    val addTags: List<String>?,
    val removeTags: List<String>?,
    val mnemonic: String?,
    val sourceSentence: String?,
    val sourceSentenceId: String?,
    val sourceMediaId: String?,
    /** `entry_seq` present (possibly null = un-match). */
    val hasEntrySeq: Boolean,
    val entrySeq: Long?,
) {
    val touchesTags: Boolean get() = tags != null || addTags != null || removeTags != null
    val touchesSource: Boolean get() = listOf("source_sentence", "source_sentence_id", "source_media_id").any { it in body.members }
}

private fun notePatch(body: JsonObject): NotePatch {
    val e = FieldErrors()
    e.unknownKeys(body, PATCH_KEYS, PATCH_IMMUTABLE)
    if (body.members.isEmpty()) e.add("", "required", "At least one property is required.")
    val m = body.members
    val fields = m["fields"]?.let { e.fieldValues(it, "/fields", 1) }
    val tags = m["tags"]?.let { e.tagList(it, "/tags", 0, 100) }
    val addTags = m["add_tags"]?.let { e.tagList(it, "/add_tags", 1, 100) }
    val removeTags = m["remove_tags"]?.let { e.tagList(it, "/remove_tags", 1, 100) }
    if (tags != null && (addTags != null || removeTags != null)) {
        e.add("/tags", "conflicting_fields", "tags cannot be combined with add_tags or remove_tags.")
    }
    val mnemonic = e.string(body, "mnemonic", 0, 10_000)
    val sourceSentence = e.string(body, "source_sentence", 0, 10_000)
    val sourceSentenceId = e.nullableUuid(body, "source_sentence_id")
    val sourceMediaId = e.nullableUuid(body, "source_media_id")
    val seqNode = m["entry_seq"]
    val entrySeq = seqNode?.takeUnless { it == JsonNull }?.let { e.long(it, "/entry_seq", 1) }
    e.throwIfAny()
    return NotePatch(body, fields, tags, addTags, removeTags, mnemonic, sourceSentence, sourceSentenceId, sourceMediaId, seqNode != null, entrySeq)
}

/** The live (not deleted) note answering for [id] and the server's semantic checks that need the row. */
private fun target(t: LearningTables, id: String, patch: NotePatch): Row {
    val row = ActionViews.resolveNote(t, id) ?: notFound()
    if (!row["deleted_at"].isNullish()) gone()
    if (patch.hasEntrySeq && row["type"].text() != "vocab") {
        semantic("/entry_seq", "invalid_value", "entry_seq is for vocab notes, not ${row["type"].text()}.")
    }
    return row
}

/** A refused `note.updated` pointer (`/payload/set/<register>…`) as the request property (server: `removePrefix("/set")`). */
private fun notePointer(r: MintRefusal.Invalid): String =
    r.pointer.removePrefix("/payload").takeIf { it.startsWith("/set/") }?.removePrefix("/set") ?: ""

/**
 * `updateNote` (SHELL.md L-2, 4.3): one `note.updated` carrying exactly the registers the body touches (openapi
 * register table); absent properties are unchanged, `fields` writes one register per key, `add_tags` / `remove_tags`
 * are resolved against the current set here (the server does the same at apply time, in its transaction).
 * Order as the server: body `422`s, then `404` / `410`, then `entry_seq` on a non-vocab note, then the 200-tag limit.
 * A new `entry_seq` is checked by the L-7 lookup like `createNote`'s (authoritative miss `422` `/entry_seq`
 * `not_found`; not on the device and not reachable `503 shell_dict_missing`). A device never merges: on a
 * re-match collision the answer is the note itself (4.3). A conflicting newer edit from another device wins by the
 * clock rule when it arrives (SYNC.md 7.1); a pulled delete beats this edit (L-6).
 */
internal suspend fun updateNote(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("note_id", "note")
    val patch = notePatch(call.bodyObject())
    val store = call.services.get<SqlDelightDeviceStore>()
    patch.entrySeq?.let { seq ->
        val current = store.read { tx -> target(tx.learning, id, patch)["entry_seq"] }
        if (integral(current) != seq) {
            when (call.services.get<DictLookup>().resolveEntry(seq)) {
                is Resolved.Found -> Unit
                Resolved.NotFound -> semantic("/entry_seq", "not_found", "No dictionary entry $seq.")
                Resolved.NotOnDevice -> throw ProblemException(Problems.shellDictMissing("Dictionary entry $seq is not on this device."))
            }
        }
    }
    val writes = call.services.get<LocalWrites<SqlLearningTables>>()
    val (shown, body) = writes.exclusive<Pair<String, JsonObject>> { tx ->
        val row = target(tx.learning, id, patch)
        val targetId = checkNotNull(row["id"].text())
        val set = LinkedHashMap<String, JsonValue>()
        patch.fields?.let { f -> set["fields"] = JsonObject(f.mapValues { JsonString(it.value) }) }
        if (patch.touchesTags) {
            val current = (row["tags"] as? JsonArray)?.items.orEmpty().mapNotNull { it.text() }
            val merged = ((patch.tags ?: current) + patch.addTags.orEmpty()).toSortedSet(CodePointOrder)
            merged.removeAll(patch.removeTags.orEmpty().toSet())
            if (merged.size > MAX_TAGS_PER_NOTE) semantic("/add_tags", "out_of_range", "A note has at most $MAX_TAGS_PER_NOTE tags.")
            set["tags"] = JsonArray(merged.map(::JsonString))
        }
        patch.mnemonic?.let { set["mnemonic"] = JsonString(it) }
        if (patch.touchesSource) {
            set["source_sentence"] = JsonString(patch.sourceSentence ?: "")
            set["source_sentence_id"] = patch.sourceSentenceId?.let(::JsonString) ?: JsonNull
            set["source_media_id"] = patch.sourceMediaId?.let(::JsonString) ?: JsonNull
        }
        if (patch.hasEntrySeq) set["entry_seq"] = patch.entrySeq?.let { JsonNumber(it.toDouble()) } ?: JsonNull
        val payload = jsonObj("id" to JsonString(targetId), "set" to JsonObject(set))
        WritePlan.Mint(listOf(MintRequest(OpType.NOTE_UPDATED, payload)), ::notePointer) { after, _ ->
            val t = after.learning
            val shownRow = checkNotNull(ActionViews.resolveNote(t, targetId)) { "note $targetId vanished" }
            checkNotNull(shownRow["id"].text()) to ActionViews.note(t, shownRow)
        }
    }
    return located(body, shown, id)
}

/**
 * `deleteNote`: one `note.deleted` (its cards are hidden through the note's tombstone; no `card.deleted`). Already
 * deleted, or a merge loser (deleting a duplicate never deletes the survivor): `204`, nothing minted. Unknown `404`.
 */
internal fun deleteNote(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("note_id", "note")
    call.services.get<LocalWrites<SqlLearningTables>>().exclusive<Unit> { tx ->
        val row = tx.learning.get(LearningTable.NOTE, listOf(JsonString(id))) ?: notFound()
        if (!row["merged_into_id"].isNullish() || !row["deleted_at"].isNullish()) return@exclusive WritePlan.Answer(Unit)
        WritePlan.Mint(listOf(MintRequest(OpType.NOTE_DELETED, jsonObj("id" to JsonString(id)))), { "" }) { _, _ -> }
    }
    return ShellResponse.noContent()
}
