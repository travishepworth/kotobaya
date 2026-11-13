package kotobaya.android.review.actions

import kotobaya.android.review.CardRows
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.render.CardRenderer
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.render.DefaultCardRenderer
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.DeviceStore

/** Builds the toolbar actions over a backend's store and minter. */
fun <L : LearningTables> kotobaya.android.review.ReviewBackend<L>.newActions(): ReviewActions<L> =
    ReviewActions(store, minter, clock, nextStudyDayStart)

/** What the toolbar shows for a card. */
data class CardActionState(
    val suspended: Boolean,
    val buriedUntil: Long?,
    /** 0 = none, 1-7 = colour. */
    val flags: Int,
    /** The vocab entry of the card's note, or null (never-forget and blacklist need one). */
    val entrySeq: Long?,
    /** `word_status.override_status` of that entry: known, never_forget, blacklisted, ignored, or null. */
    val wordOverride: String?,
)

/** The note behind a card, for the editor. */
data class EditableNote(
    val noteId: String,
    /** Field keys of the note type, in order, then any extra keys the note holds. */
    val keys: List<String>,
    val values: Map<String, String>,
)

sealed interface ActionResult {
    /** Minted and applied; [undoable] is false for a no-op. */
    data class Done(val undoable: Boolean = true, val warnings: List<EditWarning> = emptyList()) : ActionResult

    data class Refused(val reason: MintRefusal) : ActionResult

    /** The card has no vocab entry (never-forget, blacklist) or no longer exists. */
    data object NotApplicable : ActionResult
}

/** An edit left the front of a card kind empty; that card will render blank (the T201 skip-empty-front rule). */
data class EditWarning(val cardId: String, val kind: String)

/**
 * The review toolbar actions. Each mints exactly the contract's op through [OpMinter] (one transaction, which also
 * applies it locally) and records the inverse op(s) so [undo] can mint them (the 'Undo' snackbar). The caller
 * rebuilds the queue afterwards. Call from one background context.
 */
