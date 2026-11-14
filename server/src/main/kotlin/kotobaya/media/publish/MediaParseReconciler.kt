package kotobaya.media.publish

import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.server.Database
import kotobaya.sync.OpApply
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * MEDIA_PIPELINE.md 4 "Reconciler": the server's `parsing` -> `failed` | `parsed` transitions. For each live media
 * stuck at `parsing` with no queued or running `media-import` / `media-parse` / `jpdb-deck-import` job for it, emit
 * `media.content_published` with the same version and blob hash: `failed` (with the last finished job's error) if
 * that job failed, else `parsed`. One op transaction (lock 7033001) per media; the media is re-read under the lock.
 */
class MediaParseReconciler(private val db: Database, private val opApply: OpApply) {
    private val log = LoggerFactory.getLogger("kotobaya.media.publish")

    /** Reconciles every live `parsing` media; returns how many it changed. */
    fun reconcileAll(): Int {
        val ids = db.txBlocking { dsl ->
            dsl.fetch("select id from app.media where deleted_at is null and parse_status = 'parsing' order by id")
                .map { it.get(0, UUID::class.java) }
        }
        return ids.count { id ->
            runCatching { reconcile(id) }.onFailure { log.warn("reconcile media {} failed", id, it) }.getOrDefault(false)
        }
    }

    /** True if an op was emitted for [mediaId]. */
    fun reconcile(mediaId: UUID): Boolean = opApply.transactionBlocking(null) { tx ->
        val dsl = tx.dsl
        val m = dsl.fetchOptional(
            "select parse_status, content_version, content_blob_hash from app.media where id = ? and deleted_at is null for update", mediaId,
        ).orElse(null) ?: return@transactionBlocking false
        if (m.get(0, String::class.java) != "parsing") return@transactionBlocking false
        val active = dsl.fetchExists(
            dsl.selectOne().from("app.job").where(
                "kind in ($KINDS) and status in ('queued', 'running') and payload->>'media_id' = ?", mediaId.toString(),
            ),
        )
        if (active) return@transactionBlocking false
        val last = dsl.fetchOptional(
            "select status, error from app.job where kind in ($KINDS) and payload->>'media_id' = ? and finished_at is not null " +
                "order by finished_at desc, id desc limit 1",
            mediaId.toString(),
        ).orElse(null)
        val failed = last?.get(0, String::class.java) == "failed"
        val error = if (failed) firstCodePoints(last?.get(1, String::class.java) ?: "job failed", 2000) else null
        val hash = m.get(2, String::class.java)
        tx.emit(
            OpType.MEDIA_CONTENT_PUBLISHED,
            MediaPublishRunner.obj(
                "media_id" to JsonString(mediaId.toString()),
                "parse_status" to JsonString(if (failed) "failed" else "parsed"),
                "parse_error" to (error?.let(::JsonString) ?: JsonNull),
                "content_version" to JsonNumber(m.get(1, Int::class.java).toDouble()),
                "content_blob_hash" to (hash?.let(::JsonString) ?: JsonNull),
            ),
        )
        true
    }

    companion object {
        private const val KINDS = "'media-import', 'media-parse', 'jpdb-deck-import'"

        private fun firstCodePoints(s: String, n: Int): String =
            if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))
    }
}
