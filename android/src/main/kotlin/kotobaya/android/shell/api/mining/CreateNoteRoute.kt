package kotobaya.android.shell.api.mining

import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotobaya.android.dict.DictLookup
import kotobaya.android.dict.Resolved
import kotobaya.android.notes.DuplicateCheck
import kotobaya.android.notes.NoteCreator
import kotobaya.android.notes.readingEnabled
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.Problem
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.actions.ActionViews
import kotobaya.android.shell.api.actions.KEY_RE
import kotobaya.android.shell.api.actions.exclusive
import kotobaya.android.shell.api.actions.fieldValues
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.long
import kotobaya.android.shell.api.actions.string
import kotobaya.android.shell.api.actions.tagList
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.actions.uuidValue
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.cards.DefaultCardGenerator
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
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.store.UuidV7Source

private val CREATE_KEYS = setOf(
    "id", "note_type_id", "entry_seq", "kanji_literal", "grammar_point_id", "fields", "tags", "mnemonic",
    "source_sentence", "source_sentence_id", "source_media_id", "origin", "card_kinds", "deck_id",
)

/** Ids the server would generate (a note `id` the request omitted, card ids): UUIDv7 from the device clock (L-2). */
private val ids = UuidV7Source(SecureRandom().asKotlinRandom())

/** A validated `NoteCreate` body (server `NoteApi.create`, same checks, codes and pointers). */
internal class NoteCreate(
    val id: String?,
    val noteTypeId: String,
    val entrySeq: Long?,
    val kanji: String?,
    val grammarId: String?,
    val fields: Map<String, String>,
    val tags: List<String>,
    val mnemonic: String,
    val sourceSentence: String,
    val sourceSentenceId: String?,
    val sourceMediaId: String?,
    val origin: String,
    val cardKinds: List<String>?,
    val deckId: String?,
    val onDuplicate: String,
)

private fun FieldErrors.uuid(o: JsonObject, key: String, required: Boolean = false): String? {
    val v = o.members[key]
    if (v == null) {
        if (required) add("/$key", "required", "$key is required.")
        return null
    }
    return uuidValue(v, "/$key")
}

private fun FieldErrors.nullableUuid(o: JsonObject, key: String): String? {
    val v = o.members[key] ?: return null
    return if (v == JsonNull) null else uuidValue(v, "/$key")
}

private fun FieldErrors.kindList(node: JsonValue): List<String>? {
    val items = (node as? JsonArray)?.items
    if (items == null) {
        add("/card_kinds", "invalid_type", "Must be an array.")
        return null
    }
    val out = ArrayList<String>()
    items.forEachIndexed { i, k ->
        val s = (k as? JsonString)?.value
        if (s == null || !KEY_RE.matches(s)) add("/card_kinds/$i", "invalid_value", "A kind is a lowercase identifier.") else out += s
    }
    if (out.size != out.toSet().size) add("/card_kinds", "invalid_value", "Kinds must be unique.")
    return out
}

