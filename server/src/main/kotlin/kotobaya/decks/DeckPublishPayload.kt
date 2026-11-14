package kotobaya.decks

import com.fasterxml.jackson.databind.JsonNode
import kotobaya.media.publish.BlobRef
import kotobaya.media.publish.PublishFailure
import java.util.UUID

/** The `deck-publish` job payload v1 (contracts/db/media/deck-publish.v1.schema.json, closed; VOCAB_LISTS.md 5.1). */
data class DeckPublishPayload(
    val parentJobId: UUID,
    val deckId: String,
    val listKey: String,
    val baseContentVersion: Int,
    val deckItems: BlobRef,
    val unmatched: Int,
) {
    val version: Int get() = baseContentVersion + 1

    companion object {
        private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val SHA_RE = Regex("^[0-9a-f]{64}$")
        private val KEY_RE = Regex("^[a-z0-9][a-z0-9-]*$")
        private val TOP = setOf("format_version", "parent_job_id", "deck_id", "list_key", "base_content_version", "deck_items", "unmatched")

        /** Validates [p] against the closed schema; throws [PublishFailure] (`unsupported_format` / `invalid_payload`). */
        fun parse(p: JsonNode): DeckPublishPayload {
            if (!p.isObject) bad("", "the payload is not an object")
            val fv = p.get("format_version")
            if (fv != null && !(fv.isIntegralNumber && fv.canConvertToLong() && fv.asLong() == 1L)) {
                throw PublishFailure(PublishFailure.UNSUPPORTED_FORMAT, "format_version $fv is not 1")
            }
            closed(p, "", TOP)
            val key = text(p, "list_key", "")
            if (!KEY_RE.matches(key) || key.length > 100) bad("/list_key", "not a list key slug of at most 100 characters")
            val items = req(p, "deck_items", "")
            closed(items, "/deck_items", setOf("blob_hash", "size_bytes"))
            val hash = text(items, "blob_hash", "/deck_items")
            if (!SHA_RE.matches(hash)) bad("/deck_items/blob_hash", "not a lowercase hex SHA-256")
            return DeckPublishPayload(
                parentJobId = UUID.fromString(uuid(p, "parent_job_id")),
                deckId = uuid(p, "deck_id"),
                listKey = key,
                baseContentVersion = int(p, "base_content_version", "", 0, 2_147_483_646).toInt(),
                deckItems = BlobRef(hash, int(items, "size_bytes", "/deck_items", 1, 536_870_912)),
                unmatched = int(p, "unmatched", "", 0, 2_147_483_647).toInt(),
            )
        }

        private fun closed(n: JsonNode, at: String, members: Set<String>) {
            if (!n.isObject) bad(at, "must be an object")
            n.fieldNames().forEach { if (it !in members) bad("$at/$it", "unknown member") }
            members.forEach { if (!n.has(it)) bad("$at/$it", "required") }
        }

        private fun req(n: JsonNode, name: String, at: String): JsonNode = n.get(name) ?: bad("$at/$name", "required")

        private fun int(n: JsonNode, name: String, at: String, min: Long, max: Long): Long {
            val v = req(n, name, at)
            if (!v.isIntegralNumber || !v.canConvertToLong()) bad("$at/$name", "must be an integer")
            val x = v.asLong()
            if (x < min || x > max) bad("$at/$name", "$x outside $min..$max")
            return x
        }

        private fun text(n: JsonNode, name: String, at: String): String {
            val v = req(n, name, at)
            if (!v.isTextual) bad("$at/$name", "must be a string")
            return v.asText()
        }

        private fun uuid(n: JsonNode, name: String): String =
            text(n, name, "").also { if (!UUID_RE.matches(it)) bad("/$name", "not a canonical lowercase UUID") }

        private fun bad(at: String, message: String): Nothing =
            throw PublishFailure(PublishFailure.INVALID_PAYLOAD, "${at.ifEmpty { "/" }}: $message")
    }
}
