package kotobaya.android.shell.api.actions

import kotobaya.android.shell.api.identity.timestamp
import kotobaya.android.shell.api.review.AnswerViews
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.MergeReaders
import kotobaya.sync.client.apply.Row

/**
 * The `Note` and `WordStatus` views (openapi.yaml), shaped like the server's `NoteViews.note` and
 * `WordStatusApi.status`: pure reads of the replica. Cards are [AnswerViews.card], the view `getCard` and the
 * review results already share with the server.
 */
internal object ActionViews {
    private fun Row.col(name: String): JsonValue = this[name] ?: JsonNull

    private fun Row.stamp(name: String): JsonValue = (this[name] as? JsonNumber)?.value?.toLong()?.let { JsonString(timestamp(it)) } ?: JsonNull

    private fun live(r: Row): Boolean = r["deleted_at"].isNullish()

    /** The note answering for [id] (its survivor when merged, one hop: merges are flattened, SYNC.md 8.2); null if absent. */
    fun resolveNote(t: LearningTables, id: String): Row? = MergeReaders.resolveNote(t, id)

    /** The `Note` of [note] with its live cards (ordered by kind, then id) and `deck_ids` (C-25). */
    fun note(t: LearningTables, note: Row): JsonObject {
        val id = checkNotNull(note["id"].text()) { "note row without id" }
        val threshold = AnswerViews.leechThreshold(t)
        val cards = t.where(LearningTable.CARD, "note_id", JsonString(id)).filter(::live)
            .sortedWith(compareBy<Row>({ it["kind"].text().orEmpty() }, { it["id"].text().orEmpty() }))
            .map { c -> JsonObject(AnswerViews.card(c, note, threshold).members.filterKeys { it != "note" }) }
        return jsonObj(
            "id" to note.col("id"),
            "note_type_id" to note.col("note_type_id"),
            "type" to note.col("type"),
            "language" to (note["language"] as? JsonString ?: JsonString("ja")),
            "entry_seq" to note.col("entry_seq"),
            "kanji_literal" to note.col("kanji_literal"),
            "grammar_point_id" to note.col("grammar_point_id"),
            "fields" to (note["fields"] as? JsonObject ?: JsonObject(emptyMap())),
            "tags" to (note["tags"] as? JsonArray ?: JsonArray(emptyList())),
            "mnemonic" to (note["mnemonic"] as? JsonString ?: JsonString("")),
            "source_sentence" to (note["source_sentence"] as? JsonString ?: JsonString("")),
            "source_sentence_id" to note.col("source_sentence_id"),
            "source_media_id" to note.col("source_media_id"),
            "origin" to note.col("origin"),
            "created_by_device_id" to note.col("created_by_device_id"),
            "created_at" to note.stamp("created_at"),
            "updated_at" to note.stamp("updated_at"),
            "deleted_at" to note.stamp("deleted_at"),
            "merged_into_id" to note.col("merged_into_id"),
            "deck_ids" to JsonArray(deckIds(t, note).map(::JsonString)),
            "cards" to JsonArray(cards),
        )
    }

    /**
     * C-25 (server `DeckMembership.noteDeckIdsSql`): live non-smart decks holding a live item `e:<entry_seq>`, `n:<id>`
     * or `n:<loser>` of a note merged into this one; draw order (priority, id); empty for a tombstone or merge loser.
     */
    fun deckIds(t: LearningTables, note: Row): List<String> {
        if (!note["deleted_at"].isNullish() || !note["merged_into_id"].isNullish()) return emptyList()
        val id = note["id"].text() ?: return emptyList()
        val items = ArrayList<Row>()
        (note["entry_seq"] as? JsonNumber)?.let { items += t.where(LearningTable.DECK_ITEM, "entry_seq", it) }
        items += t.where(LearningTable.DECK_ITEM, "note_id", JsonString(id))
        for (loser in t.where(LearningTable.NOTE, "merged_into_id", JsonString(id))) {
            loser["id"].text()?.let { items += t.where(LearningTable.DECK_ITEM, "note_id", JsonString(it)) }
        }
        return items.filter(::live).mapNotNull { it["deck_id"].text() }.toSet()
            .mapNotNull { t.get(LearningTable.DECK, listOf(JsonString(it))) }
            .filter { live(it) && it["type"].text() != "smart" }
            .sortedWith(compareBy<Row>({ (it["priority"] as? JsonNumber)?.value ?: 0.0 }, { it["id"].text().orEmpty() }))
            .mapNotNull { it["id"].text() }
    }

    /** `WordStatus` of [seq]; an entry without a row is `new` with every other property null. */
    fun wordStatus(t: LearningTables, seq: Long): JsonObject {
        val r = t.get(LearningTable.WORD_STATUS, listOf(JsonNumber(seq.toDouble())))
        return jsonObj(
            "entry_seq" to JsonNumber(seq.toDouble()),
            "status" to (r?.get("status") as? JsonString ?: JsonString("new")),
            "auto_status" to (r?.col("auto_status") ?: JsonNull),
            "override_status" to (r?.col("override_status") ?: JsonNull),
            "override_origin" to (r?.col("override_origin") ?: JsonNull),
            "updated_at" to (r?.stamp("updated_at") ?: JsonNull),
        )
    }
}
