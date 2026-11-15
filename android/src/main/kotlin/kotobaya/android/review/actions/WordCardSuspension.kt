package kotobaya.android.review.actions

import kotobaya.android.review.CardRows
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.DeviceStore

/** A live card of a word and whether it is suspended. */
data class WordCardState(val cardId: String, val suspended: Boolean)

/**
 * Suspend / unsuspend over every live card of a word's notes (notes with that `entry_seq`), shared by the deck word
 * sheet and the word page. Each change is one `card.suspended` / `card.unsuspended` op via [OpMinter] (one
 * transaction); a card already in the wanted state mints nothing.
 */
class WordCardSuspension<L : LearningTables>(private val store: DeviceStore<L>, private val minter: OpMinter<L>) {
    /** Live cards of the live notes holding [entrySeq]. */
    fun cardsOf(entrySeq: Long): List<WordCardState> = store.transaction { tx ->
        val t = tx.learning
        t.where(LearningTable.NOTE, "entry_seq", JsonNumber(entrySeq.toDouble())).filter(CardRows::live).flatMap { note ->
            val noteId = CardRows.str(note["id"]) ?: return@flatMap emptyList()
            t.where(LearningTable.CARD, "note_id", JsonString(noteId)).filter(CardRows::live).mapNotNull { c ->
                CardRows.str(c["id"])?.let { WordCardState(it, (c["suspended"] as? JsonBool)?.value ?: false) }
            }
        }
    }

    /** Suspends ([suspend] true) or unsuspends every live card of [entrySeq]; the number of ops minted, null if refused. */
    fun setForWord(entrySeq: Long, suspend: Boolean): Int? = set(cardsOf(entrySeq), suspend)

    /** The same for the given cards (the word page's "My cards"); ids that are gone or already so mint nothing. */
    fun setForCards(cardIds: Collection<String>, suspend: Boolean): Int? {
        val live = store.transaction { tx ->
            cardIds.toSet().mapNotNull { id ->
                CardRows.get(tx.learning, LearningTable.CARD, id)?.takeIf(CardRows::live)
                    ?.let { WordCardState(id, (it["suspended"] as? JsonBool)?.value ?: false) }
            }
        }
        return set(live, suspend)
    }

    private fun set(cards: List<WordCardState>, suspend: Boolean): Int? {
        val type = if (suspend) OpType.CARD_SUSPENDED else OpType.CARD_UNSUSPENDED
        val reqs = cards.filter { it.suspended != suspend }.map { MintRequest(type, JsonObject(linkedMapOf("id" to JsonString(it.cardId)))) }
        if (reqs.isEmpty()) return 0
        return if (minter.mintAll(reqs) is MintResult.Refused) null else reqs.size
    }
}