internal fun noteCreate(call: LocalCall): NoteCreate {
    val q = call.query("on_duplicate")
    val body = call.bodyObject()
    val onDuplicate = q.first("on_duplicate") ?: "error"
    val e = FieldErrors()
    if (onDuplicate !in setOf("error", "return_existing")) e.add("/on_duplicate", "invalid_value", "Must be error or return_existing.")
    e.unknownKeys(body, CREATE_KEYS)
    val id = e.uuid(body, "id")
    val noteTypeId = e.uuid(body, "note_type_id", required = true)
    val entrySeq = body.members["entry_seq"]?.let { e.long(it, "/entry_seq", 1) }
    val kanji = e.string(body, "kanji_literal", 1, 2)?.also {
        if (it.codePointCount(0, it.length) != 1) e.add("/kanji_literal", "invalid_value", "Exactly one kanji (one code point).")
    }
    val grammarId = e.uuid(body, "grammar_point_id")
    val fields = body.members["fields"]?.let { e.fieldValues(it, "/fields", 1) } ?: emptyMap()
    val tags = body.members["tags"]?.let { e.tagList(it, "/tags", 0, 100) } ?: emptyList()
    val mnemonic = e.string(body, "mnemonic", 0, 10_000) ?: ""
    val sourceSentence = e.string(body, "source_sentence", 0, 10_000) ?: ""
    val sourceSentenceId = e.nullableUuid(body, "source_sentence_id")
    val sourceMediaId = e.nullableUuid(body, "source_media_id")
    val origin = e.string(body, "origin", 1, 40)?.also {
        if (!KEY_RE.matches(it)) e.add("/origin", "invalid_value", "A lowercase identifier.")
    } ?: "api"
    val cardKinds = body.members["card_kinds"]?.let { e.kindList(it) }
    val deckId = e.uuid(body, "deck_id")
    val refs = listOf("entry_seq" to (entrySeq != null), "kanji_literal" to (kanji != null), "grammar_point_id" to (grammarId != null)).filter { it.second }
    if (refs.size > 1) refs.forEach { e.add("/${it.first}", "conflicting_fields", "At most one of entry_seq, kanji_literal and grammar_point_id.") }
    e.throwIfAny()
    return NoteCreate(
        id, checkNotNull(noteTypeId), entrySeq, kanji, grammarId, fields, tags, mnemonic, sourceSentence, sourceSentenceId,
        sourceMediaId, origin, cardKinds, deckId, onDuplicate,
    )
}

private fun semantic(pointer: String, code: String, detail: String): Nothing =
    throw ProblemException(Problems.validationFailed(pointer, code, detail))

/** The oldest live note (the one the server keeps, SYNC.md 8.1) among [rows]. */
private fun oldestLive(rows: List<Row>): String? = rows.filter { it["deleted_at"].isNullish() && it["merged_into_id"].isNullish() }
    .minWithOrNull(compareBy<Row>({ (it["created_at"] as? JsonNumber)?.value ?: 0.0 }, { it["id"].text().orEmpty() }))?.get("id").text()

/** A live note holding the reference of [req] (server `liveHolder`; on a device the oldest of a pre-merge pair). */
private fun liveHolder(t: LearningTables, req: NoteCreate): String? = when {
    req.entrySeq != null -> DuplicateCheck.byEntry(t, req.entrySeq)?.noteId
    req.kanji != null -> oldestLive(t.where(LearningTable.NOTE, "kanji_literal", JsonString(req.kanji)))
    req.grammarId != null -> oldestLive(t.where(LearningTable.NOTE, "grammar_point_id", JsonString(req.grammarId)))
    else -> null
}

private fun isLiveUserDeck(t: LearningTables, deckId: String): Boolean =
    t.get(LearningTable.DECK, listOf(JsonString(deckId)))?.let { it["deleted_at"].isNullish() && it["type"].text() == "user" } == true

/** `deck.item_put` at the deck's live maximum position + 1024; key `e:<entry_seq>` or `n:<note id>` (server `putDeckItem`). */
private fun deckItemPut(t: LearningTables, deckId: String, entrySeq: Long?, noteId: String): MintRequest {
    val max = t.where(LearningTable.DECK_ITEM, "deck_id", JsonString(deckId)).filter { it["deleted_at"].isNullish() }
        .maxOfOrNull { (it["position"] as? JsonNumber)?.value?.toLong() ?: 0L } ?: 0L
    return MintRequest(
        OpType.DECK_ITEM_PUT,
        jsonObj(
            "deck_id" to JsonString(deckId),
            "item_key" to JsonString(if (entrySeq != null) "e:$entrySeq" else "n:$noteId"),
            "position" to JsonNumber((max + NoteCreator.DECK_POSITION_STEP).toDouble()),
        ),
    )
}

