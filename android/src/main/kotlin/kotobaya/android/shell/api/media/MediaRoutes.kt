package kotobaya.android.shell.api.media

import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.actions.enumValue
import kotobaya.android.shell.api.actions.exclusive
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.pageLimit
import kotobaya.android.shell.api.actions.pathId
import kotobaya.android.shell.api.actions.string
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.confusables.Keyset
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.timestamp
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest

private val KINDS = setOf("anime", "novel", "vn", "manga", "web", "other")
private val HASH_RE = Regex("^[0-9a-f]{64}$")
private val REGISTERS = setOf("title", "kind", "cover_blob_hash")

private fun Row.ms(column: String): Long = (this[column] as? JsonNumber)?.value?.toLong() ?: 0L

private fun Row.stamp(column: String): JsonValue = (this[column] as? JsonNumber)?.value?.toLong()?.let { JsonString(timestamp(it)) } ?: JsonNull

private fun Row.col(column: String): JsonValue = this[column] ?: JsonNull

private fun mediaRow(t: LearningTables, id: String): Row? = t.get(LearningTable.MEDIA, listOf(JsonString(id)))

private fun noMedia(): Nothing = throw ProblemException(Problems.notFound("No such media."))

private fun gone(r: Row): Nothing = throw ProblemException(Problems.gone("The media was deleted at ${(r.stamp("deleted_at") as? JsonString)?.value}."))

/**
 * `Media` (server `MediaApiModule.mediaRow`) of a replica row; with [segments] the installed snapshot's segments by
 * `ordinal` — `[]` while no snapshot is installed (SHELL.md 4.3, documented difference; `parse_status` and
 * `content_version` are the row's, so the SPA can tell "not downloaded").
 */
internal fun mediaView(t: LearningTables, r: Row, segments: Boolean): JsonObject {
    val m = linkedMapOf(
        "id" to r.col("id"),
        "title" to r.col("title"),
        "kind" to r.col("kind"),
        "language" to (r["language"] as? JsonString ?: JsonString("ja")),
        "source" to r.col("source"),
        "jpdb_id" to r.col("jpdb_id"),
        "cover_blob_hash" to r.col("cover_blob_hash"),
        "parse_status" to r.col("parse_status"),
        "parse_error" to r.col("parse_error"),
        "content_version" to (r["content_version"] as? JsonNumber ?: JsonNumber(0.0)),
        "content_blob_hash" to r.col("content_blob_hash"),
        "created_at" to r.stamp("created_at"),
        "updated_at" to r.stamp("updated_at"),
        "deleted_at" to r.stamp("deleted_at"),
    )
    if (segments) {
        val segs = t.where(LearningTable.MEDIA_SEGMENT, "media_id", r.col("id"))
            .sortedBy { it.ms("ordinal") }
            .map { s ->
                jsonObj(
                    "id" to s.col("id"),
                    "media_id" to s.col("media_id"),
                    "ordinal" to s.col("ordinal"),
                    "kind" to s.col("kind"),
                    "title" to (s["title"] as? JsonString ?: JsonString("")),
                    "sentence_count" to (s["sentence_count"] as? JsonNumber ?: JsonNumber(0.0)),
                    "token_count" to (s["token_count"] as? JsonNumber ?: JsonNumber(0.0)),
                    "unmapped_token_count" to (s["unmapped_token_count"] as? JsonNumber ?: JsonNumber(0.0)),
                )
            }
        m["segments"] = JsonArray(segs)
    }
    return JsonObject(m)
}

private fun writes(call: LocalCall): LocalWrites<SqlLearningTables> = call.services.get()

private fun store(call: LocalCall): SqlDelightDeviceStore = call.services.get()

private val MEDIA_ORDER = Keyset.columns(Keyset.LONG_DESC, Keyset.TEXT_DESC)

private fun mediaKey(r: Row): List<String> = listOf(r.ms("created_at").toString(), r["id"].text().orEmpty())

/**
 * `listMedia` (server `MediaApiModule.list`): filters `kind`, `q` (case-insensitive substring of the title),
 * `include_deleted`; ordered by `created_at` descending then `id` descending; keyset-paged. No `segments`.
 */
