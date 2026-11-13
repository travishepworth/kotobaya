package kotobaya.android.ankiapi.store

import kotobaya.android.ankiapi.provider.AnkiCard
import kotobaya.android.ankiapi.provider.AnkiContract
import kotobaya.android.ankiapi.provider.AnkiDeck
import kotobaya.android.ankiapi.provider.AnkiModel
import kotobaya.android.ankiapi.provider.AnkiNote
import kotobaya.android.ankiapi.provider.AnkiStore
import kotobaya.android.ankiapi.provider.AnkiStoreException
import kotobaya.android.ankiapi.provider.AnkiTemplate
import kotobaya.android.ankiapi.provider.NewModel
import kotobaya.android.ankiapi.provider.NewNote
import kotobaya.android.ankiapi.provider.NoteFilter
import kotobaya.android.ankiapi.provider.ProviderCallLog
import kotobaya.android.ankiapi.provider.TemplateEdit
import kotobaya.android.ankiapi.takoboto.EnsureResult
import kotobaya.android.ankiapi.takoboto.NoteTypeEnsurer
import kotobaya.android.ankiapi.takoboto.TakobotoVocab
import kotobaya.android.notes.CreateRefusal
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.DuplicateCheck
import kotobaya.android.notes.EntryMatcher
import kotobaya.android.notes.LearningReader
import kotobaya.android.notes.NoteCreator
import kotobaya.android.notes.NoteRequest
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.MergeReaders
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.OpIdSource

/** What [TakobotoAnkiStore.add] did. Both carry the note as AnkiDroid callers see it. */
sealed interface AddOutcome {
    val note: AnkiNote

    /** A new note (and its cards and ops) was created. */
    data class Added(override val note: AnkiNote, val noteId: String) : AddOutcome

    /** A live note already studies the word ([DuplicateCheck]); nothing was created. [note] is that note. */
    data class Duplicate(override val note: AnkiNote, val noteId: String) : AddOutcome
}

/**
 * The production [AnkiStore] (T305c): AnkiDroid's API over the learning tables. Every learning-data
 * change is a sync op (CLAUDE.md rule 8) minted offline: notes through [NoteCreator] (`note.created`,
 * `card.created`, `deck.item_put`), the note type through [NoteTypeEnsurer], decks as `deck.created`.
 *
 * What callers see:
 * - **Decks**: the user's live `user` decks; when there are none, a "Takoboto" inbox
 *   ([AnkiIds.INBOX_DECK_ID]). Notes added to the inbox id (always accepted) are deckless (mined).
 * - **Models**: "Takoboto Vocab" ([AnkiIds.TAKOBOTO_MODEL_ID], fields [TakobotoVocab.FIELDS]) plus models
 *   a client created; a client model's fields are mapped to keys by name ([TakobotoVocab.mapClientFields])
 *   and its notes are Takoboto Vocab notes too.
 * - **Notes**: every live vocab note, rendered through the requested (else its own, else Takoboto Vocab)
 *   model; fields a matched note does not store come from the dictionary ([EntryLookup]). Ids come from the
 *   app-private [AnkiIdMap] (`ankiapi_note_map`), allocated on first sight and stable afterwards.
 * - **Duplicates**: words are learned once, so a duplicate query looks across all vocab notes, whatever
 *   the model, and resolves every hit through [DuplicateCheck] (the oldest live note of that word). Adding
 *   a word that is already there creates nothing and answers with the existing note ([AddOutcome.Duplicate]).
 * - Updating, deleting and moving notes/cards are not offered (answered `false`, logged as unknown).
 *
 * Thread-safe: one lock around every call; [transaction] (bulkInsert) holds it and one store transaction.
 */
