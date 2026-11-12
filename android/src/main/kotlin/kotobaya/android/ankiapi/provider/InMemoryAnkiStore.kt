package kotobaya.android.ankiapi.provider

/**
 * In-memory [AnkiStore]: the test fake, and the provider's fallback until the real store (T305c)
 * is registered. Lost on process death. Ids are deterministic (a counter starting at [firstId]) so
 * fixtures can name them. Seeds deck "Default" (id 1) and note type "Basic" (id 1, Front/Back), as
 * a fresh AnkiDroid collection has.
 */
class InMemoryAnkiStore(
    private val firstId: Long = 1000,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : AnkiStore {
    private val lock = Any()
    private var nextId = firstId
    private val decks = mutableListOf(AnkiDeck(DEFAULT_DECK_ID, "Default"))
    private val models = mutableListOf(
        AnkiModel(
            id = BASIC_MODEL_ID,
            name = "Basic",
            fields = listOf("Front", "Back"),
            templates = listOf(AnkiTemplate("Card 1", "{{Front}}", "{{FrontSide}}<hr id=answer>{{Back}}")),
            deckId = DEFAULT_DECK_ID,
        ),
    )
    private val notes = mutableListOf<AnkiNote>()
    private val cards = mutableListOf<CardRow>()
    private var selectedDeck = DEFAULT_DECK_ID
    private var currentModel: Long? = BASIC_MODEL_ID

    private data class CardRow(val noteId: Long, val ord: Int, val deckId: Long)

    private fun newId(): Long = nextId++

    override fun decks(): List<AnkiDeck> = synchronized(lock) { decks.toList() }

    override fun addDeck(name: String, description: String?): AnkiDeck = synchronized(lock) {
        if (name.isBlank()) throw AnkiStoreException.Invalid("deck name is empty")
        decks.firstOrNull { it.name == name }
            ?: AnkiDeck(newId(), name, description.orEmpty()).also { decks.add(it) }
    }

    override fun selectedDeckId(): Long = synchronized(lock) { selectedDeck }

    override fun selectDeck(deckId: Long): Boolean = synchronized(lock) {
        if (decks.none { it.id == deckId }) return false
        selectedDeck = deckId
        true
    }

    override fun models(): List<AnkiModel> = synchronized(lock) { models.toList() }

    override fun currentModelId(): Long? = synchronized(lock) { currentModel }

    override fun setCurrentModel(modelId: Long): Boolean = synchronized(lock) {
        if (models.none { it.id == modelId }) return false
        currentModel = modelId
        true
    }

    override fun addModel(model: NewModel): AnkiModel = synchronized(lock) {
        if (model.name.isBlank()) throw AnkiStoreException.Invalid("model name is empty")
        if (model.fields.isEmpty()) throw AnkiStoreException.Invalid("model has no fields")
        val deckId = model.deckId ?: selectedDeck
        if (decks.none { it.id == deckId }) throw AnkiStoreException.NotFound("deck $deckId")
        AnkiModel(
            id = newId(),
            name = model.name,
            fields = model.fields,
            templates = List(model.numCards.coerceAtLeast(1)) { AnkiTemplate("Card ${it + 1}", "", "") },
            css = model.css,
            deckId = deckId,
            sortFieldIndex = model.sortFieldIndex.coerceIn(0, model.fields.size - 1),
            type = model.type,
            latexHeader = model.latexHeader.orEmpty(),
            latexFooter = model.latexFooter.orEmpty(),
        ).also { models.add(it) }
    }

    override fun addTemplate(modelId: Long, edit: TemplateEdit): Int = synchronized(lock) {
        val i = modelIndex(modelId)
        val m = models[i]
        val t = applyEdit(AnkiTemplate("Card ${m.templates.size + 1}", "", ""), edit)
        models[i] = m.copy(templates = m.templates + t)
        m.templates.size
    }

    override fun updateTemplate(modelId: Long, ord: Int, edit: TemplateEdit): Boolean = synchronized(lock) {
        val i = models.indexOfFirst { it.id == modelId }
        if (i < 0) return false
        val m = models[i]
        if (ord !in m.templates.indices) return false
        models[i] = m.copy(templates = m.templates.toMutableList().also { it[ord] = applyEdit(it[ord], edit) })
        true
    }

    override fun findNotes(filter: NoteFilter): List<AnkiNote> = synchronized(lock) {
        notes.filter { n ->
            (filter.modelId == null || n.modelId == filter.modelId) &&
                (filter.checksums == null || n.checksum in filter.checksums) &&
                (filter.sortField == null || n.sortField == filter.sortField)
        }
    }

    override fun note(noteId: Long): AnkiNote? = synchronized(lock) { notes.firstOrNull { it.id == noteId } }

    override fun addNote(note: NewNote): AnkiNote = synchronized(lock) {
        val model = models.firstOrNull { it.id == note.modelId }
            ?: throw AnkiStoreException.NotFound("model ${note.modelId}")
        if (note.fields.size != model.fields.size) {
            throw AnkiStoreException.Invalid(
                "model ${model.id} has ${model.fields.size} fields, got ${note.fields.size}",
            )
        }
        val deckId = note.deckId ?: model.deckId
        if (decks.none { it.id == deckId }) throw AnkiStoreException.NotFound("deck $deckId")
        val id = newId()
        val sfld = note.fields[model.sortFieldIndex]
        val row = AnkiNote(
            id = id,
            guid = "kb$id",
            modelId = model.id,
            fields = note.fields,
            tags = note.tags,
            modSeconds = clockSeconds(),
            sortField = sfld,
            checksum = AnkiContract.fieldChecksum(sfld),
        )
        notes.add(row)
        model.templates.indices.forEach { cards.add(CardRow(id, it, deckId)) }
        row
    }

    override fun updateNote(noteId: Long, fields: List<String>?, tags: List<String>?): Boolean = synchronized(lock) {
        val i = notes.indexOfFirst { it.id == noteId }
        if (i < 0) return false
        val old = notes[i]
        val model = models.first { it.id == old.modelId }
        val newFields = fields ?: old.fields
        if (newFields.size != model.fields.size) {
            throw AnkiStoreException.Invalid("model ${model.id} has ${model.fields.size} fields, got ${newFields.size}")
        }
        val sfld = newFields[model.sortFieldIndex]
        notes[i] = old.copy(
            fields = newFields,
            tags = tags ?: old.tags,
            modSeconds = clockSeconds(),
            sortField = sfld,
            checksum = AnkiContract.fieldChecksum(sfld),
        )
        true
    }

    override fun deleteNote(noteId: Long): Boolean = synchronized(lock) {
        cards.removeAll { it.noteId == noteId }
        notes.removeAll { it.id == noteId }
    }

    override fun cards(noteId: Long): List<AnkiCard> = synchronized(lock) {
        val note = notes.firstOrNull { it.id == noteId } ?: return emptyList()
        val model = models.first { it.id == note.modelId }
        cards.filter { it.noteId == noteId }.map { c ->
            val t = model.templates.getOrNull(c.ord)
            fun render(fmt: String) =
                model.fields.foldIndexed(fmt) { i, acc, f -> acc.replace("{{$f}}", note.fields.getOrElse(i) { "" }) }
            val q = render(t?.questionFormat.orEmpty())
            AnkiCard(
                noteId = noteId,
                ord = c.ord,
                name = t?.name ?: "Card ${c.ord + 1}",
                deckId = c.deckId,
                question = q,
                answer = render(t?.answerFormat.orEmpty()).replace("{{FrontSide}}", q),
            )
        }
    }

    override fun moveCard(noteId: Long, ord: Int, deckId: Long): Boolean = synchronized(lock) {
        if (decks.none { it.id == deckId }) throw AnkiStoreException.NotFound("deck $deckId")
        val i = cards.indexOfFirst { it.noteId == noteId && it.ord == ord }
        if (i < 0) return false
        cards[i] = cards[i].copy(deckId = deckId)
        true
    }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) { block() }

    private fun modelIndex(modelId: Long): Int =
        models.indexOfFirst { it.id == modelId }.takeIf { it >= 0 }
            ?: throw AnkiStoreException.NotFound("model $modelId")

    private fun applyEdit(t: AnkiTemplate, e: TemplateEdit) = AnkiTemplate(
        name = e.name ?: t.name,
        questionFormat = e.questionFormat ?: t.questionFormat,
        answerFormat = e.answerFormat ?: t.answerFormat,
        browserQuestionFormat = e.browserQuestionFormat ?: t.browserQuestionFormat,
        browserAnswerFormat = e.browserAnswerFormat ?: t.browserAnswerFormat,
    )

    companion object {
        const val DEFAULT_DECK_ID: Long = 1
        const val BASIC_MODEL_ID: Long = 1
    }
}
