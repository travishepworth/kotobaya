package kotobaya.android.ankiapi.store

import kotobaya.android.ankiapi.provider.AnkiCard
import kotobaya.android.ankiapi.provider.AnkiDeck
import kotobaya.android.ankiapi.provider.AnkiModel
import kotobaya.android.ankiapi.provider.AnkiNote
import kotobaya.android.ankiapi.provider.AnkiStore
import kotobaya.android.ankiapi.provider.NewModel
import kotobaya.android.ankiapi.provider.NewNote
import kotobaya.android.ankiapi.provider.NoteFilter
import kotobaya.android.ankiapi.provider.TemplateEdit

/**
 * The [AnkiStore] handed to `AnkiStoreHolder` from `Application.onCreate`: registered eagerly (so a
 * provider call never times out waiting for it) but resolved on the first call, on the binder thread,
 * so app start does not open the learning database on the main thread.
 */
class DeferredAnkiStore(resolve: () -> AnkiStore) : AnkiStore {
    private val store: AnkiStore by lazy(resolve)

    override fun decks(): List<AnkiDeck> = store.decks()

    override fun addDeck(name: String, description: String?): AnkiDeck = store.addDeck(name, description)

    override fun selectedDeckId(): Long = store.selectedDeckId()

    override fun selectDeck(deckId: Long): Boolean = store.selectDeck(deckId)

    override fun models(): List<AnkiModel> = store.models()

    override fun currentModelId(): Long? = store.currentModelId()

    override fun setCurrentModel(modelId: Long): Boolean = store.setCurrentModel(modelId)

    override fun addModel(model: NewModel): AnkiModel = store.addModel(model)

    override fun addTemplate(modelId: Long, edit: TemplateEdit): Int = store.addTemplate(modelId, edit)

    override fun updateTemplate(modelId: Long, ord: Int, edit: TemplateEdit): Boolean = store.updateTemplate(modelId, ord, edit)

    override fun findNotes(filter: NoteFilter): List<AnkiNote> = store.findNotes(filter)

    override fun note(noteId: Long): AnkiNote? = store.note(noteId)

    override fun addNote(note: NewNote): AnkiNote = store.addNote(note)

    override fun updateNote(noteId: Long, fields: List<String>?, tags: List<String>?): Boolean = store.updateNote(noteId, fields, tags)

    override fun deleteNote(noteId: Long): Boolean = store.deleteNote(noteId)

    override fun cards(noteId: Long): List<AnkiCard> = store.cards(noteId)

    override fun moveCard(noteId: Long, ord: Int, deckId: Long): Boolean = store.moveCard(noteId, ord, deckId)

    override fun <T> transaction(block: () -> T): T = store.transaction(block)
}
