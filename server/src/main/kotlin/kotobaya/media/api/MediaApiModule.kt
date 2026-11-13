package kotobaya.media.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.sync.OpType
import kotobaya.jobs.JobRepo
import kotobaya.jobs.JobRow
import kotobaya.notes.Errors
import kotobaya.notes.Reply
import kotobaya.notes.UUID_RE
import kotobaya.notes.callerTokenId
import kotobaya.notes.gone
import kotobaya.notes.includeDeleted
import kotobaya.notes.jsonBody
import kotobaya.notes.pathId
import kotobaya.notes.payload
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.Uuid7
import kotobaya.server.formatInstant
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import kotobaya.sync.OpTx
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

private val HASH_RE = Regex("^[0-9a-f]{64}$")
private val JPDB_ID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9/_-]*$")
private val URL_RE = Regex("^https?://[^\\s/]+\\S*$", RegexOption.IGNORE_CASE)
private val KINDS = setOf("anime", "novel", "vn", "manga", "web", "other")
private val FORMATS = setOf("auto", "srt", "ass", "vtt", "epub", "txt", "mokuro", "html", "texthooker")
private const val JPDB_MAPPING_LOCK = 7033011L

/**
 * Media API (T411b): library, imports, reparse, delete and the jpdb mapping-correction handlers (C-11).
 *
 * Imports only enqueue jobs (payload = request + server-set `media_id`); rows are installed by the publish runner.
 * Writes to learning data are ops through [opApply]; `media.deleted` already hard-deletes the content and emits
 * `deck.deleted` in `OpApplier`, so nothing is duplicated here. Wiring (W-5): `handlers += MediaApiModule(...).handlers`.
 */
class MediaApiModule(private val db: Database, private val opApply: OpApply) {
    val handlers: Map<String, OperationHandler> = mapOf(
        "listMedia" to { list() },
        "getMedia" to { get() },
        "updateMedia" to { update() },
        "deleteMedia" to { delete() },
        "importMedia" to { importMedia() },
        "importJpdbDeck" to { importJpdbDeck() },
        "reparseMedia" to { reparse() },
        "listJpdbVocab" to { listVocab() },
        "putJpdbVocabResolution" to { putResolution() },
        "clearJpdbVocabResolution" to { clearResolution() },
    )

    // ------------------------------------------------------------------ views

    private fun ts(r: Record, name: String): String? = r.get(name, OffsetDateTime::class.java)?.let { formatInstant(it.toInstant()) }

    private fun mediaRow(r: Record): ObjectNode = json.createObjectNode().apply {
        put("id", r.get("id", String::class.java))
        put("title", r.get("title", String::class.java))
        put("kind", r.get("kind", String::class.java))
        put("language", r.get("language", String::class.java))
        put("source", r.get("source", String::class.java))
        put("jpdb_id", r.get("jpdb_id", String::class.java))
        put("cover_blob_hash", r.get("cover_blob_hash", String::class.java))
        put("parse_status", r.get("parse_status", String::class.java))
        put("parse_error", r.get("parse_error", String::class.java))
        put("content_version", r.get("content_version", Int::class.javaObjectType))
        put("content_blob_hash", r.get("content_blob_hash", String::class.java))
        put("created_at", ts(r, "created_at"))
        put("updated_at", ts(r, "updated_at"))
        put("deleted_at", ts(r, "deleted_at"))
    }

    private val mediaCols = "id::text as id, title, kind, language, source, jpdb_id, cover_blob_hash, parse_status, parse_error, " +
        "content_version, content_blob_hash, created_at, updated_at, deleted_at"

    private fun media(dsl: DSLContext, id: String, withSegments: Boolean): ObjectNode? {
        val r = dsl.fetchOptional("select $mediaCols from app.media where id = ?::uuid", id).orElse(null) ?: return null
        val row = mediaRow(r)
        if (withSegments) {
            val segs = row.putArray("segments")
            dsl.fetch(
                "select id::text, media_id::text, ordinal, kind, title, sentence_count, token_count, unmapped_token_count " +
                    "from app.media_segment where media_id = ?::uuid order by ordinal", id,
            ).forEach {
                segs.addObject().apply {
                    put("id", it.get(0, String::class.java))
                    put("media_id", it.get(1, String::class.java))
                    put("ordinal", it.get(2, Int::class.javaObjectType))
                    put("kind", it.get(3, String::class.java))
                    put("title", it.get(4, String::class.java))
                    put("sentence_count", it.get(5, Int::class.javaObjectType))
                    put("token_count", it.get(6, Int::class.javaObjectType))
                    put("unmapped_token_count", it.get(7, Int::class.javaObjectType))
                }
            }
        }
        return row
    }

