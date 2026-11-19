package kotobaya.android.shell.api.decks

import kotobaya.android.dict.DictLookup
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.identity.timestamp
import kotobaya.android.shell.api.mining.DictViews
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.MergeReaders
import kotobaya.sync.client.apply.Row

/** One item of a deck as readers see it (SYNC.md 8.5): the chosen row of an effective [identity]. */
internal class VisibleItem(val identity: String, val row: Row) {
    /** The entry of an `e:` identity, else null. */
    val entrySeq: Long? get() = identity.takeIf { it.startsWith("e:") }?.substring(2)?.toLongOrNull()

    val position: Long get() = (row["position"] as? JsonNumber)?.value?.toLong() ?: 0L

    val occurrenceCount: Long get() = (row["occurrence_count"] as? JsonNumber)?.value?.toLong() ?: 0L
}

/**
 * The `Deck` and `DeckItem` views of openapi.yaml, shaped like the server's `DeckViews` (same keys, same rules): pure
 * reads of the replica. Item visibility, identities and removals use core [MergeReaders] (SYNC.md 8.5), the same
 * readers the native `DeckMembership` uses, so the shell and the native screens agree on what a deck holds.
 */
internal object DeckViews {
    private fun Row.col(name: String): JsonValue = this[name] ?: JsonNull

    private fun Row.stamp(name: String): JsonValue = (this[name] as? JsonNumber)?.value?.toLong()?.let { JsonString(timestamp(it)) } ?: JsonNull

    fun deckRow(t: LearningTables, id: String): Row? = t.get(LearningTable.DECK, listOf(JsonString(id)))

    fun isLive(row: Row): Boolean = row["deleted_at"].isNullish()

    fun type(row: Row): String = row["type"].text().orEmpty()

    /** Live items of [deckId] collapsed by identity (smallest `(position, item_key)`), in `(position, item_key)` order. */
    fun visibleItems(t: LearningTables, deckId: String): List<VisibleItem> =
        MergeReaders.visibleDeckItems(t, deckId).mapNotNull { row -> MergeReaders.effectiveIdentity(t, row)?.let { VisibleItem(it, row) } }

    /** The visible item whose identity is [identity], or null. */
    fun visibleItem(t: LearningTables, deckId: String, identity: String): VisibleItem? = visibleItems(t, deckId).firstOrNull { it.identity == identity }

    /** `Deck` (server `DECK_SELECT`): `item_count` = visible items, 0 for a smart deck. */
    fun deck(t: LearningTables, row: Row): JsonObject {
        val id = row["id"].text().orEmpty()
        val items = if (type(row) == "smart") emptyList() else visibleItems(t, id)
        val count = items.size
        return jsonObj(
            "id" to row.col("id"),
            "name" to row.col("name"),
            "type" to row.col("type"),
            "language" to (row["language"] as? JsonString ?: JsonString("ja")),
            "priority" to (row["priority"] as? JsonNumber ?: JsonNumber(0.0)),
            "enabled" to (row["enabled"] as? JsonBool ?: JsonBool(true)),
            "new_cards_per_day" to row.col("new_cards_per_day"),
            "reviews_per_day" to row.col("reviews_per_day"),
            "settings" to (row["settings"] as? JsonObject ?: JsonObject(emptyMap())),
            "media_id" to row.col("media_id"),
            "list_key" to row.col("list_key"),
            "query" to row.col("query"),
            "content_version" to (row["content_version"] as? JsonNumber ?: JsonNumber(0.0)),
            "content_blob_hash" to row.col("content_blob_hash"),
            "item_count" to JsonNumber(count.toDouble()),
            // ADR-041: core DeckProgress over the replica; null for a smart or tombstoned deck
            "progress" to DeckProgressView.progress(t, row, items),
            "created_at" to row.stamp("created_at"),
            "updated_at" to row.stamp("updated_at"),
            "deleted_at" to row.stamp("deleted_at"),
        )
    }

    /**
     * `DeckItem` of [item]: `item_key` is the identity, `note_id` the survivor of an `n:` identity, `entry` the
     * dictionary summary by L-7 steps 1-2 only (null on a miss, as the server for an entry missing from `ref`).
     */
    fun item(t: LearningTables, dict: DictLookup, deckId: String, item: VisibleItem): JsonObject {
        val seq = item.entrySeq
        val entry = seq?.let { s -> runCatching { dict.entry(s) }.getOrNull()?.let { DictViews.summary(t, it) } }
        return jsonObj(
            "deck_id" to JsonString(deckId),
            "item_key" to JsonString(item.identity),
            "entry_seq" to (seq?.let { JsonNumber(it.toDouble()) } ?: JsonNull),
            "note_id" to (item.identity.takeIf { it.startsWith("n:") }?.let { JsonString(it.substring(2)) } ?: JsonNull),
            "position" to JsonNumber(item.position.toDouble()),
            "occurrence_count" to JsonNumber(item.occurrenceCount.toDouble()),
            "added_at" to item.row.stamp("added_at"),
            "entry" to (entry ?: JsonNull),
            "status" to (seq?.let { JsonString(DictViews.status(t, it)) } ?: JsonNull),
        )
    }

    /** Largest `position` of the deck's live rows (any identity), 0 when none (server `maxPosition`). */
    fun maxPosition(t: LearningTables, deckId: String): Long =
        t.where(LearningTable.DECK_ITEM, "deck_id", JsonString(deckId)).filter { it["deleted_at"].isNullish() }
            .maxOfOrNull { (it["position"] as? JsonNumber)?.value?.toLong() ?: 0L } ?: 0L

    /** The position of the live row with exactly [key], or null (server `livePosition`). */
    fun livePosition(t: LearningTables, deckId: String, key: String): Long? =
        t.get(LearningTable.DECK_ITEM, listOf(JsonString(deckId), JsonString(key)))
            ?.takeIf { it["deleted_at"].isNullish() }
            ?.let { (it["position"] as? JsonNumber)?.value?.toLong() ?: 0L }

    /** Every live key of the deck whose effective identity is [identity], sorted (SYNC.md 8.5 removal; server `liveKeysOf`). */
    fun liveKeysOf(t: LearningTables, deckId: String, identity: String): List<String> =
        t.where(LearningTable.DECK_ITEM, "deck_id", JsonString(deckId)).filter { it["deleted_at"].isNullish() }
            .filter { MergeReaders.effectiveIdentity(t, it) == identity }
            .mapNotNull { it["item_key"].text() }
            .sorted()
}