/** A refused op pointer as the request property: `note.created` payload pointers are the body's; others `""`. */
private fun createPointer(r: MintRefusal.Invalid): String =
    if (r.index == 0) r.pointer.removePrefix("/payload").takeIf { it.startsWith("/") } ?: "" else ""

private fun alreadyExists(holder: String, what: String) = Problem(
    409, "already_exists", "Already exists", "A live note already references $what.",
    extra = mapOf("existing_id" to JsonString(holder)),
)

private fun deckReadOnly() = Problem(409, "deck_read_only", "Deck is read-only", "The deck is not a live user deck.")

/** How the L-7 lookup answered for the request's `entry_seq` before the write (null: none to check). */
private enum class EntryCheck { FOUND, NOT_FOUND, NOT_ON_DEVICE }

/**
 * `createNote` (SHELL.md 4.3, L-2, L-4; openapi `createNote`): the server's rules and answers, minted by the device.
 * - Idempotency: an `id` that exists in the replica (live, deleted or merged) → `200` with it or its survivor, nothing
 *   minted, nothing looked up.
 * - `entry_seq` by the full L-7 lookup (installed package → sidecar cache → when online the server, cached): a known
 *   miss (authoritative package, or the server said `404`) → `422 /entry_seq not_found`; not on the device and not
 *   reachable → `503 shell_dict_missing`, nothing minted — unless a live note already holds the entry, which answers
 *   as the duplicate it is.
 * - Card kinds by core `CardGenerator.kindsForNewNote(type, card_kinds, cards.reading_enabled)` (ADR-035: no
 *   `reading` card while the setting is not true); `deck.item_put` when `deck_id` is given (also on
 *   `return_existing`). The duplicate check, the id check and the mint share one store transaction ([LocalWrites]).
 * The note is created even when its entry came from the server: a device never forwards `createNote`.
 */
