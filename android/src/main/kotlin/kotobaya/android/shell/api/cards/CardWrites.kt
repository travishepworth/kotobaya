package kotobaya.android.shell.api.cards

import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellHeaders
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.actions.CardMints
import kotobaya.android.shell.api.actions.KEY_RE
import kotobaya.android.shell.api.actions.exclusive
import kotobaya.android.shell.api.actions.instant
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.long
import kotobaya.android.shell.api.actions.pathId
import kotobaya.android.shell.api.actions.string
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.actions.uuidValue
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.api.review.AnswerViews
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.CardKinds
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.MergeReaders
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest

private const val CARDS = "/api/v1/cards"
private val CREATE_KEYS = setOf("id", "note_id", "kind")
private val PATCH_KEYS = setOf("suspended", "buried_until", "flags", "preset_id")

private fun noCard(): Nothing = throw ProblemException(Problems.notFound("No such card."))

private fun invalid(pointer: String, detail: String): Nothing =
    throw ProblemException(Problems.validationFailed(pointer, "invalid_value", detail))

private fun writes(call: LocalCall): LocalWrites<SqlLearningTables> = call.services.get()

private fun uuid(e: FieldErrors, o: JsonObject, key: String, required: Boolean = false): String? {
    val v = o.members[key]
    if (v == null) {
        if (required) e.add("/$key", "required", "$key is required.")
        return null
    }
    return e.uuidValue(v, "/$key")
}

/** The `Card` view of [card] (server `ReviewViews.cards`), its note looked up by `note_id`. */
private fun view(t: LearningTables, card: Row): JsonObject {
    val note = card["note_id"].text()?.let { t.get(LearningTable.NOTE, listOf(JsonString(it))) } ?: noCard()
    return AnswerViews.card(card, note, AnswerViews.leechThreshold(t))
}

/** `410` for a deleted card or a card of a deleted note (server `CardApi.goneIfDeleted`). */
private fun goneIfDeleted(t: LearningTables, card: Row) {
    if (!card["deleted_at"].isNullish()) throw ProblemException(Problems.gone("The card was deleted."))
    val note = card["note_id"].text()?.let { t.get(LearningTable.NOTE, listOf(JsonString(it))) }
    if (note != null && !note["deleted_at"].isNullish()) throw ProblemException(Problems.gone("The note was deleted."))
}

private fun located(status: Int, body: JsonObject, shown: String, requested: String): ShellResponse =
    ShellResponse.json(body, status, if (shown != requested) mapOf(ShellHeaders.CONTENT_LOCATION to "$CARDS/$shown") else emptyMap())

/** A refused card op pointer (`/payload/<member>`) as the request property it came from (L-3). */
private fun cardPointer(r: MintRefusal.Invalid): String = when (r.pointer.removePrefix("/payload/").substringBefore('/')) {
    "until" -> "/buried_until"
    "flags" -> "/flags"
    "preset_id" -> "/preset_id"
    "kind" -> "/kind"
    "note_id" -> "/note_id"
    "id" -> "/id"
    else -> ""
}

/**
 * `createCard` (SHELL.md 4.3, server `CardApi.create`): body checks; the note (merge loser → survivor) must exist
 * (`404`) and be live (`410`); a card with the request's `id` → `200` with it (`409 conflict` if it belongs to another
 * note or kind); a live card of `(note, kind)` → `200` with it (L-4); `kind` must be a key of the note type's
 * `templates` and not retired (ADR-035), else `422` at `/kind`; then one `card.created` (device UUIDv7 id if none).
 */
