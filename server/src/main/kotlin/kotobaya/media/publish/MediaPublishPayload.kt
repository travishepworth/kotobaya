package kotobaya.media.publish

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import java.util.UUID

/**
 * A publish failure with one of the MEDIA_PIPELINE.md 3.3 error codes; the child ends `failed` with
 * `error = "<code>: <message>"`.
 */
class PublishFailure(val code: String, message: String) : RuntimeException(message) {
    val error: String get() = "$code: $message"

    companion object {
        const val INVALID_PAYLOAD = "invalid_payload"
        const val UNSUPPORTED_FORMAT = "unsupported_format"
        const val BLOB_MISSING = "blob_missing"
        const val INVALID_SNAPSHOT = "invalid_snapshot"
        const val STALE_BASE = "stale_base"
        const val UNKNOWN_MEDIA = "unknown_media"
        const val MEDIA_DELETED = "media_deleted"
        const val DECK_ID_TAKEN = "deck_id_taken"
        const val JPDB_ID_TAKEN = "jpdb_id_taken"
        const val INVARIANT_VIOLATION = "invariant_violation"
        const val INTERNAL = "internal"
    }
}

/** `{blob_hash, size_bytes}` of one archive. */
data class BlobRef(val blobHash: String, val sizeBytes: Long)

data class DeckOptions(val priority: Int, val enabled: Boolean, val newCardsPerDay: Int?)

data class CreateMedia(val title: String, val kind: String, val source: String, val jpdbId: String?, val deck: DeckOptions)

data class JpdbVocab(
    val jpdbVid: Long,
    val spelling: String,
    val reading: String,
    val occurrenceCount: Int,
    val resolution: String,
    val entrySeq: Long?,
)

data class JpdbPart(val jpdbId: String, val vocab: List<JpdbVocab>)

