package kotobaya.optimizer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType
import kotobaya.server.Database
import kotobaya.server.SERVER_DEVICE_ID
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.OpApply
import kotobaya.sync.OpInputInvalid
import kotobaya.sync.OpRefused
import kotobaya.sync.OpTx
import kotobaya.sync.SyncJson
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The server half of the `fsrs-optimize` hand-off (contracts/db/FSRS_PUBLISH.md 2, C-16): the only claimer of the
 * server-internal job kind `fsrs-params-publish` (SCHEMA.md 4.4) and the only minter of `fsrs_params.published`.
 *
 * Per claimed child, with no lock held: validate the payload (including the strict `log_loss_after < log_loss_before`
 * gate: the worker is never trusted), build the op payload and validate it against the op schema (no clamping), read
 * the parent. Then, in ONE op transaction (advisory lock 7033001, taken first by [OpApply]): re-check the claim and
 * the parent, no-op if the same parent already published, lock the preset row, check `base_weights_version` against
 * the stored one (`stale_base`), mint exactly one op with `weights_version = base + 1`, and end the child `succeeded`
 * with `JobResultFsrsOptimize` as its result. Any error rolls everything back and the child ends `failed` with
 * `<code>: <message>` in a new transaction. Replay of the cards is lazy (OPS.md; the op's apply marks them stale).
 */
class FsrsParamsPublishRunner(
    private val db: Database,
    private val opApply: OpApply,
    private val pollMs: Long = 1_000,
    private val staleAfterMs: Long = 300_000,
    private val heartbeatMs: Long = 30_000,
    private val instanceId: String = "server-" + UUID.randomUUID().toString().take(8),
) {
    private val log = LoggerFactory.getLogger("kotobaya.optimizer.publish")

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val ran = try {
                runOnce()
            } catch (e: Exception) {
                log.error("fsrs params publish runner failed", e)
                false
            }
            if (!ran) delay(pollMs)
        }
    }

    /** Re-queues stale children, then claims and runs one queued child; false if there was none. */
    fun runOnce(): Boolean {
        val token = "$instanceId:${UUID.randomUUID()}"
        val claimed = db.txBlocking { dsl ->
            dsl.execute(
                "update app.job set status = 'queued', locked_by = null where kind = ? and status = 'running' " +
                    "and coalesce(heartbeat_at, started_at, created_at) < now() - make_interval(secs => ?)",
                KIND, staleAfterMs / 1000.0,
            )
            dsl.fetchOptional(
                "update app.job set status = 'running', locked_by = ?, attempts = attempts + 1, started_at = now(), heartbeat_at = now() " +
                    "where id = (select id from app.job where kind = ? and status = 'queued' and run_after <= now() " +
                    "order by run_after, created_at for update skip locked limit 1) returning id, payload::text",
                token, KIND,
            ).map { it.get(0, UUID::class.java) to json.readTree(it.get(1, String::class.java)) }.orElse(null)
        } ?: return false
        val (id, payload) = claimed
        val beat = startHeartbeat(id, token)
        try {
            run(id, token, payload)
        } finally {
            beat.stop()
        }
        return true
    }

    /** The claim moved to another runner (stale re-queue); roll back and leave the child alone. */
    private class LostClaim : RuntimeException("claim lost")

    private fun run(id: UUID, token: String, raw: JsonNode) {
        try {
            val p = FsrsParamsPublishPayload.parse(raw)
            val op = opPayload(p)
            checkBounds(p, op)
            val parent = db.txBlocking { dsl ->
                dsl.fetchOptional("select kind, status, payload->>'preset_id' from app.job where id = ?", p.parentJobId)
                    .map { Triple(it.get(0, String::class.java), it.get(1, String::class.java), it.get(2, String::class.java)) }.orElse(null)
            }
            if (parent == null || parent.first != PARENT_KIND || parent.third != p.presetId) {
                throw FsrsPublishFailure(FsrsPublishFailure.INVALID_PAYLOAD, "parent job ${p.parentJobId} is not an $PARENT_KIND job for preset ${p.presetId}")
            }
            if (parent.second != "running") {
                end(id, token, "cancelled")
                return
            }
            val outcome = opApply.transactionBlocking(null) { tx -> publish(tx, id, token, p, op) }
            log.info("fsrs params publish {} for preset {}: {}", id, p.presetId, outcome)
        } catch (e: LostClaim) {
            log.warn("fsrs params publish {}: claim lost, left to the new claim", id)
        } catch (e: FsrsPublishFailure) {
            log.warn("fsrs params publish {} failed: {}", id, e.error)
            end(id, token, "failed", error = e.error)
        } catch (e: OpInputInvalid) {
            end(id, token, "failed", error = "${FsrsPublishFailure.WEIGHTS_OUT_OF_BOUNDS}: ${e.message}")
        } catch (e: Exception) {
            log.error("fsrs params publish {} failed", id, e)
            end(id, token, "failed", error = "${FsrsPublishFailure.INTERNAL}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private enum class Outcome { PUBLISHED, ALREADY_PUBLISHED, PRESET_DELETED, CANCELLED }

    /** The op payload of FSRS_PUBLISH.md 2.3. */
    private fun opPayload(p: FsrsParamsPublishPayload): JsonObject {
        val n = json.createObjectNode()
        n.put("id", p.presetId)
        n.put("weights_version", p.baseWeightsVersion + 1)
        n.put("fsrs_version", p.fsrsVersion)
        n.set<JsonNode>("weights", json.createArrayNode().also { a -> p.weights.forEach { a.add(it) } })
        n.put("optimized_at", p.optimizedAt)
        n.set<JsonNode>(
            "optimizer_stats",
            json.createObjectNode()
                .put("review_count", p.reviewCount).put("log_loss", p.logLossAfter).put("rmse_bins", p.rmseBins)
                .put("log_loss_before", p.logLossBefore).put("holdout_review_count", p.holdoutReviewCount),
        )
        return SyncJson.fromJackson(n) as JsonObject
    }

    /** Step 2: the op payload against `fsrs_params.published.schema.json` (never clamped). */
    private fun checkBounds(p: FsrsParamsPublishPayload, payload: JsonObject) {
        val op = Op(
            Uuid7.generate().toString(), OpType.FSRS_PARAMS_PUBLISHED, 1, OpType.FSRS_PARAMS_PUBLISHED.entityType, p.presetId,
            SERVER_DEVICE_ID, "0193a1b2c007-0000-$SERVER_DEVICE_ID", payload,
        )
        when (val d = DefaultOpCodec.decodeTree(DefaultOpCodec.encodeTree(op), OpSource.PUSHED)) {
            is OpDecodeResult.Known -> Unit
            is OpDecodeResult.Invalid -> throw FsrsPublishFailure(FsrsPublishFailure.WEIGHTS_OUT_OF_BOUNDS, "${d.pointer.removePrefix("/payload")}: ${d.message}")
            is OpDecodeResult.Unsupported -> throw FsrsPublishFailure(FsrsPublishFailure.INTERNAL, "unsupported op ${d.opType}")
        }
    }

    private fun result(p: FsrsParamsPublishPayload, published: Boolean, version: Int, reason: String?): ObjectNode = json.createObjectNode().apply {
        put("preset_id", p.presetId)
        put("published", published)
        put("weights_version", version)
        put("review_count", p.reviewCount)
        put("log_loss", p.logLossAfter)
        put("rmse_bins", p.rmseBins)
        put("log_loss_before", p.logLossBefore)
        put("holdout_review_count", p.holdoutReviewCount)
        if (reason == null) putNull("reason") else put("reason", reason)
    }

    // ------------------------------------------------------------------ the transaction (FSRS_PUBLISH.md 2.2 steps 4-8)

    private fun publish(tx: OpTx, id: UUID, token: String, p: FsrsParamsPublishPayload, op: JsonObject): Outcome {
        val dsl = tx.dsl
        // 4. The claim is still ours and the parent still running.
        val child = dsl.fetchOptional("select status, locked_by from app.job where id = ? for update", id).orElse(null)
        if (child == null || child.get(0, String::class.java) != "running" || child.get(1, String::class.java) != token) throw LostClaim()
        val parentStatus = dsl.fetchOptional("select status from app.job where id = ? for update", p.parentJobId).map { it.get(0, String::class.java) }.orElse(null)
        if (parentStatus != "running") {
            endInTx(tx, id, token, "cancelled", null)
            return Outcome.CANCELLED
        }
        // 5. The same parent already published: end with that result, mint nothing.
        val earlier = dsl.fetchOptional(
            "select result::text from app.job where kind = ? and id <> ? and status = 'succeeded' and payload->>'parent_job_id' = ? " +
                "and result is not null order by finished_at desc limit 1",
            KIND, id, p.parentJobId.toString(),
        ).map { it.get(0, String::class.java) }.orElse(null)
        if (earlier != null) {
            endInTx(tx, id, token, "succeeded", json.readTree(earlier) as ObjectNode)
            return Outcome.ALREADY_PUBLISHED
        }
        // 6. The preset.
        val preset = dsl.fetchOptional(
            "select weights_version, deleted_at is not null from app.fsrs_params where id = ?::uuid for update", p.presetId,
        ).orElse(null) ?: throw FsrsPublishFailure(FsrsPublishFailure.UNKNOWN_PRESET, "no preset ${p.presetId}")
        val stored = preset.get(0, Int::class.java)
        if (preset.get(1, Boolean::class.java)) {
            endInTx(tx, id, token, "succeeded", result(p, false, stored, "preset_deleted"))
            notifyParent(tx, p, progress = false)
            return Outcome.PRESET_DELETED
        }
        if (stored != p.baseWeightsVersion) {
            throw FsrsPublishFailure(FsrsPublishFailure.STALE_BASE, "preset ${p.presetId} is at weights_version $stored, the run compared against ${p.baseWeightsVersion}")
        }
        // 7. One server op.
        try {
            tx.emit(OpType.FSRS_PARAMS_PUBLISHED, op)
        } catch (e: OpRefused) {
            throw FsrsPublishFailure(FsrsPublishFailure.INTERNAL, "op refused: ${e.message}")
        }
        // 8. The child succeeds with the op; the parent shows "publishing".
        endInTx(tx, id, token, "succeeded", result(p, true, p.baseWeightsVersion + 1, null))
        notifyParent(tx, p, progress = true)
        return Outcome.PUBLISHED
    }

    private fun notifyParent(tx: OpTx, p: FsrsParamsPublishPayload, progress: Boolean) {
        if (progress) {
            tx.dsl.execute(
                "update app.job set progress = greatest(progress, 0.95), progress_message = 'publishing' where id = ? and status = 'running'",
                p.parentJobId,
            )
        }
        tx.dsl.execute("select pg_notify('kotobaya_events', ?)", """{"type":"job.updated","data":{"id":"${p.parentJobId}"}}""")
    }

    private fun endInTx(tx: OpTx, id: UUID, token: String, status: String, result: ObjectNode?) {
        val n = tx.dsl.execute(
            "update app.job set status = ?, result = ?::jsonb, progress = case when ? = 'succeeded' then 1 else progress end, " +
                "finished_at = now(), locked_by = null, error = null where id = ? and status = 'running' and locked_by = ?",
            status, result?.let { json.writeValueAsString(it) }, status, id, token,
        )
        if (n != 1) throw LostClaim()
    }

    /** Ends the child outside the publish transaction (failure, early cancel); guarded by the claim. */
    private fun end(id: UUID, token: String, status: String, error: String? = null) {
        db.txBlocking { dsl ->
            dsl.execute(
                "update app.job set status = ?, error = ?, finished_at = now(), locked_by = null where id = ? and status = 'running' and locked_by = ?",
                status, error?.take(4000), id, token,
            )
        }
    }

    private class Beat(private val thread: Thread) {
        fun stop() {
            thread.interrupt()
            thread.join(5_000)
        }
    }

    private fun startHeartbeat(id: UUID, token: String): Beat {
        val t = Thread {
            try {
                while (true) {
                    Thread.sleep(heartbeatMs)
                    runCatching {
                        db.txBlocking { it.execute("update app.job set heartbeat_at = now() where id = ? and status = 'running' and locked_by = ?", id, token) }
                    }.onFailure { log.warn("fsrs params publish heartbeat failed", it) }
                }
            } catch (_: InterruptedException) {
                // stopped
            }
        }
        t.isDaemon = true
        t.name = "fsrs-params-publish-heartbeat"
        t.start()
        return Beat(t)
    }

    companion object {
        const val KIND = "fsrs-params-publish"
        private const val PARENT_KIND = "fsrs-optimize"
    }
}
