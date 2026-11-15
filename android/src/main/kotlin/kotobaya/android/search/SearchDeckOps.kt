package kotobaya.android.search

import kotobaya.android.decks.DeckChoice
import kotobaya.android.decks.DeckMembership
import kotobaya.android.decks.DeckTarget
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.NoteCreator
import kotobaya.android.notes.NoteRequest
import kotobaya.android.sync.SqlLearningTables
import kotobaya.sync.client.mint.MintResult

/** Blocking deck writes of the Search tab (AND-ADD2DECK): membership over [DeckMembership], notes over [NoteCreator]. */
class SearchDeckOps(private val membership: DeckMembership, private val creator: NoteCreator<SqlLearningTables>) {
    fun decks(target: DeckTarget): List<DeckChoice> = membership.decks(target)

    /** True when the item is now as asked (including already so). */
    fun set(deckId: String, target: DeckTarget, member: Boolean): Boolean =
        membership.set(deckId, target, member).let { it == null || it is MintResult.Minted }

    fun createDeck(name: String): String? = membership.createDeck(name)

    /** "Add to my notes": one `NoteCreator` call, which also mints the deck item when [deckId] is given. */
    fun addToNotes(word: String, reading: String, deckId: String?): AddNoteOutcome =
        when (creator.create(NoteRequest(word = word, reading = reading, origin = "manual", deckId = deckId))) {
            is CreateResult.Created -> AddNoteOutcome.Added
            is CreateResult.Duplicate -> AddNoteOutcome.Duplicate
            is CreateResult.Refused -> AddNoteOutcome.Failed
        }
}