class ReviewActions<L : LearningTables>(
    private val store: DeviceStore<L>,
    private val minter: OpMinter<L>,
    private val clock: () -> Long,
    /** First instant of the study day after the one containing the argument (the bury horizon). */
    private val nextStudyDayStart: (Long) -> Long,
    private val renderer: CardRenderer = DefaultCardRenderer,
) {
    private val inverses = ArrayDeque<List<MintRequest>>()

    val canUndo: Boolean get() = inverses.isNotEmpty()

    fun stateOf(cardId: String): CardActionState? = store.transaction { tx ->
        val t = tx.learning
        val card = CardRows.get(t, LearningTable.CARD, cardId) ?: return@transaction null
        val entry = entryOf(t, card)
        val override = entry?.let { t.get(LearningTable.WORD_STATUS, listOf(JsonNumber(it.toDouble()))) }
            ?.let { CardRows.str(it["override_status"]) }
        CardActionState(
            suspended = (card["suspended"] as? JsonBool)?.value ?: false,
            buriedUntil = (card["buried_until"] as? JsonNumber)?.value?.toLong(),
            flags = (card["flags"] as? JsonNumber)?.value?.toInt() ?: 0,
            entrySeq = entry,
            wordOverride = override,
        )
    }

    /** Suspends the card; on a suspended card, unsuspends it. */
    fun suspend(cardId: String): ActionResult {
        val s = stateOf(cardId) ?: return ActionResult.NotApplicable
        return if (s.suspended) run(listOf(req(OpType.CARD_UNSUSPENDED, cardId)), listOf(req(OpType.CARD_SUSPENDED, cardId)))
        else run(listOf(req(OpType.CARD_SUSPENDED, cardId)), listOf(req(OpType.CARD_UNSUSPENDED, cardId)))
    }

    /** Buries the card until the start of the next study day; on an already buried card, unburies it. */
    fun bury(cardId: String): ActionResult {
        val s = stateOf(cardId) ?: return ActionResult.NotApplicable
        val now = clock()
        val until = s.buriedUntil
        if (until != null && until > now) {
            return run(listOf(req(OpType.CARD_UNBURIED, cardId)), listOf(buriedReq(cardId, until)))
        }
        val inverse = if (until != null) buriedReq(cardId, until) else req(OpType.CARD_UNBURIED, cardId)
        return run(listOf(buriedReq(cardId, nextStudyDayStart(now))), listOf(inverse))
    }

    /** Sets the flag colour (0 clears). */
    fun flag(cardId: String, flags: Int): ActionResult {
        require(flags in 0..7) { "flags 0..7" }
        val s = stateOf(cardId) ?: return ActionResult.NotApplicable
        if (s.flags == flags) return ActionResult.Done(undoable = false)
        return run(listOf(flagReq(cardId, flags)), listOf(flagReq(cardId, s.flags)))
    }

    /** Toggles the never-forget override of the card's entry (`word.status_set` / `word.status_cleared`). */
    fun neverForget(cardId: String): ActionResult = toggleStatus(cardId, "never_forget")

    /** Toggles the blacklist override of the card's entry. */
    fun blacklist(cardId: String): ActionResult = toggleStatus(cardId, "blacklisted")

    private fun toggleStatus(cardId: String, status: String): ActionResult {
        val s = stateOf(cardId) ?: return ActionResult.NotApplicable
        val entry = s.entrySeq ?: return ActionResult.NotApplicable
        val inverse = s.wordOverride?.let { setReq(entry, it) } ?: clearReq(entry)
        val forward = if (s.wordOverride == status) clearReq(entry) else setReq(entry, status)
        return run(listOf(forward), listOf(inverse))
    }

    /** The note behind [cardId], for the bottom-sheet editor. */
    fun editable(cardId: String): EditableNote? = store.transaction { tx ->
        val t = tx.learning
        val card = CardRows.get(t, LearningTable.CARD, cardId) ?: return@transaction null
        val noteId = CardRows.str(card["note_id"]) ?: return@transaction null
        val note = CardRows.get(t, LearningTable.NOTE, noteId) ?: return@transaction null
        val type = CardRows.get(t, LearningTable.NOTE_TYPE, CardRows.str(note["note_type_id"]) ?: return@transaction null)
        val values = CardRows.fieldsOf(note)
        val typeKeys = type?.let { CardRows.noteTypeInfo(it).fieldKeys }.orEmpty()
        EditableNote(noteId, typeKeys + values.keys.filter { it !in typeKeys }, values)
    }

    /**
     * Saves an edit: one `note.updated` carrying the fields that differ ([edited] maps key to the new HTML). The
     * result lists the note's cards whose front now renders empty (kept, but flagged to the user).
     */
    fun saveEdit(cardId: String, edited: Map<String, String>): ActionResult {
        val note = editable(cardId) ?: return ActionResult.NotApplicable
        val changed = edited.filter { (k, v) -> (note.values[k] ?: "") != v }
        if (changed.isEmpty()) return ActionResult.Done(undoable = false)
        val old = changed.keys.associateWith { note.values[it] ?: "" }
        val r = minter.mint(OpType.NOTE_UPDATED, notePayload(note.noteId, changed))
        if (r is MintResult.Refused) return ActionResult.Refused(r.reason)
        inverses.addLast(listOf(MintRequest(OpType.NOTE_UPDATED, notePayload(note.noteId, old))))
        return ActionResult.Done(warnings = emptyFronts(note.noteId))
    }

    /** Mints the inverse of the last action; false when none or refused. */
    fun undo(): Boolean {
        val inv = inverses.lastOrNull() ?: return false
        if (minter.mintAll(inv) is MintResult.Refused) return false
        inverses.removeLast()
        return true
    }

    private fun emptyFronts(noteId: String): List<EditWarning> = store.transaction { tx ->
        val t = tx.learning
        val note = CardRows.get(t, LearningTable.NOTE, noteId) ?: return@transaction emptyList()
        val typeRow = CardRows.get(t, LearningTable.NOTE_TYPE, CardRows.str(note["note_type_id"]) ?: return@transaction emptyList())
            ?: return@transaction emptyList()
        val info: NoteTypeInfo = CardRows.noteTypeInfo(typeRow)
        val fields = CardRows.fieldsOf(note)
        t.where(LearningTable.CARD, "note_id", JsonString(noteId)).filter(CardRows::live).mapNotNull { c ->
            val kind = CardRows.str(c["kind"]) ?: return@mapNotNull null
            val front = renderer.render(
                RenderInput(
                    noteType = info, kind = kind, fields = fields, mnemonic = "", dictionary = null, cloze = null,
                    side = CardSide.FRONT,
                    options = RenderOptions(furigana = false, pitch = false, showMnemonic = false, mediaUrlTemplate = kotobaya.android.review.CardPage.MEDIA_URL_TEMPLATE),
                ),
            )
            val text = front.html.replace(Regex("<[^>]*>"), "").trim()
            if (text.isEmpty() && front.audio.isEmpty() && !front.html.contains("<img", ignoreCase = true)) {
                EditWarning(CardRows.str(c["id"]).orEmpty(), kind)
            } else null
        }
    }

    private fun run(forward: List<MintRequest>, inverse: List<MintRequest>): ActionResult {
        val r = minter.mintAll(forward)
        if (r is MintResult.Refused) return ActionResult.Refused(r.reason)
        inverses.addLast(inverse)
        return ActionResult.Done()
    }

    private fun entryOf(t: LearningTables, card: Row): Long? {
        val note = CardRows.get(t, LearningTable.NOTE, CardRows.str(card["note_id"]) ?: return null) ?: return null
        val seq = (note["entry_seq"] as? JsonNumber)?.value?.toLong() ?: return null
        val type = CardRows.get(t, LearningTable.NOTE_TYPE, CardRows.str(note["note_type_id"]) ?: return null) ?: return null
        return seq.takeIf { CardRows.str(type["base_type"]) == "vocab" }
    }

    private fun req(type: OpType, cardId: String) = MintRequest(type, obj("id" to JsonString(cardId)))

    private fun buriedReq(cardId: String, until: Long) =
        MintRequest(OpType.CARD_BURIED, obj("id" to JsonString(cardId), "until" to JsonNumber(until.toDouble())))

    private fun flagReq(cardId: String, flags: Int) =
        MintRequest(OpType.CARD_FLAG_SET, obj("id" to JsonString(cardId), "flags" to JsonNumber(flags.toDouble())))

    private fun setReq(entry: Long, status: String) = MintRequest(
        OpType.WORD_STATUS_SET,
        obj("entry_seq" to JsonNumber(entry.toDouble()), "status" to JsonString(status), "origin" to JsonString("manual")),
    )

    private fun clearReq(entry: Long) = MintRequest(OpType.WORD_STATUS_CLEARED, obj("entry_seq" to JsonNumber(entry.toDouble())))

    private fun notePayload(noteId: String, fields: Map<String, String>) = obj(
        "id" to JsonString(noteId),
        "set" to obj("fields" to obj(*fields.map { (k, v) -> k to (JsonString(v) as JsonValue) }.toTypedArray())),
    )

    private fun obj(vararg p: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*p))
}