internal fun createCard(call: LocalCall): ShellResponse {
    call.query()
    val body = call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, CREATE_KEYS)
    val id = uuid(e, body, "id")
    val noteId = uuid(e, body, "note_id", required = true)
    val kind = e.string(body, "kind", 1, 64, required = true)?.also {
        if (!KEY_RE.matches(it)) e.add("/kind", "invalid_value", "A kind is a lowercase identifier.")
    }
    e.throwIfAny()
    val requestedNote = checkNotNull(noteId)
    val wantKind = checkNotNull(kind)
    val nowMs = call.services.get<ShellIdentity>().nowMs()
    val (status, card) = writes(call).exclusive<Pair<Int, JsonObject>> { tx ->
        val t = tx.learning
        val note = MergeReaders.resolveNote(t, requestedNote) ?: throw ProblemException(Problems.notFound("No such note."))
        if (!note["deleted_at"].isNullish()) throw ProblemException(Problems.gone("The note was deleted."))
        val survivorNote = checkNotNull(note["id"].text())
        if (id != null) {
            t.get(LearningTable.CARD, listOf(JsonString(id)))?.let { row ->
                val owner = row["note_id"].text()
                if (owner != survivorNote && owner != requestedNote || row["kind"].text() != wantKind) {
                    throw ProblemException(Problems.conflict("conflict", "A card with this id exists for another note or kind."))
                }
                return@exclusive WritePlan.Answer(200 to view(t, row))
            }
        }
        t.where(LearningTable.CARD, "note_id", JsonString(survivorNote))
            .firstOrNull { it["kind"].text() == wantKind && it["deleted_at"].isNullish() && it["merged_into_id"].isNullish() }
            ?.let { return@exclusive WritePlan.Answer(200 to view(t, it)) }
        val type = note["note_type_id"].text()?.let { t.get(LearningTable.NOTE_TYPE, listOf(JsonString(it))) }
        val templates = type?.get("templates") as? JsonObject
        if (templates == null || wantKind !in templates.members) invalid("/kind", "The note type has no card kind $wantKind.")
        val base = NoteBaseType.entries.firstOrNull { it.wire == type["base_type"].text() }
        if (base != null && CardKinds.isRetired(base, wantKind, readingEnabled(t))) {
            invalid("/kind", "Card kind $wantKind is retired while cards.reading_enabled is off.")
        }
        val newId = id ?: CardMints.newId(nowMs)
        val payload = jsonObj("id" to JsonString(newId), "note_id" to JsonString(survivorNote), "kind" to JsonString(wantKind))
        WritePlan.Mint(listOf(MintRequest(OpType.CARD_CREATED, payload)), ::cardPointer) { after, _ ->
            val created = checkNotNull(after.learning.get(LearningTable.CARD, listOf(JsonString(newId)))) { "card $newId missing after card.created" }
            201 to view(after.learning, created)
        }
    }
    return ShellResponse.json(card, status)
}

private fun readingEnabled(t: LearningTables): Boolean =
    CardKinds.readingEnabled(t.get(LearningTable.SETTINGS, listOf(JsonString(CardKinds.SETTING_READING_ENABLED)))?.get("value"))

/**
 * `updateCard` (server `CardApi.update`): one op per property present, in the server's order (`suspended`,
 * `buried_until`, `flags`, `preset_id`) on the survivor; empty body `422`; `404` / `410` (L-6); `preset_id` must be a
 * live `fsrs_params` row of the replica (`422` at `/preset_id`). A value equal to the current one still mints (L-2).
 */
internal fun updateCard(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("card_id", "card")
    val body = call.bodyObject()
    val m = body.members
    val e = FieldErrors()
    e.unknownKeys(body, PATCH_KEYS)
    if (m.isEmpty()) e.add("", "required", "At least one property is required.")
    val suspended = m["suspended"]?.let { v -> (v as? JsonBool)?.value ?: run { e.add("/suspended", "invalid_type", "Must be a boolean."); null } }
    val buried = m["buried_until"]
    val buriedMs = buried?.takeIf { it != JsonNull }?.let { e.instant(it, "/buried_until") }
    val flags = m["flags"]?.let { e.long(it, "/flags", 0, 7) }
    val presetId = uuid(e, body, "preset_id")
    e.throwIfAny()
    val (shown, card) = writes(call).exclusive<Pair<String, JsonObject>> { tx ->
        val t = tx.learning
        val row = MergeReaders.resolveCard(t, id) ?: noCard()
        val survivor = checkNotNull(row["id"].text())
        goneIfDeleted(t, row)
        if (presetId != null) {
            val preset = t.get(LearningTable.FSRS_PARAMS, listOf(JsonString(presetId)))
            if (preset == null || !preset["deleted_at"].isNullish()) invalid("/preset_id", "No such live preset.")
        }
        val requests = ArrayList<MintRequest>()
        suspended?.let { requests += if (it) CardMints.suspended(survivor) else CardMints.unsuspended(survivor) }
        if (buried != null) requests += if (buriedMs != null) CardMints.buried(survivor, buriedMs) else CardMints.unburied(survivor)
        flags?.let { requests += CardMints.flagSet(survivor, it) }
        presetId?.let { requests += CardMints.presetSet(survivor, it) }
        WritePlan.Mint(requests, ::cardPointer) { after, _ ->
            val now = checkNotNull(after.learning.get(LearningTable.CARD, listOf(JsonString(survivor)))) { "card $survivor vanished" }
            survivor to view(after.learning, now)
        }
    }
    return located(200, card, shown, id)
}

/** `deleteCard` (server `CardApi.delete`): unknown `404`; deleted or merged away `204`, nothing minted; else one `card.deleted`. */
internal fun deleteCard(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("card_id", "card")
    writes(call).exclusive<Unit> { tx ->
        val row = tx.learning.get(LearningTable.CARD, listOf(JsonString(id))) ?: noCard()
        if (!row["deleted_at"].isNullish() || !row["merged_into_id"].isNullish()) return@exclusive WritePlan.Answer(Unit)
        WritePlan.Mint(listOf(CardMints.deleted(id)), ::cardPointer) { _, _ -> }
    }
    return ShellResponse.noContent()
}
