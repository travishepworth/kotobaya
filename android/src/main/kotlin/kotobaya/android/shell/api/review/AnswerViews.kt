package kotobaya.android.shell.api.review

import kotobaya.android.queue.QueueSettings
import kotobaya.android.review.CardRows
import kotobaya.android.shell.api.identity.obj
import kotobaya.android.shell.api.identity.timestamp
import kotobaya.contracts.sync.JsonArray
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

/**
 * The `ReviewResult` view of `answerReview` / `undoReview` (openapi.yaml `ReviewResult`): pure reads of the replica,
 * shaped like the server's `ReviewViews.review` / `ReviewViews.cards`. Nothing here computes a schedule: the card is
 * whatever the op-apply path (fold or replay) left in the row.
 */
internal object AnswerViews {
    private fun JsonValue?.orNull(): JsonValue = this ?: JsonNull

    private fun stamp(v: JsonValue?): JsonValue = (v as? JsonNumber)?.value?.toLong()?.let { JsonString(timestamp(it)) } ?: JsonNull

    /** `scheduler.leech_threshold` by the validity rule of SCHEMA.md 6.1 (the queue's [QueueSettings]). */
    fun leechThreshold(t: LearningTables): Int {
        val value = t.get(LearningTable.SETTINGS, listOf(JsonString("scheduler.leech_threshold")))?.get("value")
        return QueueSettings.intValue(value, Int.MIN_VALUE, Int.MAX_VALUE) ?: QueueSettings().leechThreshold
    }

    /** `ReviewLog` of a `review_log` row. */
    fun review(r: Row): JsonObject = obj(
        "id" to r["id"].orNull(),
        "card_id" to r["card_id"].orNull(),
        "device_id" to r["device_id"].orNull(),
        "hlc" to r["hlc"].orNull(),
        "reviewed_at" to stamp(r["reviewed_at"]),
        "utc_offset_min" to r["utc_offset_min"].orNull(),
        "review_type" to r["review_type"].orNull(),
        "rating" to r["rating"].orNull(),
        "button" to r["button"].orNull(),
        "duration_ms" to r["duration_ms"].orNull(),
        "manual_action" to r["manual_action"].orNull(),
        "manual_due_at" to stamp(r["manual_due_at"]),
        "sentence_id" to r["sentence_id"].orNull(),
        "import_source" to r["import_source"].orNull(),
        "undone_at" to stamp(r["undone_at"]),
    )

    /** `Card` with its `note` summary (deleted and merged rows included, as the server's view). */
    fun card(card: Row, note: Row, leechThreshold: Int): JsonObject {
        val lapses = (card["lapses"] as? JsonNumber)?.value?.toLong() ?: 0L
        return obj(
            "id" to card["id"].orNull(),
            "note_id" to card["note_id"].orNull(),
            "kind" to card["kind"].orNull(),
            "preset_id" to card["preset_id"].orNull(),
            "suspended" to (card["suspended"] as? JsonBool ?: JsonBool(false)),
            "buried_until" to stamp(card["buried_until"]),
            "flags" to (card["flags"] as? JsonNumber ?: JsonNumber(0.0)),
            "state" to card["state"].orNull(),
            "step" to card["step"].orNull(),
            "stability" to card["stability"].orNull(),
            "difficulty" to card["difficulty"].orNull(),
            "due_at" to stamp(card["due_at"]),
            "interval_days" to card["interval_days"].orNull(),
            "last_review_at" to stamp(card["last_review_at"]),
            "reps" to (card["reps"] as? JsonNumber ?: JsonNumber(0.0)),
            "lapses" to JsonNumber(lapses.toDouble()),
            "created_at" to stamp(card["created_at"]),
            "updated_at" to stamp(card["updated_at"]),
            "deleted_at" to stamp(card["deleted_at"]),
            "merged_into_id" to card["merged_into_id"].orNull(),
            "note" to obj(
                "id" to note["id"].orNull(),
                "note_type_id" to note["note_type_id"].orNull(),
                "type" to note["type"].orNull(),
                "entry_seq" to note["entry_seq"].orNull(),
                "kanji_literal" to note["kanji_literal"].orNull(),
                "grammar_point_id" to note["grammar_point_id"].orNull(),
                "fields" to (note["fields"] as? JsonObject ?: JsonObject(emptyMap())),
                "tags" to (note["tags"] as? JsonArray ?: JsonArray(emptyList())),
                "mnemonic" to (note["mnemonic"] as? JsonString ?: JsonString("")),
                "source_sentence" to (note["source_sentence"] as? JsonString ?: JsonString("")),
            ),
            "leech" to JsonBool(lapses >= leechThreshold),
        )
    }

    /**
     * `ReviewResult` for the stored [review]: the review and the card it was logged on as it is now (its survivor when
     * merged; `review.card_id` itself is never rewritten).
     */
    fun result(t: LearningTables, review: Row): JsonObject {
        val cardId = checkNotNull(CardRows.str(review["card_id"])) { "review_log row without card_id" }
        val card = checkNotNull(MergeReaders.resolveCard(t, cardId)) { "card $cardId of a stored review is missing" }
        val noteId = checkNotNull(CardRows.str(card["note_id"])) { "card without note_id" }
        val note = checkNotNull(CardRows.get(t, LearningTable.NOTE, noteId)) { "note $noteId of a card is missing" }
        return obj("review" to review(review), "card" to card(card, note, leechThreshold(t)))
    }
}
