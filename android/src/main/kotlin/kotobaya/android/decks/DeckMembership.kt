package kotobaya.android.decks

import kotobaya.android.notes.NoteCreator
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.MergeReaders
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.sync.client.store.OpIdSource

/** What goes into a deck: a dictionary word ([entrySeq]) and/or a note ([noteId]). At least one is set. */
data class DeckTarget(val entrySeq: Long? = null, val noteId: String? = null)

/** A live user deck, [member] when the target is already in it. */
data class DeckChoice(val id: String, val name: String, val member: Boolean)

/**
 * Manual deck membership (AND-ADD2DECK): which user decks hold a word or note, and adding / removing it, as
 * `deck.item_put` / `deck.item_removed` ops minted offline (SCHEMA.md "Deck items", OPS.md `deck_item`).
 * The item key is `e:<entry_seq>` when the word has an entry (the note's, if only a note is given), else `n:<note id>`.
 */
class DeckMembership(
    private val store: SqlDelightDeviceStore,
    private val minter: OpMinter<SqlLearningTables>,
    private val ids: OpIdSource,
    private val clock: Clock,
    private val afterChange: () -> Unit = {},
) {
    /** The key a deck item for [target] has: `e:<seq>` if it has an entry, else `n:<note id>`; null if neither. */
    fun itemKey(target: DeckTarget): String? = store.read { keyOf(it.learning, target) }

    /** Live user decks in draw order (priority ascending, then name), flagged with the target's membership. */
    fun decks(target: DeckTarget): List<DeckChoice> = store.read { tx ->
        val t = tx.learning
        val identity = keyOf(t, target)
        userDecks(t).map { d ->
            val id = str(d, "id")
            DeckChoice(id, str(d, "name"), identity != null && visibleIdentity(t, id, identity) != null)
        }
    }

    /** Names of the decks holding [target] (draw order). */
    fun memberOf(target: DeckTarget): List<String> = decks(target).filter { it.member }.map { it.name }

    /**
     * Puts [target] into ([member] true) or takes it out of the user deck [deckId]; a no-op (null) when already so.
     * Removing mints one `deck.item_removed` for every live key of the same effective identity (OPS.md client duty).
     */
    fun set(deckId: String, target: DeckTarget, member: Boolean): MintResult? {
        val result = store.transaction { tx ->
            val t = tx.learning
            val deck = t.get(LearningTable.DECK, listOf(JsonString(deckId)))
            if (deck == null || !live(deck) || deck["type"] != JsonString("user")) return@transaction null
            val key = keyOf(t, target) ?: return@transaction null
            val shown = visibleIdentity(t, deckId, key)
            when {
                member && shown != null -> null
                member -> minter.mint(
                    OpType.DECK_ITEM_PUT,
                    obj("deck_id" to JsonString(deckId), "item_key" to JsonString(key), "position" to JsonNumber(nextPosition(t, deckId).toDouble())),
                )
                shown == null -> null
                else -> minter.mintAll(
                    MergeReaders.keysToRemove(t, deckId, str(shown, "item_key")).map {
                        MintRequest(OpType.DECK_ITEM_REMOVED, obj("deck_id" to JsonString(deckId), "item_key" to JsonString(it)))
                    },
                )
            }
        }
        if (result is MintResult.Minted) runCatching { afterChange() }
        return result
    }

    /** Creates a user deck named [name]; returns its id, or null when the name is blank or the minter refused. */
    fun createDeck(name: String): String? {
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return null
        val id = ids.newOpId(clock.nowMs())
        val r = minter.mint(OpType.DECK_CREATED, obj("id" to JsonString(id), "name" to JsonString(n), "type" to JsonString("user")))
        if (r is MintResult.Minted) runCatching { afterChange() }
        return id.takeIf { r is MintResult.Minted }
    }

    /** The identity key of [target]: `e:<seq>`, from the entry or (merge-resolved) the note's `entry_seq`; else `n:<id>`. */
    private fun keyOf(t: LearningTables, target: DeckTarget): String? {
        target.entrySeq?.let { return "e:$it" }
        val noteId = target.noteId ?: return null
        val note = MergeReaders.resolveNote(t, noteId)
        val seq = note?.get("entry_seq")
        return if (seq is JsonNumber) "e:${seq.value.toLong()}" else "n:${note?.let { str(it, "id") } ?: noteId}"
    }

    private fun visibleIdentity(t: LearningTables, deckId: String, identity: String): Row? =
        MergeReaders.visibleDeckItems(t, deckId).firstOrNull { MergeReaders.effectiveIdentity(t, it) == identity }

    private fun userDecks(t: LearningTables): List<Row> =
        t.all(LearningTable.DECK).filter { live(it) && it["type"] == JsonString("user") }
            .sortedWith(compareBy<Row>({ (it["priority"] as? JsonNumber)?.value ?: 0.0 }, { str(it, "name") }, { str(it, "id") }))

    private fun nextPosition(t: LearningTables, deckId: String): Long =
        (t.where(LearningTable.DECK_ITEM, "deck_id", JsonString(deckId)).filter(::live)
            .maxOfOrNull { (it["position"] as? JsonNumber)?.value?.toLong() ?: 0L } ?: 0L) + NoteCreator.DECK_POSITION_STEP

    private fun live(row: Row) = (row["deleted_at"] ?: JsonNull) == JsonNull

    private fun str(row: Row, col: String): String = (row[col] as? JsonString)?.value.orEmpty()

    private fun obj(vararg members: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*members))
}