/** The `media-publish` job payload v1 (contracts/db/media/media-publish.v1.schema.json, closed). */
data class MediaPublishPayload(
    val parentJobId: UUID,
    val mediaId: String,
    val baseContentVersion: Int,
    val create: CreateMedia?,
    val content: BlobRef,
    val deckItems: BlobRef,
    /** Copied unchanged into `JobResultMedia.unmapped`. */
    val unmapped: ArrayNode,
    val jpdb: JpdbPart?,
) {
    val version: Int get() = baseContentVersion + 1

    companion object {
        private const val MAX_SAFE = 9_007_199_254_740_991L
        private const val INT4 = 2_147_483_647L
        private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val SHA_RE = Regex("^[0-9a-f]{64}$")
        private val TOP = setOf("format_version", "parent_job_id", "media_id", "base_content_version", "create", "content", "deck_items", "unmapped", "jpdb")
        private val MEDIA_KINDS = setOf("anime", "novel", "vn", "manga", "web", "other")
        private val SOURCES = setOf("jpdb", "file", "url", "paste")
        private val RESOLUTIONS = setOf("unresolved", "mapped", "ignored")

        /** The `parent_job_id` of a payload that may not validate (for cancellation checks), or null. */
        fun parentOf(p: JsonNode): UUID? =
            p.path("parent_job_id").takeIf { it.isTextual && UUID_RE.matches(it.asText()) }?.let { UUID.fromString(it.asText()) }

        /** Validates [p] against the closed schema; throws [PublishFailure] (`unsupported_format` / `invalid_payload`). */
        fun parse(p: JsonNode): MediaPublishPayload {
            if (!p.isObject) bad("", "the payload is not an object")
            val fv = p.get("format_version")
            if (fv != null && !(fv.isIntegralNumber && fv.canConvertToLong() && fv.asLong() == 1L)) {
                throw PublishFailure(PublishFailure.UNSUPPORTED_FORMAT, "format_version $fv is not 1")
            }
            closed(p, "", TOP)
            val base = int(p, "base_content_version", "", 0, 2_147_483_646).toInt()
            val createNode = req(p, "create", "")
            val create = if (createNode.isNull) null else create(createNode)
            if ((base == 0) != (create != null)) bad("/create", "create must be set iff base_content_version = 0")
            val unmapped = req(p, "unmapped", "") as? ArrayNode ?: bad("/unmapped", "must be an array")
            unmapped.forEachIndexed { i, u ->
                val at = "/unmapped/$i"
                closed(u, at, setOf("jpdb_vid", "spelling", "reading", "occurrence_count"))
                int(u, "jpdb_vid", at, 1, MAX_SAFE)
                text(u, "spelling", at)
                text(u, "reading", at)
                int(u, "occurrence_count", at, 0, INT4)
            }
            val jpdbNode = req(p, "jpdb", "")
            val jpdb = if (jpdbNode.isNull) null else jpdb(jpdbNode)
            return MediaPublishPayload(
                parentJobId = UUID.fromString(uuid(p, "parent_job_id", "")),
                mediaId = uuid(p, "media_id", ""),
                baseContentVersion = base,
                create = create,
                content = blob(req(p, "content", ""), "/content"),
                deckItems = blob(req(p, "deck_items", ""), "/deck_items"),
                unmapped = unmapped,
                jpdb = jpdb,
            )
        }

        private fun create(n: JsonNode): CreateMedia {
            val at = "/create"
            closed(n, at, setOf("title", "kind", "source", "jpdb_id", "deck"))
            val jpdbNode = req(n, "jpdb_id", at)
            val deck = req(n, "deck", at)
            closed(deck, "$at/deck", setOf("priority", "enabled", "new_cards_per_day"))
            val enabled = req(deck, "enabled", "$at/deck").takeIf { it.isBoolean } ?: bad("$at/deck/enabled", "must be a boolean")
            val npd = req(deck, "new_cards_per_day", "$at/deck")
            return CreateMedia(
                title = text(n, "title", at, 1, 300),
                kind = enumOf(n, "kind", at, MEDIA_KINDS),
                source = enumOf(n, "source", at, SOURCES),
                jpdbId = if (jpdbNode.isNull) null else text(n, "jpdb_id", at, 1, 200),
                deck = DeckOptions(
                    priority = int(deck, "priority", "$at/deck", -1_000_000, 1_000_000).toInt(),
                    enabled = enabled.asBoolean(),
                    newCardsPerDay = if (npd.isNull) null else int(deck, "new_cards_per_day", "$at/deck", 0, 9999).toInt(),
                ),
            )
        }

        private fun jpdb(n: JsonNode): JpdbPart {
            closed(n, "/jpdb", setOf("jpdb_id", "vocab"))
            val vocab = req(n, "vocab", "/jpdb") as? ArrayNode ?: bad("/jpdb/vocab", "must be an array")
            return JpdbPart(
                jpdbId = text(n, "jpdb_id", "/jpdb", 1, 200),
                vocab = vocab.mapIndexed { i, v ->
                    val at = "/jpdb/vocab/$i"
                    closed(v, at, setOf("jpdb_vid", "spelling", "reading", "occurrence_count", "resolution", "entry_seq"))
                    val seq = req(v, "entry_seq", at)
                    JpdbVocab(
                        jpdbVid = int(v, "jpdb_vid", at, 1, MAX_SAFE),
                        spelling = text(v, "spelling", at),
                        reading = text(v, "reading", at),
                        occurrenceCount = int(v, "occurrence_count", at, 0, INT4).toInt(),
                        resolution = enumOf(v, "resolution", at, RESOLUTIONS),
                        entrySeq = if (seq.isNull) null else int(v, "entry_seq", at, 1, MAX_SAFE),
                    )
                },
            )
        }

        private fun blob(n: JsonNode, at: String): BlobRef {
            closed(n, at, setOf("blob_hash", "size_bytes"))
            val hash = text(n, "blob_hash", at)
            if (!SHA_RE.matches(hash)) bad("$at/blob_hash", "not a lowercase hex SHA-256")
            return BlobRef(hash, int(n, "size_bytes", at, 1, 536_870_912))
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

        private fun text(n: JsonNode, name: String, at: String, min: Int = 0, max: Int = Int.MAX_VALUE): String {
            val v = req(n, name, at)
            if (!v.isTextual) bad("$at/$name", "must be a string")
            val s = v.asText()
            if (s.indexOf('\u0000') >= 0) bad("$at/$name", "contains U+0000")
            val cp = s.codePointCount(0, s.length)
            if (cp < min || cp > max) bad("$at/$name", "length $cp outside $min..$max")
            return s
        }

        private fun uuid(n: JsonNode, name: String, at: String): String =
            text(n, name, at).also { if (!UUID_RE.matches(it)) bad("$at/$name", "not a canonical lowercase UUID") }

        private fun enumOf(n: JsonNode, name: String, at: String, allowed: Set<String>): String =
            text(n, name, at).also { if (it !in allowed) bad("$at/$name", "'$it' not one of $allowed") }

        private fun bad(at: String, message: String): Nothing =
            throw PublishFailure(PublishFailure.INVALID_PAYLOAD, "${at.ifEmpty { "/" }}: $message")
    }
}
