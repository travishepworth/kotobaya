package kotobaya.sync.client.apply

import kotobaya.contracts.Uuid
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue

/**
 * Readers of SYNC.md 8.5: references to a merge loser are never rewritten; readers resolve them
 * through `merged_into_id` (one hop: merges are flattened, 8.2 step 3). Shared by the queue loader,
 * deck screens and confusable lists (T304a, T307). Pure reads of [LearningTables].
 */
public object MergeReaders {
    /** The note a reference to [noteId] means: its survivor if it was merged, else itself; null if absent. */
    public fun resolveNote(tables: LearningTables, noteId: Uuid): Row? = resolve(tables, LearningTable.NOTE, noteId)

    /** The card a reference to [cardId] means (e.g. `review_log.card_id`): its survivor if merged, else itself. */
    public fun resolveCard(tables: LearningTables, cardId: Uuid): Row? = resolve(tables, LearningTable.CARD, cardId)

    private fun resolve(tables: LearningTables, table: LearningTable, id: Uuid): Row? {
        val row = tables.get(table, listOf(JsonString(id))) ?: return null
        val target = (row["merged_into_id"] as? JsonString)?.value ?: return row
        return tables.get(table, listOf(JsonString(target)))
    }

    /**
     * Effective identity of a deck item (8.5): `e:<seq>` if its key is `e:<seq>` or its resolved note
     * has a non-null `entry_seq`, else `n:<resolved note id>`. Null for an `n:` item whose note is absent.
     */
    public fun effectiveIdentity(tables: LearningTables, item: Row): String? {
        val key = (item["item_key"] as JsonString).value
        if (key.startsWith("e:")) return key
        val note = resolveNote(tables, key.substring(2)) ?: return null
        val seq = note["entry_seq"]
        return if (seq is JsonNumber) "e:${seq.value.toLong()}" else "n:${(note.getValue("id") as JsonString).value}"
    }

    /**
     * The items of deck [deckId] as a reader shows them (8.5): live rows (`deleted_at` NULL), an `n:`
     * item whose resolved note is tombstoned (or absent) hidden, and rows with the same effective
     * identity collapsed to the one with the smallest `(position, item_key)`. Ordered by
     * `(position, item_key)`.
     */
    public fun visibleDeckItems(tables: LearningTables, deckId: Uuid): List<Row> {
        val chosen = LinkedHashMap<String, Row>()
        for (item in liveItems(tables, deckId).sortedWith(itemOrder)) {
            val key = (item["item_key"] as JsonString).value
            if (key.startsWith("n:")) {
                val note = resolveNote(tables, key.substring(2))
                if (note == null || (note["deleted_at"] ?: JsonNull) != JsonNull) continue
            }
            val identity = effectiveIdentity(tables, item) ?: continue
            if (identity !in chosen) chosen[identity] = item
        }
        return chosen.values.toList()
    }

    /**
     * The keys a client MUST remove (one `deck.item_removed` each, in one transaction) when the user
     * removes the item shown for [itemKey] of deck [deckId] (8.5): every live key of that deck with the
     * same effective identity, as resolved locally. Sorted.
     */
    public fun keysToRemove(tables: LearningTables, deckId: Uuid, itemKey: String): List<String> {
        val item = tables.get(LearningTable.DECK_ITEM, listOf(JsonString(deckId), JsonString(itemKey))) ?: return listOf(itemKey)
        val identity = effectiveIdentity(tables, item) ?: return listOf(itemKey)
        return liveItems(tables, deckId)
            .filter { effectiveIdentity(tables, it) == identity }
            .map { (it["item_key"] as JsonString).value }
            .sorted()
    }

    /** A confusable pair as readers see it (8.5). */
    public data class ConfusablePair(
        /** The smaller resolved note id. */
        public val noteA: Uuid,
        /** The larger resolved note id. */
        public val noteB: Uuid,
        /** `reason`. */
        public val reason: String,
        /** Status of the row with the highest `(rank, hlc)` (rank: suggested 0, decisions 1). */
        public val status: String,
    )

    /**
     * Confusable pairs (8.5): both ids resolved; rows resolving to the same unordered pair and reason
     * are one pair with the status of the row with the highest `(rank, hlc)`; a row whose ids resolve
     * to the same note is ignored; a pair with a tombstoned (or absent) resolved note is hidden.
     */
    public fun confusablePairs(tables: LearningTables): List<ConfusablePair> {
        class Best(val rank: Int, val hlc: String, val status: String)
        val best = LinkedHashMap<Triple<String, String, String>, Best>()
        for (row in tables.all(LearningTable.CONFUSABLE)) {
            val a = resolveNote(tables, (row.getValue("note_a_id") as JsonString).value) ?: continue
            val b = resolveNote(tables, (row.getValue("note_b_id") as JsonString).value) ?: continue
            val ai = (a.getValue("id") as JsonString).value
            val bi = (b.getValue("id") as JsonString).value
            if (ai == bi) continue
            if (deleted(a) || deleted(b)) continue
            val status = (row.getValue("status") as JsonString).value
            val hlc = (row.getValue("hlc") as JsonString).value
            val rank = if (status == "suggested") 0 else 1
            val k = Triple(minOf(ai, bi), maxOf(ai, bi), (row.getValue("reason") as JsonString).value)
            val cur = best[k]
            if (cur == null || rank > cur.rank || (rank == cur.rank && hlc > cur.hlc)) best[k] = Best(rank, hlc, status)
        }
        return best.entries
            .map { (k, v) -> ConfusablePair(k.first, k.second, k.third, v.status) }
            .sortedWith(compareBy({ it.noteA }, { it.noteB }, { it.reason }))
    }

    private fun deleted(row: Row): Boolean = (row["deleted_at"] ?: JsonNull) != JsonNull

    private fun liveItems(tables: LearningTables, deckId: Uuid): List<Row> =
        tables.where(LearningTable.DECK_ITEM, "deck_id", JsonString(deckId)).filter { (it["deleted_at"] ?: JsonNull) == JsonNull }

    private val itemOrder: Comparator<Row> = compareBy<Row>({ position(it["position"]) }, { (it["item_key"] as JsonString).value })

    private fun position(v: JsonValue?): Long = (v as? JsonNumber)?.value?.toLong() ?: 0L
}