    private fun limitOf(call: ApplicationCall, e: Errors): Int =
        call.request.queryParameters["limit"]?.let { v ->
            v.toIntOrNull()?.takeIf { it in 1..500 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 500."); null }
        } ?: 50

    private fun enc(vararg parts: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(parts.joinToString("|").toByteArray())

    private fun dec(c: String, n: Int): List<String>? =
        runCatching { String(Base64.getUrlDecoder().decode(c)).split('|') }.getOrNull()?.takeIf { it.size == n }

    // ------------------------------------------------------------------ library

    private suspend fun ApplicationCall.list() {
        val deleted = includeDeleted()
        val q = request.queryParameters
        val e = Errors()
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        if (!deleted) where += "deleted_at is null"
        q["kind"]?.let {
            if (it in KINDS) { where += "kind = ?"; args += it } else e.add("/kind", "invalid_value", "Must be one of ${KINDS.joinToString(", ")}.")
        }
        q["q"]?.let {
            if (it.isEmpty() || it.length > 200) {
                e.add("/q", "invalid_value", "1 to 200 characters.")
            } else {
                where += "title ilike ? escape '\\'"
                args += "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            }
        }
        val limit = limitOf(this, e)
        q["cursor"]?.let { c ->
            val p = dec(c, 2)
            if (p == null || !UUID_RE.matches(p[1]) || runCatching { OffsetDateTime.parse(p[0]) }.isFailure) {
                e.add("/cursor", "invalid_value", "Malformed cursor.")
            } else {
                where += "(created_at, id) < (?::timestamptz, ?::uuid)"
                args += p[0]
                args += p[1]
            }
        }
        e.throwIfAny()
        val page = db.tx { dsl ->
            val rows = dsl.fetch(
                "select $mediaCols from app.media" + (if (where.isEmpty()) "" else " where " + where.joinToString(" and ")) +
                    " order by created_at desc, id desc limit ${limit + 1}",
                *args.toTypedArray(),
            )
            val shown = rows.take(limit)
            val next = if (rows.size > limit) {
                shown.last().let { enc(it.get("created_at", OffsetDateTime::class.java).toString(), it.get("id", String::class.java)) }
            } else {
                null
            }
            mapOf("items" to shown.map(::mediaRow), "next_cursor" to next)
        }
        respondJson(page)
    }

    private suspend fun ApplicationCall.get() {
        val id = pathId("media_id")
        val deleted = includeDeleted()
        val row = db.tx { media(it, id, true) } ?: throw Problems.notFound("No such media.")
        if (!deleted && !row["deleted_at"].isNull) throw gone("media", row["deleted_at"].asText())
        respondJson(row)
    }

    private suspend fun ApplicationCall.update() {
        val id = pathId("media_id")
        val body = jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("title", "kind", "cover_blob_hash"))
        if (body.isEmpty) e.add("", "required", "At least one property is required.")
        val set = json.createObjectNode()
        e.string(body, "title", 1, 300)?.let { set.put("title", it) }
        e.enum(body, "kind", KINDS)?.let { set.put("kind", it) }
        var cover: String? = null
        if (body.has("cover_blob_hash")) {
            val v = body.get("cover_blob_hash")
            if (v.isNull) {
                set.putNull("cover_blob_hash")
            } else if (v.isTextual && HASH_RE.matches(v.asText())) {
                cover = v.asText()
                set.put("cover_blob_hash", cover)
            } else {
                e.add("/cover_blob_hash", "invalid_value", "A lowercase hex SHA-256 or null.")
            }
        }
        e.throwIfAny()
        val reply = opApply.rest(callerTokenId()) { tx ->
            val row = media(tx.dsl, id, false) ?: throw Problems.notFound("No such media.")
            if (!row["deleted_at"].isNull) throw gone("media", row["deleted_at"].asText())
            if (cover != null) {
                val kind = tx.dsl.fetchOptional("select kind from app.blob where hash = ?", cover).map { it.get(0, String::class.java) }.orElse(null)
                if (kind != "image") {
                    Errors().apply {
                        add("/cover_blob_hash", if (kind == null) "not_found" else "invalid_value", "Must be an uploaded blob of kind image.")
                    }.throwIfAny()
                }
            }
            tx.emit(OpType.MEDIA_UPDATED, payload(json.createObjectNode().put("id", id).also { it.set<JsonNode>("set", set) }))
            Reply(HttpStatusCode.OK, media(tx.dsl, id, true), tx.lastSeq)
        }
        send(reply)
    }

    private suspend fun ApplicationCall.delete() {
        val id = pathId("media_id")
        val seq = opApply.rest(callerTokenId()) { tx ->
            val row = media(tx.dsl, id, false) ?: throw Problems.notFound("No such media.")
            if (!row["deleted_at"].isNull) return@rest null
            tx.emit(OpType.MEDIA_DELETED, payload(json.createObjectNode().put("id", id)))
            val cancelled = tx.dsl.fetch(
                "update app.job set status = 'cancelled', finished_at = now(), locked_by = null where status in ('queued','running') " +
                    "and kind in ('media-import','media-parse','jpdb-deck-import','media-publish') and payload->>'media_id' = ? returning id::text",
                id,
            )
            cancelled.forEach {
                tx.dsl.execute("select pg_notify('kotobaya_events', ?)", """{"type":"job.updated","data":{"id":"${it.get(0, String::class.java)}"}}""")
            }
            tx.lastSeq
        }
        send(Reply(HttpStatusCode.NoContent, null, seq))
    }

    // ------------------------------------------------------------------ jobs

    /** `parsing` (MEDIA_PIPELINE.md 4), read under the op-apply lock the caller's [OpTx] holds; no-op if already parsing. */
    private fun emitParsing(tx: OpTx, mediaId: String) {
        val r = tx.dsl.fetchOptional(
            "select parse_status, content_version, content_blob_hash from app.media where id = ?::uuid and deleted_at is null", mediaId,
        ).orElse(null) ?: return
        if (r.get(0, String::class.java) == "parsing") return
        val p = json.createObjectNode().put("media_id", mediaId).put("parse_status", "parsing")
        p.putNull("parse_error")
        p.put("content_version", r.get(1, Int::class.javaObjectType))
        p.put("content_blob_hash", r.get(2, String::class.java))
        tx.emit(OpType.MEDIA_CONTENT_PUBLISHED, payload(p))
    }

    private fun alreadyExists(job: JobRow, what: String) = ApiException(
        HttpStatusCode.Conflict, "already_exists", "Already exists", what, mapOf("existing_id" to job.id.toString()),
    )

    private fun jobReply(status: HttpStatusCode, job: JobRow, seq: Long? = null) = Reply(status, json.valueToTree(job.toApi()), seq)

    private fun deckOptions(e: Errors, v: JsonNode?) {
        if (v == null) return
        if (!v.isObject) {
            e.add("/deck", "invalid_type", "Must be an object.")
            return
        }
        e.unknownKeys(v as ObjectNode, setOf("priority", "enabled", "new_cards_per_day"))
        v.get("priority")?.let {
            if (!it.isIntegralNumber || it.asLong() !in -1_000_000..1_000_000) e.add("/deck/priority", "invalid_value", "An integer in -1000000..1000000.")
        }
        v.get("enabled")?.let { if (!it.isBoolean) e.add("/deck/enabled", "invalid_type", "Must be a boolean.") }
        v.get("new_cards_per_day")?.let {
            if (!it.isNull && (!it.isIntegralNumber || it.asLong() !in 0..9999)) e.add("/deck/new_cards_per_day", "invalid_value", "An integer in 0..9999 or null.")
        }
    }

    private fun validateOptions(e: Errors, o: JsonNode?) {
        if (o == null) return
        if (!o.isObject) {
            e.add("/options", "invalid_type", "Must be an object.")
            return
        }
        e.unknownKeys(o as ObjectNode, setOf("ruby", "cut_audio"))
        if (o.has("ruby")) e.enum(o, "ruby", setOf("strip", "hint"))
        o.get("cut_audio")?.let { if (!it.isBoolean) e.add("/options/cut_audio", "invalid_type", "Must be a boolean.") }
    }

    private suspend fun ApplicationCall.importMedia() {
        val body = jsonBody()
        val importId = body.get("import_id")?.let { v ->
            if (v.isTextual && UUID_RE.matches(v.asText())) {
                UUID.fromString(v.asText())
            } else {
                Errors().apply { add("/import_id", "invalid_value", "Must be a lowercase UUID.") }.throwIfAny()
                null
            }
        }
        val token = callerTokenId()
        val reply = opApply.rest(token) { tx ->
            val dsl = tx.dsl
            // Idempotent retry: answered before the rest of the body is looked at (OpenAPI).
            if (importId != null) {
                JobRepo.get(dsl, importId)?.let { job ->
                    if (job.kind != "media-import") throw alreadyExists(job, "import_id is used by a ${job.kind} job.")
                    return@rest jobReply(HttpStatusCode.OK, job)
                }
                if (dsl.fetchExists(dsl.selectOne().from("app.media").where("id = ?", importId))) {
                    val job = JobRepo.resolveImportRetry(dsl, importId) ?: throw Problems.notFound("No import job for this import_id.")
                    return@rest jobReply(HttpStatusCode.OK, job)
                }
            }
            val e = Errors()
            e.unknownKeys(body, setOf("import_id", "media_id", "title", "kind", "source", "files", "url", "text", "format", "options", "deck"))
            val source = e.enum(body, "source", setOf("file", "url", "paste"), required = true)
            val existingId = e.uuid(body, "media_id")
            val title = e.string(body, "title", 1, 300)
            val kind = e.enum(body, "kind", KINDS)
            if (body.has("format")) e.enum(body, "format", FORMATS)
            validateOptions(e, body.get("options"))
            deckOptions(e, body.get("deck"))
            fun only(key: String, wanted: String) {
                if (body.has(key) && source != null && source != wanted) e.add("/$key", "unexpected", "$key belongs to source $wanted.")
            }
            only("files", "file")
            only("url", "url")
            only("text", "paste")
            val blobs = mutableListOf<Pair<String, String>>()
            when (source) {
                "file" -> {
                    val files = body.get("files")
                    if (files == null) {
                        e.add("/files", "required", "files is required for source file.")
                    } else if (!files.isArray || files.size() !in 1..500) {
                        e.add("/files", "invalid_value", "1 to 500 files.")
                    } else {
                        files.forEachIndexed { i, f ->
                            if (!f.isObject) {
                                e.add("/files/$i", "invalid_type", "Must be an object.")
                                return@forEachIndexed
                            }
                            e.unknownKeys(f as ObjectNode, setOf("blob_hash", "filename", "title", "audio_blob_hash"))
                            for (k in listOf("blob_hash", "audio_blob_hash")) {
                                val h = f.get(k)
                                if (h == null) {
                                    if (k == "blob_hash") e.add("/files/$i/$k", "required", "$k is required.")
                                } else if (!h.isTextual || !HASH_RE.matches(h.asText())) {
                                    e.add("/files/$i/$k", "invalid_value", "A lowercase hex SHA-256.")
                                } else {
                                    blobs += "/files/$i/$k" to h.asText()
                                }
                            }
                            val name = f.get("filename")
                            if (name == null) e.add("/files/$i/filename", "required", "filename is required.") else e.stringValue(name, "/files/$i/filename", 1, 255)
                            f.get("title")?.let { e.stringValue(it, "/files/$i/title", 0, 300) }
                        }
                    }
                }
                "url" -> {
                    val u = body.get("url")
                    if (u == null) {
                        e.add("/url", "required", "url is required for source url.")
                    } else if (!u.isTextual || u.asText().length > 2000 || !URL_RE.matches(u.asText())) {
                        e.add("/url", "invalid_value", "An http or https URL.")
                    }
                }
                "paste" -> {
                    val t = body.get("text")
                    if (t == null) e.add("/text", "required", "text is required for source paste.") else e.stringValue(t, "/text", 1, 1_000_000)
                }
            }
            val mediaId: UUID
            if (existingId != null) {
                for (k in listOf("title", "kind", "deck")) if (body.has(k)) e.add("/$k", "unexpected", "$k must be absent when media_id is given.")
                e.throwIfAny()
                val m = dsl.fetchOptional("select source, deleted_at is not null from app.media where id = ?::uuid", existingId).orElse(null)
                    ?: throw Problems.notFound("No such media.")
                if (m.get(1, Boolean::class.javaObjectType)) {
                    e.add("/media_id", "invalid_value", "The media was deleted.")
                    e.throwIfAny()
                }
                if (m.get(0, String::class.java) != source) {
                    e.add("/source", "invalid_value", "The media's source is ${m.get(0, String::class.java)}.")
                    e.throwIfAny()
                }
                mediaId = UUID.fromString(existingId)
            } else {
                if (title == null && !body.has("title")) e.add("/title", "required", "title is required for new media.")
                if (kind == null && !body.has("kind")) e.add("/kind", "required", "kind is required for new media.")
                mediaId = importId ?: Uuid7.generate()
            }
            blobs.forEach { (at, h) ->
                if (!dsl.fetchExists(dsl.selectOne().from("app.blob").where("hash = ?", h))) {
                    e.add(at, "not_found", "No uploaded blob $h; PUT /blobs/{hash} first.")
                }
            }
            e.throwIfAny()
            val p = body.deepCopy().apply {
                remove("import_id")
                put("media_id", mediaId.toString())
            }
            val r = JobRepo.enqueue(dsl, "media-import", p, dedupeKey = "media-import:$mediaId", id = importId, createdByTokenId = token)
            if (!r.created) {
                if (importId != null && r.job.id == importId) return@rest jobReply(HttpStatusCode.OK, r.job)
                throw alreadyExists(r.job, "An import into this media is already queued or running.")
            }
            if (existingId != null) emitParsing(tx, existingId)
            jobReply(HttpStatusCode.Accepted, r.job, tx.lastSeq)
        }
        send(reply)
    }

    private suspend fun ApplicationCall.importJpdbDeck() {
        val body = jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("jpdb_id", "title", "kind", "deck"))
        for (k in listOf("media_id", "remap_only")) if (body.has(k)) e.add("/$k", "server_set", "$k is set by the server; do not send it.")
        val jpdbId = body.get("jpdb_id")?.let { v ->
            if (v.isTextual && v.asText().length in 1..200 && JPDB_ID_RE.matches(v.asText())) {
                v.asText()
            } else {
                e.add("/jpdb_id", "invalid_value", "A jpdb deck identifier such as anime/5370.")
                null
            }
        }
        if (!body.has("jpdb_id")) e.add("/jpdb_id", "required", "jpdb_id is required.")
        e.string(body, "title", 1, 300)
        e.enum(body, "kind", KINDS)
        deckOptions(e, body.get("deck"))
        e.throwIfAny()
        val token = callerTokenId()
        val reply = opApply.rest(token) { tx ->
            val known = tx.dsl.fetchOptional("select id::text from app.media where jpdb_id = ? and deleted_at is null", jpdbId)
                .map { it.get(0, String::class.java) }.orElse(null)
            val mediaId = known ?: Uuid7.generate().toString()
            val p = body.deepCopy().apply { put("media_id", mediaId) }
            val r = JobRepo.enqueue(tx.dsl, "jpdb-deck-import", p, dedupeKey = "jpdb-deck-import:$jpdbId", createdByTokenId = token)
            if (!r.created) return@rest jobReply(HttpStatusCode.OK, r.job)
            if (known != null) emitParsing(tx, known)
            jobReply(HttpStatusCode.Accepted, r.job, tx.lastSeq)
        }
        send(reply)
    }

    private suspend fun ApplicationCall.reparse() {
        val id = pathId("media_id")
        val token = callerTokenId()
        val reply = opApply.rest(token) { tx ->
            val m = tx.dsl.fetchOptional("select source, deleted_at from app.media where id = ?::uuid", id).orElse(null)
                ?: throw Problems.notFound("No such media.")
            m.get(1, OffsetDateTime::class.java)?.let { throw gone("media", formatInstant(it.toInstant())) }
            if (m.get(0, String::class.java) == "jpdb") {
                throw ApiException(HttpStatusCode.Conflict, "not_applicable", "Not applicable", "A jpdb media has nothing to parse.")
            }
            val r = JobRepo.enqueue(tx.dsl, "media-parse", json.createObjectNode().put("media_id", id), dedupeKey = "media-parse:$id", createdByTokenId = token)
            if (!r.created) return@rest jobReply(HttpStatusCode.OK, r.job)
            emitParsing(tx, id)
            jobReply(HttpStatusCode.Accepted, r.job, tx.lastSeq)
        }
        send(reply)
    }

    // ------------------------------------------------------------------ jpdb mapping corrections

    private val vocabSelect =
        "select v.jpdb_vid, v.spelling, v.reading, v.resolution, v.entry_seq, v.first_seen_at, v.resolved_at, " +
            "coalesce((select sum(l.occurrence_count) from app.jpdb_vocab_media l join app.media m on m.id = l.media_id and m.deleted_at is null " +
            "where l.jpdb_vid = v.jpdb_vid), 0)::bigint as total from app.jpdb_vocab v"

    private fun vocabViews(dsl: DSLContext, rows: List<Record>): List<ObjectNode> {
        if (rows.isEmpty()) return emptyList()
        val vids = rows.map { it.get("jpdb_vid", Long::class.javaObjectType) }.toTypedArray()
        val media = dsl.fetch(
            "select l.jpdb_vid, m.id::text, m.title, l.occurrence_count from app.jpdb_vocab_media l join app.media m on m.id = l.media_id " +
                "and m.deleted_at is null where l.jpdb_vid = any(?) order by l.jpdb_vid, l.occurrence_count desc, m.id",
            vids,
        ).groupBy { it.get(0, Long::class.javaObjectType) }
        return rows.map { r ->
            val vid = r.get("jpdb_vid", Long::class.javaObjectType)
            json.createObjectNode().apply {
                put("jpdb_vid", vid)
                put("spelling", r.get("spelling", String::class.java))
                put("reading", r.get("reading", String::class.java))
                put("resolution", r.get("resolution", String::class.java))
                r.get("entry_seq", Long::class.javaObjectType).let { s -> if (s == null) putNull("entry_seq") else put("entry_seq", s) }
                put("occurrence_total", r.get("total", Long::class.javaObjectType))
                val arr = putArray("media")
                media[vid].orEmpty().forEach {
                    arr.addObject().put("media_id", it.get(1, String::class.java)).put("title", it.get(2, String::class.java))
                        .put("occurrence_count", it.get(3, Int::class.javaObjectType))
                }
                put("first_seen_at", ts(r, "first_seen_at"))
                put("resolved_at", ts(r, "resolved_at"))
            }
        }
    }

    private suspend fun ApplicationCall.listVocab() {
        val q = request.queryParameters
        val e = Errors()
        val status = q["status"] ?: "unresolved"
        if (status !in setOf("unresolved", "mapped", "ignored", "all")) e.add("/status", "invalid_value", "Must be unresolved, mapped, ignored or all.")
        val mediaId = q["media_id"]?.let {
            if (UUID_RE.matches(it)) it else { e.add("/media_id", "invalid_value", "Must be a lowercase UUID."); null }
        }
        val limit = limitOf(this, e)
        var cursor: Pair<Long, Long>? = null
        q["cursor"]?.let { c ->
            val p = dec(c, 2)
            val a = p?.get(0)?.toLongOrNull()
            val b = p?.get(1)?.toLongOrNull()
            if (a == null || b == null) e.add("/cursor", "invalid_value", "Malformed cursor.") else cursor = a to b
        }
        e.throwIfAny()
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        when (status) {
            "unresolved" -> { where += "resolution = 'unresolved'"; where += "total > 0" }
            "mapped", "ignored" -> where += "resolution = '$status'"
        }
        mediaId?.let {
            where += "exists (select 1 from app.jpdb_vocab_media l join app.media m on m.id = l.media_id and m.deleted_at is null " +
                "where l.jpdb_vid = t.jpdb_vid and l.media_id = ?::uuid)"
            args += it
        }
        cursor?.let {
            where += "(total, -jpdb_vid) < (?::bigint, ?::bigint)"
            args += it.first
            args += -it.second
        }
        val page = db.tx { dsl ->
            val rows = dsl.fetch(
                "select * from ($vocabSelect) t" + (if (where.isEmpty()) "" else " where " + where.joinToString(" and ")) +
                    " order by total desc, jpdb_vid asc limit ${limit + 1}",
                *args.toTypedArray(),
            )
            val shown = rows.take(limit)
            val next = if (rows.size > limit) {
                shown.last().let { enc(it.get("total", Long::class.javaObjectType).toString(), it.get("jpdb_vid", Long::class.javaObjectType).toString()) }
            } else {
                null
            }
            mapOf("items" to vocabViews(dsl, shown), "next_cursor" to next)
        }
        respondJson(page)
    }

    private fun ApplicationCall.vid(): Long =
        parameters["jpdb_vid"]?.toLongOrNull()?.takeIf { it >= 1 } ?: throw Problems.notFound("No such jpdb vocabulary.")

    private suspend fun ApplicationCall.putResolution() {
        val vid = vid()
        val body = jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("resolution", "entry_seq"))
        val resolution = e.enum(body, "resolution", setOf("mapped", "ignored"), required = true)
        val seq = body.get("entry_seq")?.let { e.long(it, "/entry_seq", 1) }
        if (resolution == "mapped" && !body.has("entry_seq")) e.add("/entry_seq", "required", "entry_seq is required for mapped.")
        if (resolution == "ignored" && body.has("entry_seq")) e.add("/entry_seq", "unexpected", "entry_seq must be absent for ignored.")
        e.throwIfAny()
        resolve(vid, resolution ?: error("validated"), if (resolution == "mapped") seq else null)
    }

