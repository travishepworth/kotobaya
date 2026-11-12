package kotobaya.jobs

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpStatusCode
import kotobaya.server.ApiException
import kotobaya.server.Uuid7
import kotobaya.server.formatInstant
import kotobaya.server.json
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The closed `JobKind` enum of the API. Server-internal kinds in `app.job` are never read or exposed. */
val API_JOB_KINDS: List<String> = listOf(
    "dict-refresh", "media-import", "media-parse", "jpdb-deck-import", "jpdb-history-import",
    "fsrs-optimize", "tts", "deck-build",
)
val JOB_STATUSES: List<String> = listOf("queued", "running", "succeeded", "failed", "cancelled")
private val TERMINAL = setOf("succeeded", "failed", "cancelled")

// Constants only; safe to inline into SQL.
private val KIND_LIST = API_JOB_KINDS.joinToString(",") { "'$it'" }

/**
 * Job retention (decision, T109a).
 *
 * - Terminal jobs (`succeeded`, `failed`, `cancelled`) are kept for [RETENTION_DAYS] days after `finished_at`
 *   (the API promises at least 30), then pruned by [JobRepo.prune], which the API runs once a day in-process.
 * - Import kinds ([KEEP_FOREVER_KINDS]) are never pruned: `importMedia.import_id` idempotency and
 *   `importJpdbHistory` retries rely on the job row, and these rows are small.
 * - Queued and running jobs are never pruned.
 */
const val RETENTION_DAYS = 90
val KEEP_FOREVER_KINDS: List<String> = listOf("media-import", "jpdb-deck-import", "jpdb-history-import")

class JobRow(
    val id: UUID,
    val kind: String,
    val status: String,
    val payload: JsonNode,
    val result: JsonNode?,
    val progress: Double,
    val progressMessage: String?,
    val error: String?,
    val attempts: Int,
    val maxAttempts: Int,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
) {
    val terminal: Boolean get() = status in TERMINAL

    /** The API `Job` object. */
    fun toApi(): Map<String, Any?> = linkedMapOf(
        "id" to id.toString(),
        "kind" to kind,
        "status" to status,
        "payload" to payload,
        "result" to result,
        "progress" to progress,
        "progress_message" to progressMessage,
        "error" to error,
        "attempts" to attempts,
        "max_attempts" to maxAttempts,
        "created_at" to formatInstant(createdAt),
        "started_at" to startedAt?.let(::formatInstant),
        "finished_at" to finishedAt?.let(::formatInstant),
    )
}

class EnqueueResult(val job: JobRow, /** False when an existing job was returned (dedupe or same id). */ val created: Boolean)

object JobRepo {
    internal const val COLUMNS =
        "id, kind, status, payload::text, result::text, progress::text, progress_message, error, attempts, max_attempts, " +
            "created_at, started_at, finished_at"

    internal fun map(r: Record): JobRow {
        fun ts(i: Int): Instant? = r.get(i, OffsetDateTime::class.java)?.toInstant()
        return JobRow(
            id = r.get(0, UUID::class.java),
            kind = r.get(1, String::class.java),
            status = r.get(2, String::class.java),
            payload = json.readTree(r.get(3, String::class.java)),
            result = r.get(4, String::class.java)?.let(json::readTree),
            progress = r.get(5, String::class.java).toDouble(),
            progressMessage = r.get(6, String::class.java),
            error = r.get(7, String::class.java),
            attempts = r.get(8, Int::class.javaObjectType),
            maxAttempts = r.get(9, Int::class.javaObjectType),
            createdAt = requireNotNull(ts(10)),
            startedAt = ts(11),
            finishedAt = ts(12),
        )
    }

    fun get(dsl: DSLContext, id: UUID): JobRow? =
        dsl.fetchOptional("select $COLUMNS from app.job where id = ? and kind in ($KIND_LIST)", id).map(::map).orElse(null)

    /**
     * Enqueue a job. Used by the endpoints of other tickets (call inside their transaction).
     *
     * - [dedupeKey]: an already queued or running job with the same key is returned instead ([EnqueueResult.created] false).
     * - [id]: caller-chosen job id (`import_id`); if a job with this id exists, in any status, it is returned.
     * Callers that need a kind check on a returned job (`import_id` used by another kind is 409) compare `job.kind`.
     */
    fun enqueue(
        dsl: DSLContext,
        kind: String,
        payload: JsonNode,
        dedupeKey: String? = null,
        id: UUID? = null,
        maxAttempts: Int = 3,
        createdByTokenId: UUID? = null,
        runAfter: Instant? = null,
    ): EnqueueResult {
        val newId = id ?: Uuid7.generate()
        repeat(5) {
            val inserted = dsl.fetch(
                "insert into app.job (id, kind, payload, dedupe_key, max_attempts, created_by_token_id, run_after) " +
                    "values (?, ?, ?::jsonb, ?, ?, ?, coalesce(?, now())) on conflict do nothing returning $COLUMNS",
                newId, kind, json.writeValueAsString(payload), dedupeKey, maxAttempts, createdByTokenId,
                runAfter?.atOffset(ZoneOffset.UTC),
            )
            if (inserted.isNotEmpty()) return EnqueueResult(map(inserted[0]), true)
            // Conflict: either the id exists (any status) or the dedupe key is held by a queued/running job.
            if (id != null) {
                val byId = dsl.fetchOptional("select $COLUMNS from app.job where id = ?", id)
                if (byId.isPresent) return EnqueueResult(map(byId.get()), false)
            }
            if (dedupeKey != null) {
                val byKey = dsl.fetchOptional(
                    "select $COLUMNS from app.job where dedupe_key = ? and status in ('queued','running')", dedupeKey,
                )
                if (byKey.isPresent) return EnqueueResult(map(byKey.get()), false)
            }
            // The holder finished between the insert and the lookup: try again.
        }
        error("could not enqueue $kind job")
    }

