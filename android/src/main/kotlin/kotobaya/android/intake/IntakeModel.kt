package kotobaya.android.intake

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** State of the confirm sheet; [submit] blocks (call it off the main thread). */
class IntakeModel(private val services: IntakeServices, target: IntakeTarget) {
    var word by mutableStateOf("")
    var reading by mutableStateOf("")
    var sentence by mutableStateOf("")
    var deckId: String? by mutableStateOf(null)
    var trimmed by mutableStateOf(false)
    var outcome: IntakeOutcome by mutableStateOf(IntakeOutcome.Editing)
    var busy by mutableStateOf(false)
    private var origin = IntakeParser.ORIGIN_SHARE

    val decks: List<DeckOption> by lazy { runCatching { services.decks() }.getOrDefault(emptyList()) }

    init {
        when (target) {
            is IntakeTarget.AddNote -> {
                word = target.word
                reading = target.reading.orEmpty()
                sentence = target.sentence.orEmpty()
                trimmed = target.trimmed
                origin = target.origin
            }
            is IntakeTarget.OpenWord -> outcome = IntakeOutcome.WordPage(target.id)
            is IntakeTarget.Invalid -> outcome = IntakeOutcome.Invalid(target.reason)
        }
    }

    fun submit(force: Boolean = false) {
        if (word.isBlank() && reading.isBlank()) {
            outcome = IntakeOutcome.NoWord
            return
        }
        busy = true
        val request = NoteRequest(
            word = word.ifBlank { null },
            reading = reading.ifBlank { null },
            sentence = sentence.ifBlank { null },
            origin = origin,
            deckId = deckId,
            force = force,
        )
        outcome = try {
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
        } finally {
            busy = false
        }
    }

    fun openExisting() {
        (outcome as? IntakeOutcome.Duplicate)?.let { outcome = IntakeOutcome.WordPage(it.existingNoteId) }
    }
}
