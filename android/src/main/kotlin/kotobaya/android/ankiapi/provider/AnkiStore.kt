package kotobaya.android.ankiapi.provider

/**
 * What [AnkiProvider] needs from a collection. The provider only translates FlashCardsContract
 * calls; everything stateful lives behind this interface. [InMemoryAnkiStore] is the stand-in used
 * until the real, op-backed store (T305c) is registered in Koin.
 *
 * Implementations must be thread-safe: provider calls arrive on binder threads.
 * Failures a client can cause throw [AnkiStoreException]; the provider maps them the way AnkiDroid
 * reports them (IllegalArgumentException across the binder).
 */
interface AnkiStore {
    fun decks(): List<AnkiDeck>

    /** Creates a deck, or returns the existing deck with that exact name. */
    fun addDeck(name: String, description: String?): AnkiDeck

    fun selectedDeckId(): Long

    fun selectDeck(deckId: Long): Boolean

    fun models(): List<AnkiModel>

    fun currentModelId(): Long?

    fun setCurrentModel(modelId: Long): Boolean

    fun addModel(model: NewModel): AnkiModel

    /** Appends a template; returns its ord. Throws [AnkiStoreException.NotFound] for an unknown model. */
    fun addTemplate(modelId: Long, edit: TemplateEdit): Int

    fun updateTemplate(modelId: Long, ord: Int, edit: TemplateEdit): Boolean

    fun findNotes(filter: NoteFilter): List<AnkiNote>

    fun note(noteId: Long): AnkiNote?

    /** Throws [AnkiStoreException] for an unknown model/deck or a field count that doesn't match the model. */
    fun addNote(note: NewNote): AnkiNote

    fun updateNote(noteId: Long, fields: List<String>?, tags: List<String>?): Boolean

    fun deleteNote(noteId: Long): Boolean

    fun cards(noteId: Long): List<AnkiCard>

    fun moveCard(noteId: Long, ord: Int, deckId: Long): Boolean

    /** Runs [block] atomically (bulkInsert). */
    fun <T> transaction(block: () -> T): T = block()
}

data class AnkiDeck(
    val id: Long,
    val name: String,
    val description: String = "",
    val dynamic: Boolean = false,
)

data class AnkiTemplate(
    val name: String,
    val questionFormat: String,
    val answerFormat: String,
    val browserQuestionFormat: String = "",
    val browserAnswerFormat: String = "",
)

data class AnkiModel(
    val id: Long,
    val name: String,
    val fields: List<String>,
    val templates: List<AnkiTemplate>,
    val css: String = "",
    val deckId: Long,
    val sortFieldIndex: Int = 0,
    val type: Int = 0,
    val latexHeader: String = "",
    val latexFooter: String = "",
)

data class AnkiNote(
    val id: Long,
    val guid: String,
    val modelId: Long,
    val fields: List<String>,
    val tags: List<String>,
    /** Seconds since the epoch, as Anki stores `mod`. */
    val modSeconds: Long,
    val sortField: String,
    val checksum: Long,
)

data class AnkiCard(
    val noteId: Long,
    val ord: Int,
    val name: String,
    val deckId: Long,
    val question: String,
    val answer: String,
)

data class NewModel(
    val name: String,
    val fields: List<String>,
    val numCards: Int,
    val css: String,
    val deckId: Long?,
    val sortFieldIndex: Int,
    val type: Int,
    val latexHeader: String?,
    val latexFooter: String?,
    val callerPackage: String?,
)

data class NewNote(
    val modelId: Long,
    val fields: List<String>,
    val tags: List<String>,
    val deckId: Long?,
    val callerPackage: String?,
)

data class TemplateEdit(
    val name: String? = null,
    val questionFormat: String? = null,
    val answerFormat: String? = null,
    val browserQuestionFormat: String? = null,
    val browserAnswerFormat: String? = null,
)

/** Duplicate-check filter parsed from a `notes`/`notes_v2` selection; null means "any". */
data class NoteFilter(
    val modelId: Long? = null,
    val checksums: Set<Long>? = null,
    val sortField: String? = null,
)

sealed class AnkiStoreException(message: String) : RuntimeException(message) {
    class NotFound(message: String) : AnkiStoreException(message)

    class Invalid(message: String) : AnkiStoreException(message)
}