class TakobotoAnkiStore<L : LearningTables>(
    private val store: DeviceStore<L>,
    private val reader: LearningReader,
    private val minter: OpMinter<L>,
    private val noteTypes: NoteTypeEnsurer<L>,
    private val creator: NoteCreator<L>,
    private val duplicates: DuplicateCheck,
    private val map: AnkiIdMap,
    private val entries: EntryLookup,
    private val ids: OpIdSource,
    private val clock: Clock,
) : AnkiStore {
    private val lock = Any()

    private data class Def(val model: AnkiModel, val keys: List<String?>)

    // --- note type ---------------------------------------------------------------------------------

    /** Creates the Takoboto Vocab note type if absent (idempotent; called at app start and before each add). */
    fun ensureNoteType(): EnsureResult = synchronized(lock) { noteTypes.ensure() }

    // --- decks -------------------------------------------------------------------------------------

    override fun decks(): List<AnkiDeck> = synchronized(lock) {
        val user = reader.read { t ->
            t.where(LearningTable.DECK, "type", JsonString("user")).filter(::notDeleted)
                .mapNotNull { row -> str(row, "id")?.let { it to (str(row, "name") ?: "") } }
        }.sortedWith(compareBy({ it.second }, { it.first }))
        if (user.isEmpty()) return listOf(inbox())
        user.map { (id, name) -> AnkiDeck(map.deckAnkiId(id), name) }
    }

    override fun addDeck(name: String, description: String?): AnkiDeck = synchronized(lock) {
        val n = name.trim()
        if (n.isEmpty()) throw AnkiStoreException.Invalid("deck name is empty")
        decks().firstOrNull { it.name == n }?.let { return it }
        if (n == AnkiIds.INBOX_DECK_NAME) return inbox()
        val id = ids.newOpId(clock.nowMs())
        val payload = JsonObject(linkedMapOf("id" to JsonString(id), "name" to JsonString(n), "type" to JsonString("user")))
        when (val r = minter.mint(OpType.DECK_CREATED, payload)) {
            is MintResult.Minted -> AnkiDeck(map.deckAnkiId(id), n)
            is MintResult.Refused -> throw refusal(r.reason)
        }
    }

    override fun selectedDeckId(): Long = synchronized(lock) {
        val listed = decks()
        map.get(AnkiIds.SELECTED_DECK)?.toLongOrNull()?.takeIf { s -> listed.any { it.id == s } } ?: listed.first().id
    }

    override fun selectDeck(deckId: Long): Boolean = synchronized(lock) {
        if (deckId != AnkiIds.INBOX_DECK_ID && decks().none { it.id == deckId }) return false
        map.put(AnkiIds.SELECTED_DECK, deckId.toString())
        true
    }

    private fun inbox() = AnkiDeck(AnkiIds.INBOX_DECK_ID, AnkiIds.INBOX_DECK_NAME)

    /** The Kotobaya deck an Anki deck id means: null = deckless (the inbox). */
    private fun kotobayaDeck(ankiDeckId: Long): String? {
        if (ankiDeckId == AnkiIds.INBOX_DECK_ID) return null
        val id = map.deckId(ankiDeckId) ?: throw AnkiStoreException.NotFound("deck $ankiDeckId")
        val live = reader.read { t -> t.get(LearningTable.DECK, listOf(JsonString(id)))?.takeIf { notDeleted(it) && it["type"] == JsonString("user") } }
        return id.takeIf { live != null } ?: throw AnkiStoreException.NotFound("deck $ankiDeckId")
    }

    // --- models ------------------------------------------------------------------------------------

    private fun takobotoDef(): Def {
        val names = TakobotoVocab.FIELDS.map { it.ankiName }
        val back = "{{FrontSide}}<hr id=answer>{{${names[1]}}}<br>{{${names[2]}}}"
        return Def(
            AnkiModel(
                id = AnkiIds.TAKOBOTO_MODEL_ID,
                name = TakobotoVocab.NAME,
                fields = names,
                templates = TakobotoVocab.CARD_KINDS.map { AnkiTemplate(it, "{{${names[0]}}}", back) },
                deckId = selectedDeckId(),
            ),
            TakobotoVocab.FIELDS.map { it.key },
        )
    }

    private fun defs(): List<Def> = listOf(takobotoDef()) + map.clientModels().map { Def(it.model, it.keys) }

    override fun models(): List<AnkiModel> = synchronized(lock) { defs().map { it.model } }

    override fun currentModelId(): Long? = synchronized(lock) {
        val all = defs()
        map.get(AnkiIds.CURRENT_MODEL)?.toLongOrNull()?.takeIf { c -> all.any { it.model.id == c } } ?: AnkiIds.TAKOBOTO_MODEL_ID
    }

    override fun setCurrentModel(modelId: Long): Boolean = synchronized(lock) {
        if (defs().none { it.model.id == modelId }) return false
        map.put(AnkiIds.CURRENT_MODEL, modelId.toString())
        true
    }

    /** A model with the name and fields of an existing one is that one; otherwise the client's own model is kept. */
    override fun addModel(model: NewModel): AnkiModel = synchronized(lock) {
        if (model.name.isBlank()) throw AnkiStoreException.Invalid("model name is empty")
        if (model.fields.isEmpty()) throw AnkiStoreException.Invalid("model has no fields")
        defs().firstOrNull { it.model.name == model.name && it.model.fields == model.fields }?.let { return it.model }
        val deckId = model.deckId ?: selectedDeckId()
        if (deckId != AnkiIds.INBOX_DECK_ID) kotobayaDeck(deckId)
        val stored = map.putClientModel(
            ClientModel(
                AnkiModel(
                    id = 0,
                    name = model.name,
                    fields = model.fields,
                    templates = List(model.numCards.coerceAtLeast(1)) { AnkiTemplate("Card ${it + 1}", "", "") },
                    css = model.css,
                    deckId = deckId,
                    sortFieldIndex = model.sortFieldIndex.coerceIn(0, model.fields.size - 1),
                    type = model.type,
                    latexHeader = model.latexHeader.orEmpty(),
                    latexFooter = model.latexFooter.orEmpty(),
                ),
                TakobotoVocab.mapClientFields(model.fields),
            ),
        )
        ProviderCallLog.log("  client model '${model.name}' fields mapped to ${stored.keys}")
        stored.model
    }

    override fun addTemplate(modelId: Long, edit: TemplateEdit): Int = synchronized(lock) {
        if (modelId == AnkiIds.TAKOBOTO_MODEL_ID) throw AnkiStoreException.Invalid("${TakobotoVocab.NAME} templates are fixed")
        val m = map.clientModels().firstOrNull { it.model.id == modelId } ?: throw AnkiStoreException.NotFound("model $modelId")
        val t = applyEdit(AnkiTemplate("Card ${m.model.templates.size + 1}", "", ""), edit)
        map.putClientModel(m.copy(model = m.model.copy(templates = m.model.templates + t)))
        m.model.templates.size
    }

    override fun updateTemplate(modelId: Long, ord: Int, edit: TemplateEdit): Boolean = synchronized(lock) {
        val m = map.clientModels().firstOrNull { it.model.id == modelId } ?: return false
        if (ord !in m.model.templates.indices) return false
        val templates = m.model.templates.toMutableList().also { it[ord] = applyEdit(it[ord], edit) }
        map.putClientModel(m.copy(model = m.model.copy(templates = templates)))
        true
    }

    private fun applyEdit(t: AnkiTemplate, e: TemplateEdit) = AnkiTemplate(
        name = e.name ?: t.name,
        questionFormat = e.questionFormat ?: t.questionFormat,
        answerFormat = e.answerFormat ?: t.answerFormat,
        browserQuestionFormat = e.browserQuestionFormat ?: t.browserQuestionFormat,
        browserAnswerFormat = e.browserAnswerFormat ?: t.browserAnswerFormat,
    )

    // --- notes -------------------------------------------------------------------------------------

    override fun findNotes(filter: NoteFilter): List<AnkiNote> = synchronized(lock) {
        val all = defs()
        val requested = filter.modelId?.let { id -> all.firstOrNull { it.model.id == id } ?: return emptyList() }
        // sfld: the word itself; ask DuplicateCheck (dictionary match + local notes)
        val bySortField = filter.sortField?.let { duplicates.find(it, null)?.noteId ?: return emptyList() }
        reader.read { t ->
            val rows: List<Row> = if (bySortField != null) {
                listOfNotNull(t.get(LearningTable.NOTE, listOf(JsonString(bySortField))))
            } else {
                t.where(LearningTable.NOTE, "type", JsonString("vocab")).filter(DuplicateCheck::isLive)
            }
            val out = LinkedHashMap<String, AnkiNote>()
            for (row in rows) {
                val noteId = str(row, "id") ?: continue
                val link = map.noteByKotobayaId(noteId)
                val def = requested ?: link?.let { l -> all.firstOrNull { it.model.id == l.modelId } } ?: all.first()
                val values = values(row, def)
                if (filter.checksums != null && AnkiContract.fieldChecksum(values[0]) !in filter.checksums) continue
                // every hit is answered as the note DuplicateCheck would keep for its word
                val canonical = if (bySortField != null) row else canonical(t, row) ?: continue
                val cid = str(canonical, "id") ?: continue
                if (cid in out) continue
                val cdef = if (canonical === row) def else requested ?: all.first()
                out[cid] = view(canonical, cdef, linkFor(cid, cdef.model.id).ankiId)
            }
            out.values.toList()
        }
    }

    /** The oldest live note of [row]'s word ([DuplicateCheck.find] on the tables), [row] itself if none. */
    private fun canonical(t: LearningTables, row: Row): Row? {
        val seq = (row["entry_seq"] as? JsonNumber)?.value?.toLong()
        val f = fieldsOf(row)
        val e = DuplicateCheck.find(t, seq, EntryMatcher.clean(f["expression"]), EntryMatcher.clean(f["reading"]))
        return e?.let { t.get(LearningTable.NOTE, listOf(JsonString(it.noteId))) } ?: row
    }

    override fun note(noteId: Long): AnkiNote? = synchronized(lock) {
        val link = map.note(noteId) ?: return null
        val all = defs()
        val def = all.firstOrNull { it.model.id == link.modelId } ?: all.first()
        reader.read { t ->
            MergeReaders.resolveNote(t, link.noteId)?.takeIf(DuplicateCheck::isLive)?.let { view(it, def, noteId) }
        }
    }

    override fun addNote(note: NewNote): AnkiNote = add(note).note

    /** [addNote] with the outcome: a duplicate creates nothing and answers with the existing note. */
    fun add(note: NewNote): AddOutcome = synchronized(lock) {
        val all = defs()
        val def = all.firstOrNull { it.model.id == note.modelId } ?: throw AnkiStoreException.NotFound("model ${note.modelId}")
        if (note.fields.size != def.model.fields.size) {
            throw AnkiStoreException.Invalid("model ${def.model.id} has ${def.model.fields.size} fields, got ${note.fields.size}")
        }
        val ankiDeck = note.deckId ?: selectedDeckId()
        val deckId = kotobayaDeck(ankiDeck)
        val noteTypeId = when (val e = noteTypes.ensure()) {
            is EnsureResult.Ready -> e.noteTypeId
            is EnsureResult.Refused -> {
                map.increment(AnkiIds.COUNT_REFUSED)
                throw refusal(e.reason)
            }
        }
        val fields = mapFields(def, note.fields)
        val request = NoteRequest(
            word = fields["expression"],
            reading = fields["reading"],
            sentence = fields["sentence"]?.let { AnkiContract.stripHtmlMedia(it).trim() }?.takeIf { it.isNotEmpty() },
            origin = if (note.callerPackage?.contains("takoboto", ignoreCase = true) == true) ORIGIN_TAKOBOTO else ORIGIN_API,
            deckId = deckId,
            tags = note.tags,
            fields = fields,
            noteTypeId = noteTypeId,
        )
        when (val r = creator.create(request)) {
            is CreateResult.Created -> {
                map.increment(AnkiIds.COUNT_ADDED)
                AddOutcome.Added(viewOf(r.noteId, def, ankiDeck), r.noteId)
            }
            is CreateResult.Duplicate -> {
                map.increment(AnkiIds.COUNT_DUPLICATE)
                ProviderCallLog.log("  duplicate of an existing note; nothing created")
                AddOutcome.Duplicate(viewOf(r.existing.noteId, def, ankiDeck), r.existing.noteId)
            }
            is CreateResult.Refused -> {
                map.increment(AnkiIds.COUNT_REFUSED)
                throw when (val reason = r.reason) {
                    CreateRefusal.NoWord -> AnkiStoreException.Invalid("no word: the expression and reading fields are empty")
                    is CreateRefusal.DeckNotWritable -> AnkiStoreException.NotFound("deck $ankiDeck")
                    is CreateRefusal.UnknownNoteType -> IllegalStateException("note type ${reason.noteTypeId} is missing")
                    is CreateRefusal.Mint -> refusal(reason.refusal)
                }
            }
        }
    }

    private fun viewOf(noteId: String, def: Def, ankiDeck: Long): AnkiNote {
        val link = map.linkNote(noteId, def.model.id, ankiDeck)
        val row = reader.read { t -> t.get(LearningTable.NOTE, listOf(JsonString(noteId))) }
            ?: throw IllegalStateException("note $noteId vanished")
        return view(row, def, link.ankiId)
    }

    /**
     * Client field values by key. Expression and reading are stored as plain text (they are what
     * matching and duplicate checks compare); the others keep their HTML. Values of fields that map to
     * no key are appended to [TakobotoVocab.OVERFLOW_KEY] as `Name: value`, never dropped.
     */
    private fun mapFields(def: Def, values: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val overflow = ArrayList<String>()
        def.keys.forEachIndexed { i, key ->
            val v = values[i]
            if (v.isBlank()) return@forEachIndexed
            when (key) {
                null -> overflow += "${def.model.fields[i]}: $v"
                "expression", "reading" -> AnkiContract.stripHtmlMedia(v).trim().takeIf { it.isNotEmpty() }?.let { out[key] = it }
                else -> out[key] = v
            }
        }
        if (overflow.isNotEmpty()) {
            out[TakobotoVocab.OVERFLOW_KEY] = (listOfNotNull(out[TakobotoVocab.OVERFLOW_KEY]) + overflow).joinToString("<br>")
        }
        return out
    }

    private fun linkFor(noteId: String, modelId: Long): NoteLink =
        map.noteByKotobayaId(noteId) ?: map.linkNote(noteId, modelId, AnkiIds.INBOX_DECK_ID)

    private fun values(row: Row, def: Def): List<String> {
        val f = fieldsOf(row)
        val seq = (row["entry_seq"] as? JsonNumber)?.value?.toLong()
        val hw by lazy { seq?.let(entries::headword) }
        return def.keys.map { key ->
            when (key) {
                null -> ""
                else -> f[key] ?: when (key) {
                    "expression" -> hw?.word
                    "reading" -> hw?.reading
                    "meaning" -> hw?.meaning
                    else -> null
                } ?: ""
            }
        }
    }

    private fun view(row: Row, def: Def, ankiId: Long): AnkiNote {
        val values = values(row, def)
        return AnkiNote(
            id = ankiId,
            guid = str(row, "id") ?: "",
            modelId = def.model.id,
            fields = values,
            tags = (row["tags"] as? JsonArray)?.items.orEmpty().mapNotNull { (it as? JsonString)?.value },
            modSeconds = ((row["updated_at"] as? JsonNumber)?.value?.toLong() ?: 0L) / 1000,
            sortField = AnkiContract.stripHtmlMedia(values[def.model.sortFieldIndex.coerceIn(values.indices)]),
            checksum = AnkiContract.fieldChecksum(values[0]),
        )
    }

    override fun updateNote(noteId: Long, fields: List<String>?, tags: List<String>?): Boolean = unsupported("updateNote $noteId")

    override fun deleteNote(noteId: Long): Boolean = unsupported("deleteNote $noteId")

    override fun cards(noteId: Long): List<AnkiCard> = synchronized(lock) {
        val link = map.note(noteId) ?: return emptyList()
        val all = defs()
        val def = all.firstOrNull { it.model.id == link.modelId } ?: all.first()
        reader.read { t ->
            val row = MergeReaders.resolveNote(t, link.noteId)?.takeIf(DuplicateCheck::isLive) ?: return@read emptyList()
            val values = values(row, def)
            val meaning = def.keys.indexOf("meaning").takeIf { it >= 0 }?.let { values[it] }.orEmpty()
            t.where(LearningTable.CARD, "note_id", row.getValue("id")).filter(::notDeleted)
                .mapNotNull { str(it, "kind") }.sorted()
                .mapIndexed { ord, kind -> AnkiCard(noteId, ord, kind, link.deckAnkiId, values[0], meaning) }
        }
    }

    override fun moveCard(noteId: Long, ord: Int, deckId: Long): Boolean = unsupported("moveCard $noteId/$ord")

    override fun <T> transaction(block: () -> T): T = synchronized(lock) { store.transaction { block() } }

    private fun unsupported(what: String): Boolean {
        ProviderCallLog.unknown("$what: not supported by Kotobaya's store; nothing changed")
        return false
    }

    private fun refusal(reason: MintRefusal): RuntimeException = when (reason) {
        is MintRefusal.Invalid -> AnkiStoreException.Invalid("refused: ${reason.message} at ${reason.pointer}")
        else -> IllegalStateException("Kotobaya cannot save right now: $reason")
    }

    companion object {
        const val ORIGIN_TAKOBOTO: String = "takoboto"
        const val ORIGIN_API: String = "api"

        private fun str(row: Row, col: String): String? = (row[col] as? JsonString)?.value

        private fun notDeleted(row: Row): Boolean = (row["deleted_at"] ?: JsonNull) == JsonNull

        private fun fieldsOf(row: Row): Map<String, String> =
            (row["fields"] as? JsonObject)?.members.orEmpty().mapNotNull { (k, v) -> (v as? JsonString)?.value?.let { k to it } }.toMap()
    }
}