internal suspend fun createNote(call: LocalCall): ShellResponse {
    val req = noteCreate(call)
    val store = call.services.get<SqlDelightDeviceStore>()
    // L-4: a retry needs no dictionary and no network
    req.id?.let { id ->
        store.read { tx -> MergeReaders.resolveNote(tx.learning, id)?.let { ActionViews.note(tx.learning, it) } }?.let { return ShellResponse.json(it) }
    }
    val entryCheck = req.entrySeq?.let { seq ->
        val vocab = store.read { tx ->
            tx.learning.get(LearningTable.NOTE_TYPE, listOf(JsonString(req.noteTypeId)))?.get("base_type").text() == "vocab"
        }
        if (!vocab) {
            null // the base-type 422 answers first
        } else {
            when (call.services.get<DictLookup>().resolveEntry(seq)) {
                is Resolved.Found -> EntryCheck.FOUND
                Resolved.NotFound -> EntryCheck.NOT_FOUND
                Resolved.NotOnDevice -> EntryCheck.NOT_ON_DEVICE
            }
        }
    }
    val now = call.services.get<ShellIdentity>().nowMs()
    val writes = call.services.get<LocalWrites<SqlLearningTables>>()
    val (status, body) = writes.exclusive<Pair<Int, JsonObject>> { tx ->
        val t = tx.learning
        val noteId = req.id ?: ids.newOpId(now)
        MergeReaders.resolveNote(t, noteId)?.let { return@exclusive WritePlan.Answer(200 to ActionViews.note(t, it)) }

        val type = t.get(LearningTable.NOTE_TYPE, listOf(JsonString(req.noteTypeId))) ?: semantic("/note_type_id", "not_found", "No such note type.")
        if (!type["deleted_at"].isNullish()) semantic("/note_type_id", "invalid_value", "The note type is deleted.")
        val base = type["base_type"].text()
        val se = FieldErrors()
        for ((name, given, fits) in listOf(
            Triple("entry_seq", req.entrySeq != null, "vocab"),
            Triple("kanji_literal", req.kanji != null, "kanji"),
            Triple("grammar_point_id", req.grammarId != null, "grammar"),
        )) {
            if (given && base != fits) se.add("/$name", "invalid_value", "$name is for $fits note types, not $base.")
        }
        val holder = liveHolder(t, req)
        if (entryCheck == EntryCheck.NOT_FOUND) se.add("/entry_seq", "not_found", "No dictionary entry ${req.entrySeq}.")
        if (req.grammarId != null) {
            val gp = t.get(LearningTable.GRAMMAR_POINT, listOf(JsonString(req.grammarId)))
            if (gp == null || !gp["deleted_at"].isNullish()) se.add("/grammar_point_id", "not_found", "No live grammar point ${req.grammarId}.")
        }
        val info = NoteCreator.noteTypeInfo(type)
        req.cardKinds?.forEachIndexed { i, k ->
            if (info.templates.none { it.kind == k }) se.add("/card_kinds/$i", "invalid_value", "The note type has no card kind $k.")
        }
        se.throwIfAny()
        if (entryCheck == EntryCheck.NOT_ON_DEVICE && holder == null) {
            throw ProblemException(Problems.shellDictMissing("Dictionary entry ${req.entrySeq} is not on this device; connect to look it up."))
        }
        if (req.deckId != null && !isLiveUserDeck(t, req.deckId)) throw ProblemException(deckReadOnly())

        if (holder != null) {
            if (req.onDuplicate == "error") {
                val what = req.entrySeq?.let { "entry_seq $it" } ?: req.kanji?.let { "kanji_literal $it" } ?: "grammar_point_id ${req.grammarId}"
                throw ProblemException(alreadyExists(holder, what))
            }
            val existing = checkNotNull(MergeReaders.resolveNote(t, holder))
            if (req.deckId == null) return@exclusive WritePlan.Answer(200 to ActionViews.note(t, existing))
            return@exclusive WritePlan.Mint(listOf(deckItemPut(t, req.deckId, req.entrySeq, holder))) { after, _ ->
                200 to ActionViews.note(after.learning, checkNotNull(MergeReaders.resolveNote(after.learning, holder)))
            }
        }

        val p = LinkedHashMap<String, JsonValue>()
        p["id"] = JsonString(noteId)
        p["note_type_id"] = JsonString(req.noteTypeId)
        req.entrySeq?.let { p["entry_seq"] = JsonNumber(it.toDouble()) }
        req.kanji?.let { p["kanji_literal"] = JsonString(it) }
        req.grammarId?.let { p["grammar_point_id"] = JsonString(it) }
        p["fields"] = JsonObject(req.fields.mapValues { JsonString(it.value) })
        p["tags"] = JsonArray(req.tags.map(::JsonString))
        p["mnemonic"] = JsonString(req.mnemonic)
        p["source_sentence"] = JsonString(req.sourceSentence)
        req.sourceSentenceId?.let { p["source_sentence_id"] = JsonString(it) }
        req.sourceMediaId?.let { p["source_media_id"] = JsonString(it) }
        p["origin"] = JsonString(req.origin)
        val requests = ArrayList<MintRequest>()
        requests += MintRequest(OpType.NOTE_CREATED, JsonObject(p))
        for (kind in DefaultCardGenerator.kindsForNewNote(info, req.cardKinds, readingEnabled(t))) {
            requests += MintRequest(
                OpType.CARD_CREATED,
                jsonObj("id" to JsonString(ids.newOpId(now)), "note_id" to JsonString(noteId), "kind" to JsonString(kind)),
            )
        }
        req.deckId?.let { requests += deckItemPut(t, it, req.entrySeq, noteId) }
        WritePlan.Mint(requests, ::createPointer) { after, _ ->
            201 to ActionViews.note(after.learning, checkNotNull(after.learning.get(LearningTable.NOTE, listOf(JsonString(noteId)))) { "note $noteId missing after note.created" })
        }
    }
    return ShellResponse.json(body, status = status)
}
