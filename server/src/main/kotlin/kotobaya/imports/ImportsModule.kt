package kotobaya.imports

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotobaya.jobs.JobRepo
import kotobaya.notes.Errors
import kotobaya.notes.callerTokenId
import kotobaya.notes.jsonBody
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply

private val BLOB_HASH_RE = Regex("^[0-9a-f]{64}$")

/**
 * Imports (T207b): `importJpdbHistory` and the server half of the `jpdb-history-import` job.
 *
 * Wired in `composeApp` (`ImportsModule(db, sync.opApply)`, handlers merged, `start(scope)` in `main`). The worker half
 * (`worker/.../importers/jpdb_history/apply.py`) is the other end of the protocol below.
 */
class ImportsModule(private val db: Database, opApply: OpApply) {
    val jpdbHistory = JpdbHistoryApply(opApply)
    val applyRunner = JpdbHistoryApplyRunner(db, jpdbHistory)

    val handlers: Map<String, OperationHandler> = mapOf(
        "importJpdbHistory" to { importJpdbHistory() },
    )

    /** Starts the loop that applies planned jpdb imports ([JpdbHistoryApplyRunner]). */
    fun start(scope: CoroutineScope): Job = applyRunner.start(scope)

    private suspend fun ApplicationCall.importJpdbHistory() {
        val body = jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("blob_hash", "import_reviews", "import_word_status"))
        val hash = body.get("blob_hash")
        if (hash == null) {
            e.add("/blob_hash", "required", "blob_hash is required.")
        } else if (!hash.isTextual || !BLOB_HASH_RE.matches(hash.asText())) {
            e.add("/blob_hash", "invalid_value", "A lowercase hex SHA-256.")
        }
        val flags = listOf("import_reviews", "import_word_status").associateWith { k ->
            val v = body.get(k)
            if (v != null && !v.isBoolean) e.add("/$k", "invalid_value", "Must be a boolean.")
            v?.takeIf { it.isBoolean }?.asBoolean() ?: true
        }
        e.throwIfAny()
        val blobHash = hash?.asText() ?: error("validated")
        val tokenId = callerTokenId()
        val (status, job) = db.tx { dsl ->
            if (!dsl.fetchExists(dsl.selectOne().from("app.blob").where("hash = ?", blobHash))) {
                Errors().apply { add("/blob_hash", "not_found", "No uploaded blob $blobHash; PUT /blobs/{hash} first.") }.throwIfAny()
            }
            val payload = json.createObjectNode().put("blob_hash", blobHash)
            flags.forEach { (k, v) -> payload.put(k, v) }
            val r = JobRepo.enqueue(dsl, JpdbHistoryApplyRunner.PUBLIC_KIND, payload, dedupeKey = JpdbHistoryApplyRunner.PUBLIC_KIND, createdByTokenId = tokenId)
            (if (r.created) HttpStatusCode.Accepted else HttpStatusCode.OK) to r.job.toApi()
        }
        respondJson(job, status)
    }
}

/**
 * The server half of a `jpdb-history-import` job. The worker (T207a, `runner` registration) claims the public job,
 * reads the export and plans; its `apply_plan` hands the plan to this runner through a **server-internal** job row
 * (JobRepo: internal kinds are never listed or returned by the API) and waits for it:
 *
 * 1. worker: `insert into app.job (id, kind, payload, dedupe_key, max_attempts) values (<uuid7>, 'jpdb-history-apply',
 *    {"parent_job_id": <public job id>, "plan": <ImportPlan.to_json()>}, 'jpdb-history-apply:<public job id>', 1)`
 *    (`on conflict do nothing`; then read the row holding the dedupe key) and `pg_notify` as usual;
 * 2. this runner claims it (`SKIP LOCKED`), applies the plan through [JpdbHistoryApply], writes the parent's
 *    `progress` (guarded by `status = 'running'`, T109a) and stops at the next batch once the parent is no longer
 *    running; it ends the internal row `succeeded` with the `JobResultJpdbHistory` as `result` (the plan is dropped
 *    from its payload), `cancelled`, or `failed` with `error`;
 * 3. worker: polls the internal row (and `ctx.check_cancelled()`), returns its `result` as the public job's result,
 *    or raises with its `error`.
 *
 * A running internal row whose `heartbeat_at` is older than [staleAfterMs] (server restart mid-run) is re-queued;
 * the apply is idempotent, so the re-run writes only what is missing. Each claim counts in `attempts`; a row already
 * claimed [maxStaleRequeues] times is ended `failed` instead (a poison plan must not crash-loop the server). The
 * runner heartbeats every [heartbeatMs] from a side thread, so a batch that takes minutes is never stale.
 */
