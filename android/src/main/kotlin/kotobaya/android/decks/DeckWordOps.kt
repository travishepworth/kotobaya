package kotobaya.android.decks

import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.NoteCreator
import kotobaya.android.notes.NoteRequest
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter

/** What the deck inspector does to words (blocking; the ViewModel calls it on IO). */
interface DeckWordActions {
    /**
     * Sets the override of every entry in [entrySeqs] to [status] (`known`, `never_forget`, `blacklisted`,
     * `ignored`), or clears it when [status] is null. One transaction; entries already so mint nothing.
     * Returns the number of ops minted, or null when the minter refused.
     */
    fun setStatus(entrySeqs: Collection<Long>, status: String?): Int?

    /** Takes the word off the user deck (`deck.item_removed`). */
    fun removeFromDeck(deckId: String, entrySeq: Long): Boolean

    /** "Add to my notes" (a deckless note; the word stays in the deck). */
    fun addToNotes(word: String, reading: String): Boolean
}

/**
 * [DeckWordActions] over the replica: `word.status_set` / `word.status_cleared` ops minted through [OpMinter] (which
 * also applies them locally, so the lists update offline) and the existing [DeckMembership] / [NoteCreator] paths.
 */
class SqlDeckWordActions(
    private val store: SqlDelightDeviceStore,
    private val minter: OpMinter<SqlLearningTables>,
    private val membership: DeckMembership,
    private val creator: NoteCreator<SqlLearningTables>,
    private val afterChange: () -> Unit = {},
) : DeckWordActions {
    override fun setStatus(entrySeqs: Collection<Long>, status: String?): Int? {
        val seqs = entrySeqs.distinct()
        if (seqs.isEmpty()) return 0
        val result = store.transaction { tx ->
            val reqs = seqs.mapNotNull { seq ->
                val current = tx.learning.get(LearningTable.WORD_STATUS, listOf(JsonNumber(seq.toDouble())))
                    ?.get("override_status")?.let { (it as? JsonString)?.value }
                when {
                    current == status -> null
                    status == null -> MintRequest(OpType.WORD_STATUS_CLEARED, obj("entry_seq" to JsonNumber(seq.toDouble())))
                    else -> MintRequest(
                        OpType.WORD_STATUS_SET,
                        obj("entry_seq" to JsonNumber(seq.toDouble()), "status" to JsonString(status), "origin" to JsonString("manual")),
                    )
                }
            }
            if (reqs.isEmpty()) reqs.size to null else reqs.size to minter.mintAll(reqs)
        }
        val (count, minted) = result
        if (minted is MintResult.Refused) return null
        if (count > 0) runCatching { afterChange() }
        return count
    }

    override fun removeFromDeck(deckId: String, entrySeq: Long): Boolean =
        membership.set(deckId, DeckTarget(entrySeq = entrySeq), member = false).let { it == null || it is MintResult.Minted }

    override fun addToNotes(word: String, reading: String): Boolean =
        creator.create(NoteRequest(word = word, reading = reading, origin = "manual")) !is CreateResult.Refused

    private fun obj(vararg p: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*p))
}
