package kotobaya.android.intake

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotobaya.android.vm.StateViewModel
import kotobaya.android.notes.CreateRefusal
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.NoteRequest

data class DeckOption(val id: String, val name: String)

/** What the intake sheet needs: note creation (T305b [kotobaya.android.notes.NoteCreator]) and the user's decks. */
interface IntakeServices {
    fun create(request: NoteRequest): CreateResult

    fun decks(): List<DeckOption>
}

/** Where the sheet is. */
sealed interface IntakeOutcome {
    data object Editing : IntakeOutcome

    data object Added : IntakeOutcome

    data class Duplicate(val existingNoteId: String) : IntakeOutcome

    /** The word page: a stub until it exists. */
    data class WordPage(val id: String) : IntakeOutcome

    data class Failed(val detail: String) : IntakeOutcome

    data object NoWord : IntakeOutcome

    data class Invalid(val reason: InvalidReason) : IntakeOutcome
}

/** The confirm sheet's state. */
data class IntakeUiState(
    val word: String = "",
    val reading: String = "",
    val sentence: String = "",
    val deckId: String? = null,
    /** The shared text was cut to the limit. */
    val trimmed: Boolean = false,
    val outcome: IntakeOutcome = IntakeOutcome.Editing,
    val busy: Boolean = false,
    /** The user's decks (empty until loaded or when none). */
    val decks: List<DeckOption> = emptyList(),
)

interface IntakeIntents {
    fun setWord(word: String) {}
    fun setReading(reading: String) {}
    fun setSentence(sentence: String) {}
    fun setDeck(deckId: String?) {}
    fun submit(force: Boolean = false) {}
    fun openExisting() {}
}

/** The share / process-text / deep-link sheet: parse result, edit, create the note ([IntakeServices], on IO). */
class IntakeViewModel(
    private val services: IntakeServices,
    target: IntakeTarget,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<IntakeUiState>(initial(target), io), IntakeIntents {
    private val origin = (target as? IntakeTarget.AddNote)?.origin ?: IntakeParser.ORIGIN_SHARE

    init {
        launchIo {
            val decks = runCatching { services.decks() }.getOrDefault(emptyList())
            update { it.copy(decks = decks) }
        }
    }

    override fun setWord(word: String) = update { it.copy(word = word) }

    override fun setReading(reading: String) = update { it.copy(reading = reading) }

    override fun setSentence(sentence: String) = update { it.copy(sentence = sentence) }

    override fun setDeck(deckId: String?) = update { it.copy(deckId = deckId) }

    override fun submit(force: Boolean) {
        launchIo { submitNow(force) }
    }

    /** Creates the note now (blocking; the sheet calls it on IO through [submit]). */
    fun submitNow(force: Boolean = false) {
        val s = current
        if (s.word.isBlank() && s.reading.isBlank()) {
            update { it.copy(outcome = IntakeOutcome.NoWord) }
            return
        }
        update { it.copy(busy = true) }
        val request = NoteRequest(
            word = s.word.ifBlank { null },
            reading = s.reading.ifBlank { null },
            sentence = s.sentence.ifBlank { null },
            origin = origin,
            deckId = s.deckId,
            force = force,
        )
        val outcome = try {
            when (val r = services.create(request)) {
                is CreateResult.Created -> IntakeOutcome.Added
                is CreateResult.Duplicate -> IntakeOutcome.Duplicate(r.existing.noteId)
                is CreateResult.Refused -> when (val reason = r.reason) {
                    CreateRefusal.NoWord -> IntakeOutcome.NoWord
                    else -> IntakeOutcome.Failed(reason::class.simpleName.orEmpty())
                }
            }
        } catch (e: Exception) {
            IntakeOutcome.Failed(e::class.simpleName.orEmpty())
        }
        update { it.copy(outcome = outcome, busy = false) }
    }

    override fun openExisting() = update { s ->
        (s.outcome as? IntakeOutcome.Duplicate)?.let { s.copy(outcome = IntakeOutcome.WordPage(it.existingNoteId)) } ?: s
    }

    private companion object {
        fun initial(target: IntakeTarget): IntakeUiState = when (target) {
            is IntakeTarget.AddNote -> IntakeUiState(
                word = target.word,
                reading = target.reading.orEmpty(),
                sentence = target.sentence.orEmpty(),
                trimmed = target.trimmed,
            )
            is IntakeTarget.OpenWord -> IntakeUiState(outcome = IntakeOutcome.WordPage(target.id))
            is IntakeTarget.Invalid -> IntakeUiState(outcome = IntakeOutcome.Invalid(target.reason))
        }
    }
}