class JpdbHistoryApplyRunner(
    private val db: Database,
    private val apply: JpdbHistoryApply,
    private val pollMs: Long = 1_000,
    private val staleAfterMs: Long = 300_000,
    private val heartbeatMs: Long = 10_000,
    private val maxStaleRequeues: Int = MAX_STALE_REQUEUES,
    private val instanceId: String = "server-" + java.util.UUID.randomUUID().toString().take(8),
) {
    private val log = org.slf4j.LoggerFactory.getLogger("kotobaya.imports.jpdb")

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val ran = try {
                runOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("jpdb apply runner failed", e)
                false
            }
            if (!ran) delay(pollMs)
        }
    }

    /** Claims and runs one queued internal apply job; false if there was none. */
    fun runOnce(): Boolean {
        val claimed = db.txBlocking { dsl ->
            val stale = "kind = ? and status = 'running' and coalesce(heartbeat_at, started_at, created_at) < now() - make_interval(secs => ?)"
            val failed = dsl.fetch(
                "update app.job set status = 'failed', finished_at = now(), locked_by = null, payload = payload - 'plan', " +
                    "error = 'server stopped mid-apply ' || attempts || ' times; giving up' where $stale and attempts >= ? returning id",
                INTERNAL_KIND, staleAfterMs / 1000.0, maxStaleRequeues,
            )
            dsl.execute(
                "update app.job set status = 'queued', locked_by = null, run_after = now() where $stale and attempts < ?",
                INTERNAL_KIND, staleAfterMs / 1000.0, maxStaleRequeues,
            )
            failed.forEach { dsl.execute("select pg_notify('kotobaya_events', ?)", """{"type":"job.updated","data":{"id":"${it.get(0)}"}}""") }
            dsl.fetchOptional(
                "update app.job set status = 'running', locked_by = ?, attempts = attempts + 1, started_at = coalesce(started_at, now()), " +
                    "heartbeat_at = now() where id = (select id from app.job where kind = ? and status = 'queued' and run_after <= now() " +
                    "order by run_after, created_at for update skip locked limit 1) returning id, payload::text",
                instanceId, INTERNAL_KIND,
            ).map { it.get(0, java.util.UUID::class.java) to json.readTree(it.get(1, String::class.java)) }.orElse(null)
        } ?: return false
        val (id, payload) = claimed
        val parent = runCatching { java.util.UUID.fromString(payload.path("parent_job_id").asText()) }.getOrNull()
        val plan = payload.get("plan")
        val beat = startHeartbeat(id)
        try {
            requireNotNull(parent) { "jpdb-history-apply payload lacks parent_job_id" }
            requireNotNull(plan) { "jpdb-history-apply payload lacks plan" }
            val result = apply.apply(plan, ParentProgress(id, parent))
            beat.stop()
            finish(id, "succeeded", result = json.writeValueAsString(result))
        } catch (e: ImportCancelled) {
            beat.stop()
            finish(id, "cancelled")
        } catch (e: CancellationException) {
            beat.stop()
            throw e
        } catch (e: Exception) {
            beat.stop()
            log.error("jpdb history import {} failed", parent, e)
            finish(id, "failed", error = e.message ?: e.javaClass.simpleName)
        } finally {
            beat.stop()
        }
        return true
    }

    private class Beat(private val thread: Thread) {
        fun stop() {
            thread.interrupt()
            thread.join(5_000)
        }
    }

    /** Refreshes the child's `heartbeat_at` every [heartbeatMs] until stopped, whatever the apply is doing. */
    private fun startHeartbeat(id: java.util.UUID): Beat {
        val t = Thread {
            try {
                while (true) {
                    Thread.sleep(heartbeatMs)
                    runCatching { heartbeat(id) }.onFailure { log.warn("jpdb apply heartbeat failed", it) }
                }
            } catch (_: InterruptedException) {
                // stopped
            }
        }
        t.isDaemon = true
        t.name = "jpdb-apply-heartbeat"
        t.start()
        return Beat(t)
    }

    private fun heartbeat(id: java.util.UUID) {
        db.txBlocking { dsl ->
            dsl.execute("update app.job set heartbeat_at = now() where id = ? and status = 'running' and locked_by = ?", id, instanceId)
        }
    }

    private fun finish(id: java.util.UUID, status: String, result: String? = null, error: String? = null) {
        db.txBlocking { dsl ->
            dsl.execute(
                "update app.job set status = ?, result = ?::jsonb, error = ?, finished_at = now(), locked_by = null, " +
                    "progress = case when ? = 'succeeded' then 1 else progress end, payload = payload - 'plan' " +
                    "where id = ? and status = 'running' and locked_by = ?",
                status, result, error, status, id, instanceId,
            )
            dsl.execute("select pg_notify('kotobaya_events', ?)", """{"type":"job.updated","data":{"id":"$id"}}""")
        }
    }

    /** Parent progress in [PLANNED, 1) (the worker reports up to [PLANNED] while planning); cancel = either row stopped. */
    private inner class ParentProgress(private val id: java.util.UUID, private val parent: java.util.UUID) : ImportProgress {
        override fun report(fraction: Double, message: String) {
            db.txBlocking { dsl ->
                dsl.execute("update app.job set heartbeat_at = now() where id = ? and status = 'running' and locked_by = ?", id, instanceId)
                dsl.execute(
                    "update app.job set progress = ?, progress_message = ? where id = ? and status = 'running'",
                    (PLANNED + (0.99 - PLANNED) * fraction).toFloat(), message, parent,
                )
                dsl.execute("select pg_notify('kotobaya_events', ?)", """{"type":"job.updated","data":{"id":"$parent"}}""")
            }
        }

        override fun cancelled(): Boolean = db.txBlocking { dsl ->
            val running = dsl.fetchValue(
                "select count(*) from app.job where (id = ? and status = 'running' and locked_by = ?) or (id = ? and status = 'running')",
                id, instanceId, parent,
            ) as Number
            running.toInt() < 2
        }
    }

    companion object {
        const val PUBLIC_KIND = "jpdb-history-import"
        const val INTERNAL_KIND = "jpdb-history-apply"
        private const val PLANNED = 0.2
        const val MAX_STALE_REQUEUES = 3
    }
}