    private suspend fun ApplicationCall.clearResolution() = resolve(vid(), "unresolved", null)

    /** MEDIA_PIPELINE.md 5.4: locks 7033001 (OpApply) then 7033011, then decide, write, enqueue remaps, emit `parsing`. */
    private suspend fun ApplicationCall.resolve(vid: Long, resolution: String, entrySeq: Long?) {
        val token = callerTokenId()
        val reply = opApply.rest(token) { tx ->
            val dsl = tx.dsl
            dsl.execute("select pg_advisory_xact_lock(?)", JPDB_MAPPING_LOCK)
            val cur = dsl.fetchOptional("select resolution, entry_seq from app.jpdb_vocab where jpdb_vid = ?", vid).orElse(null)
                ?: throw Problems.notFound("No such jpdb vocabulary.")
            if (resolution == "mapped" &&
                !dsl.fetchExists(dsl.selectOne().from("ref.dict_entry").where("seq = ? and retired_release_id is null", entrySeq))
            ) {
                Errors().apply { add("/entry_seq", "not_found", "No live dictionary entry $entrySeq.") }.throwIfAny()
            }
            val jobs = mutableListOf<JsonNode>()
            if (cur.get(0, String::class.java) != resolution || cur.get(1, Long::class.javaObjectType) != entrySeq) {
                dsl.execute(
                    "update app.jpdb_vocab set resolution = ?, entry_seq = ?, " +
                        "resolved_at = case when ? = 'unresolved' then null else now() end where jpdb_vid = ?",
                    resolution, entrySeq, resolution, vid,
                )
                val affected = dsl.fetch(
                    "select m.id::text, m.jpdb_id from app.jpdb_vocab_media l join app.media m on m.id = l.media_id where l.jpdb_vid = ? " +
                        "and m.deleted_at is null and m.source = 'jpdb' and m.jpdb_id is not null order by m.id",
                    vid,
                )
                for (m in affected) {
                    val mid = m.get(0, String::class.java)
                    val p = json.createObjectNode().put("jpdb_id", m.get(1, String::class.java)).put("media_id", mid).put("remap_only", true)
                    val r = JobRepo.enqueue(dsl, "jpdb-deck-import", p, dedupeKey = "jpdb-deck-remap:$mid", createdByTokenId = token)
                    jobs.add(json.valueToTree<JsonNode>(r.job.toApi()))
                    if (r.created) emitParsing(tx, mid)
                }
            }
            val row = vocabViews(dsl, dsl.fetch("select * from ($vocabSelect) t where jpdb_vid = ?", vid)).single()
            val out = json.createObjectNode()
            out.set<JsonNode>("vocab", row)
            out.putArray("jobs").addAll(jobs)
            Reply(HttpStatusCode.OK, out, tx.lastSeq)
        }
        send(reply)
    }
}
