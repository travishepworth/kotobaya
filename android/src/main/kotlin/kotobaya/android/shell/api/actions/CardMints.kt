package kotobaya.android.shell.api.actions

import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.store.UuidV7Source

/**
 * The card-level mint requests (OPS.md `card.*`, `note.updated` tags, `review.logged` manual), one place so every
 * local writer of these ops (`bulkCards`, `bulkScheduleCards`, the single-card writes) mints the same payloads as
 * the server (`BulkApi`, `ReviewOps`). The deck item requests live in `DeckMints`.
 */
internal object CardMints {
    private val ids = UuidV7Source(SecureRandom().asKotlinRandom())

    /** A new UUIDv7 id from the device clock [nowMs] (L-2), for rows the request does not name (manual reviews). */
    fun newId(nowMs: Long): String = ids.newOpId(nowMs)

    private fun byId(type: OpType, id: String) = MintRequest(type, jsonObj("id" to JsonString(id)))

    fun suspended(id: String) = byId(OpType.CARD_SUSPENDED, id)

    fun unsuspended(id: String) = byId(OpType.CARD_UNSUSPENDED, id)

    fun unburied(id: String) = byId(OpType.CARD_UNBURIED, id)

    fun deleted(id: String) = byId(OpType.CARD_DELETED, id)

    fun buried(id: String, untilMs: Long) =
        MintRequest(OpType.CARD_BURIED, jsonObj("id" to JsonString(id), "until" to JsonNumber(untilMs.toDouble())))

    fun flagSet(id: String, flags: Long) =
        MintRequest(OpType.CARD_FLAG_SET, jsonObj("id" to JsonString(id), "flags" to JsonNumber(flags.toDouble())))

    fun presetSet(id: String, presetId: String) =
        MintRequest(OpType.CARD_PRESET_SET, jsonObj("id" to JsonString(id), "preset_id" to JsonString(presetId)))

    /** `note.updated` setting the whole tag register to [tags] (already sorted by code point, de-duplicated). */
    fun noteTags(noteId: String, tags: List<String>) = MintRequest(
        OpType.NOTE_UPDATED,
        jsonObj("id" to JsonString(noteId), "set" to jsonObj("tags" to JsonArray(tags.map { JsonString(it) }))),
    )

    /** A manual `review.logged` (`set_due` with [dueAtMs], or `reset` with none), as the server's `ReviewOps.logReview`. */
    fun manualReview(id: String, cardId: String, reviewedAtMs: Long, utcOffsetMin: Int, action: String, dueAtMs: Long?): MintRequest {
        val p = LinkedHashMap<String, JsonValue>()
        p["id"] = JsonString(id)
        p["card_id"] = JsonString(cardId)
        p["reviewed_at"] = JsonNumber(reviewedAtMs.toDouble())
        p["utc_offset_min"] = JsonNumber(utcOffsetMin.toDouble())
        p["review_type"] = JsonString("manual")
        p["rating"] = JsonNull
        p["manual_action"] = JsonString(action)
        dueAtMs?.let { p["manual_due_at"] = JsonNumber(it.toDouble()) }
        return MintRequest(OpType.REVIEW_LOGGED, JsonObject(p))
    }
}