internal fun listMedia(call: LocalCall): ShellResponse {
    val q = call.query("kind", "q", "include_deleted", "limit", "cursor")
    val e = FieldErrors()
    val deleted = q.bool("include_deleted", false, e)
    val kind = q.first("kind")?.takeIf { it in KINDS || run { e.add("/kind", "invalid_value", "Must be one of ${KINDS.joinToString(", ")}."); false } }
    val text = q.first("q")?.takeIf { v -> (v.isNotEmpty() && v.length <= 200).also { if (!it) e.add("/q", "invalid_value", "1 to 200 characters.") } }?.lowercase()
    val limit = pageLimit(q.first("limit"), e)
    val hash = listOf(deleted, kind, text).joinToString("|").hashCode().toString(16)
    val after = Keyset.decode(q.first("cursor"), 2, hash, e) { Keyset.isLong(it[0]) }
    e.throwIfAny()
    val body = store(call).read { tx ->
        val t = tx.learning
        val rows = t.all(LearningTable.MEDIA).asSequence().filter { r ->
            (deleted || r["deleted_at"].isNullish()) &&
                (kind == null || r["kind"].text() == kind) &&
                (text == null || r["title"].text().orEmpty().lowercase().contains(text))
        }
        val (shown, last) = Keyset.page(rows, ::mediaKey, MEDIA_ORDER, after, limit)
        jsonObj(
            "items" to JsonArray(shown.map { mediaView(t, it, segments = false) }),
            "next_cursor" to (last?.let { JsonString(Keyset.encode(it, hash)) } ?: JsonNull),
        )
    }
    return ShellResponse.json(body)
}

/** `getMedia`: unknown `404`; deleted `410` unless `include_deleted=true`; with the installed `segments`. */
internal fun getMedia(call: LocalCall): ShellResponse {
    val q = call.query("include_deleted")
    val id = call.pathId("media_id", "media")
    val e = FieldErrors()
    val deleted = q.bool("include_deleted", false, e)
    e.throwIfAny()
    val view = store(call).read { tx ->
        val r = mediaRow(tx.learning, id) ?: noMedia()
        if (!deleted && !r["deleted_at"].isNullish()) gone(r)
        mediaView(tx.learning, r, segments = true)
    }
    return ShellResponse.json(view)
}

/** A refused `media.updated` pointer (`/payload/set/<register>`) as the request property. */
private fun mediaPointer(r: MintRefusal.Invalid): String {
    val p = r.pointer.removePrefix("/payload").removePrefix("/set")
    return if (p.removePrefix("/").substringBefore('/') in REGISTERS) p else ""
}

/**
 * `updateMedia` (server `MediaApiModule.update`): body checks (empty `422`, `title` 1..300, `kind`, `cover_blob_hash` a
 * lowercase hex SHA-256 or null), then `404` / `410`; one `media.updated` with the registers present. Whether the cover
 * blob exists (and is an image) is server data the device does not hold: SHELL.md 10.21 (the op is pushed and the
 * server decides).
 */
internal fun updateMedia(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("media_id", "media")
    val body = call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, REGISTERS)
    if (body.members.isEmpty()) e.add("", "required", "At least one property is required.")
    val set = LinkedHashMap<String, JsonValue>()
    e.string(body, "title", 1, 300)?.let { set["title"] = JsonString(it) }
    e.enumValue(body, "kind", KINDS)?.let { set["kind"] = JsonString(it) }
    body.members["cover_blob_hash"]?.let { v ->
        when {
            v == JsonNull -> set["cover_blob_hash"] = JsonNull
            v is JsonString && HASH_RE.matches(v.value) -> set["cover_blob_hash"] = v
            else -> e.add("/cover_blob_hash", "invalid_value", "A lowercase hex SHA-256 or null.")
        }
    }
    e.throwIfAny()
    val media = writes(call).exclusive<JsonObject> { tx ->
        val r = mediaRow(tx.learning, id) ?: noMedia()
        if (!r["deleted_at"].isNullish()) gone(r)
        val payload = jsonObj("id" to JsonString(id), "set" to JsonObject(set))
        WritePlan.Mint(listOf(MintRequest(OpType.MEDIA_UPDATED, payload)), ::mediaPointer) { after, _ ->
            mediaView(after.learning, checkNotNull(mediaRow(after.learning, id)) { "media $id vanished" }, segments = true)
        }
    }
    return ShellResponse.json(media)
}

/**
 * `deleteMedia` (SHELL.md 4.3): unknown `404`; already deleted `204`, nothing minted; else `media.deleted` and, in the
 * same transaction, `deck.deleted` for each live media deck of it in the replica. The apply of `media.deleted` removes
 * the installed segments, sentences, occurrences and detected spans, as the pull path does; manual spans, notes and
 * reviews stay.
 */
internal fun deleteMedia(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("media_id", "media")
    writes(call).exclusive<Unit> { tx ->
        val t = tx.learning
        val r = mediaRow(t, id) ?: noMedia()
        if (!r["deleted_at"].isNullish()) return@exclusive WritePlan.Answer(Unit)
        val decks = t.where(LearningTable.DECK, "media_id", JsonString(id))
            .filter { it["type"].text() == "media" && it["deleted_at"].isNullish() }
            .mapNotNull { it["id"].text() }.sorted()
        val requests = listOf(MintRequest(OpType.MEDIA_DELETED, jsonObj("id" to JsonString(id)))) +
            decks.map { MintRequest(OpType.DECK_DELETED, jsonObj("id" to JsonString(it))) }
        WritePlan.Mint(requests) { _, _ -> }
    }
    return ShellResponse.noContent()
}