    /**
     * `importMedia` retry (OpenAPI): the job with id [importId] in any status; if its row is gone, the most recent
     * `media-import` job for [mediaId]; null means 404. The caller answers 409 `already_exists` when the returned
     * job's kind is not `media-import` (only possible for the by-id hit).
     */
    fun resolveImportRetry(dsl: DSLContext, importId: UUID, mediaId: UUID = importId): JobRow? {
        get(dsl, importId)?.let { return it }
        return dsl.fetchOptional(
            "select $COLUMNS from app.job where kind = 'media-import' and payload->>'media_id' = ? " +
                "order by created_at desc, id desc limit 1",
            mediaId.toString(),
        ).map(::map).orElse(null)
    }

    class Page(val items: List<JobRow>, val nextCursor: String?)

    fun list(dsl: DSLContext, kind: String?, statuses: List<String>?, limit: Int, cursor: Pair<Instant, UUID>?): Page {
        val where = mutableListOf("kind in ($KIND_LIST)")
        val args = mutableListOf<Any?>()
        if (kind != null) {
            where += "kind = ?"
            args += kind
        }
        // Statuses are validated against JOB_STATUSES by the caller, so inlining is safe.
        if (statuses != null) where += "status in (${statuses.joinToString(",") { "'$it'" }})"
        if (cursor != null) {
            where += "(created_at, id) < (?::timestamptz, ?::uuid)"
            args += cursor.first.atOffset(ZoneOffset.UTC)
            args += cursor.second
        }
        args += limit + 1
        val rows = dsl.fetch(
            "select $COLUMNS from app.job where ${where.joinToString(" and ")} order by created_at desc, id desc limit ?",
            *args.toTypedArray(),
        ).map(::map)
        val page = rows.take(limit)
        val next = if (rows.size > limit) encodeCursor(page.last().createdAt, page.last().id) else null
        return Page(page, next)
    }

    fun encodeCursor(at: Instant, id: UUID): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.writeValueAsBytes(mapOf("k" to listOf(at.toString(), id.toString()))))

    fun decodeCursor(s: String): Pair<Instant, UUID>? = runCatching {
        val k = json.readTree(java.util.Base64.getUrlDecoder().decode(s)).path("k")
        Instant.parse(k.path(0).asText()) to UUID.fromString(k.path(1).asText())
    }.getOrNull()

    /**
     * Cancel (OpenAPI `cancelJob`): queued or running becomes `cancelled` with `finished_at = now` at once; a running
     * job's worker notices the status at its next heartbeat (T109b: heartbeat updates must be guarded by
     * `status = 'running'`). Already cancelled returns the job unchanged; succeeded or failed is 409.
     */
    fun cancel(dsl: DSLContext, id: UUID): JobRow {
        val current = dsl.fetchOptional("select $COLUMNS from app.job where id = ? and kind in ($KIND_LIST) for update", id)
            .map(::map).orElseThrow { ApiException(HttpStatusCode.NotFound, "not_found", "Not found", "No such job.") }
        return when (current.status) {
            "cancelled" -> current
            "queued", "running" -> dsl.fetch(
                "update app.job set status = 'cancelled', finished_at = now(), locked_by = null where id = ? returning $COLUMNS", id,
            ).map(::map).first()
            else -> throw ApiException(
                HttpStatusCode.Conflict, "job_not_cancellable", "Job not cancellable",
                "The job already ${current.status}.",
            )
        }
    }

    /** Delete terminal jobs past retention (see [RETENTION_DAYS]). Returns the number deleted. */
    fun prune(dsl: DSLContext, retentionDays: Int = RETENTION_DAYS): Int {
        val keep = KEEP_FOREVER_KINDS.joinToString(",") { "'$it'" }
        return dsl.execute(
            "delete from app.job where status in ('succeeded','failed','cancelled') and kind not in ($keep) " +
                "and finished_at < now() - make_interval(days => ?)",
            retentionDays,
        )
    }
}
