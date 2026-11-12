package kotobaya.settings

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.sync.OpType
import kotobaya.jobs.JobRepo
import kotobaya.notes.Errors
import kotobaya.notes.Reply
import kotobaya.notes.callerTokenId
import kotobaya.notes.gone
import kotobaya.notes.jsonBody
import kotobaya.notes.pathId
import kotobaya.notes.payload
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import org.jooq.DSLContext

private const val DEFAULT_PRESET = "00000000-0000-7000-8000-000000000010"
private val PRESET_FIELDS = setOf(
    "name", "desired_retention", "maximum_interval_days", "learning_steps_s", "relearning_steps_s", "fuzz", "easy_days",
)

private fun ts(col: String) = "to_char($col at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')"

private val PRESET_JSON = "select jsonb_build_object(" +
    "'id', p.id, 'name', p.name, 'desired_retention', p.desired_retention, 'maximum_interval_days', p.maximum_interval_days, " +
    "'learning_steps_s', to_jsonb(p.learning_steps_s), 'relearning_steps_s', to_jsonb(p.relearning_steps_s), " +
    "'fuzz', p.fuzz, 'easy_days', to_jsonb(p.easy_days), 'fsrs_version', p.fsrs_version, 'weights', to_jsonb(p.weights), " +
    "'weights_version', p.weights_version, 'optimized_at', ${ts("p.optimized_at")}, 'optimizer_stats', p.optimizer_stats, " +
    "'created_at', ${ts("p.created_at")}, 'updated_at', ${ts("p.updated_at")}, 'deleted_at', ${ts("p.deleted_at")})::text " +
    "from app.fsrs_params p"

private fun preset(dsl: DSLContext, id: String): ObjectNode? =
    dsl.fetchOptional("$PRESET_JSON where p.id = ?::uuid", id).map { json.readTree(it.get(0, String::class.java)) as ObjectNode }.orElse(null)

/** Validates the preset options present in [o] (API bounds, narrower than the op schemas); returns the valid ones. */
private fun Errors.options(o: ObjectNode): ObjectNode {
    val out = json.createObjectNode()
    string(o, "name", 1, 100)?.let { out.put("name", it) }
    o.get("desired_retention")?.let { v ->
        if (!v.isNumber) {
            add("/desired_retention", "invalid_type", "Must be a number.")
        } else if (v.doubleValue() < 0.7 || v.doubleValue() > 0.99) {
            add("/desired_retention", "out_of_range", "Between 0.7 and 0.99.")
        } else {
            out.set<JsonNode>("desired_retention", v)
        }
    }
    o.get("maximum_interval_days")?.let { v ->
        if (!v.isIntegralNumber || !v.canConvertToLong() || v.asLong() !in 1..36500) {
            add("/maximum_interval_days", "out_of_range", "An integer between 1 and 36500.")
        } else {
            out.set<JsonNode>("maximum_interval_days", v)
        }
    }
    for (k in listOf("learning_steps_s", "relearning_steps_s")) steps(o.get(k), "/$k")?.let { out.set<JsonNode>(k, it) }
    o.get("fuzz")?.let { v ->
        if (!v.isBoolean) add("/fuzz", "invalid_type", "Must be a boolean.") else out.set<JsonNode>("fuzz", v)
    }
    o.get("easy_days")?.let { v ->
        val ok = v is ArrayNode && v.size() == 7 && v.all { it.isNumber && it.doubleValue() in 0.0..1.0 }
        if (!ok) add("/easy_days", "invalid_value", "Seven numbers between 0 and 1, Monday first.") else out.set<JsonNode>("easy_days", v)
    }
    return out
}

private fun Errors.steps(v: JsonNode?, at: String): JsonNode? {
    if (v == null) return null
    val ok = v is ArrayNode && v.size() <= 10 && v.all { it.isIntegralNumber && it.canConvertToLong() && it.asLong() in 1..864000 }
    if (!ok) {
        add(at, "invalid_value", "At most 10 integer steps of 1 to 864000 seconds.")
        return null
    }
    return v
}

/** `listPresets`, `createPreset`, `getPreset`, `updatePreset`, `deletePreset`, `optimizePreset`. */
internal class PresetApi(private val db: Database, private val opApply: OpApply) {
    suspend fun list(call: ApplicationCall) {
        val items = db.tx { dsl ->
            dsl.fetch("$PRESET_JSON where p.deleted_at is null order by p.created_at, p.id").map { json.readTree(it.get(0, String::class.java)) }
        }
        call.respondJson(mapOf("items" to items))
    }

    suspend fun get(call: ApplicationCall) {
        val id = call.pathId("preset_id")
        val row = db.tx { preset(it, id) } ?: throw Problems.notFound("No such preset.")
        if (!row["deleted_at"].isNull) throw gone("preset", row["deleted_at"].asText())
        call.respondJson(row)
    }

    suspend fun create(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("id") + PRESET_FIELDS)
        val id = e.uuid(body, "id")
        if (body.get("name") == null) e.add("/name", "required", "name is required.")
        val opts = e.options(body)
        e.throwIfAny()

        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val newId = id ?: Uuid7.generate().toString()
            preset(tx.dsl, newId)?.let { return@rest Reply(HttpStatusCode.OK, it) }
            val p = json.createObjectNode().put("id", newId)
            p.setAll<ObjectNode>(opts)
            tx.emit(OpType.FSRS_PARAMS_CREATED, payload(p))
            Reply(HttpStatusCode.Created, preset(tx.dsl, newId) ?: error("preset $newId missing after fsrs_params.created"), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun update(call: ApplicationCall) {
        val id = call.pathId("preset_id")
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, PRESET_FIELDS)
        if (body.isEmpty) e.add("", "required", "At least one property is required.")
        val opts = e.options(body)
        e.throwIfAny()

        val reply = opApply.rest(call.callerTokenId(), { it.removePrefix("/set") }) { tx ->
            val row = preset(tx.dsl, id) ?: throw Problems.notFound("No such preset.")
            if (!row["deleted_at"].isNull) throw gone("preset", row["deleted_at"].asText())
            val p = json.createObjectNode().put("id", id)
            p.set<JsonNode>("set", opts)
            tx.emit(OpType.FSRS_PARAMS_UPDATED, payload(p))
            Reply(HttpStatusCode.OK, preset(tx.dsl, id) ?: error("preset $id vanished"), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun delete(call: ApplicationCall) {
        val id = call.pathId("preset_id")
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val row = preset(tx.dsl, id) ?: throw Problems.notFound("No such preset.")
            if (id == DEFAULT_PRESET) {
                throw ApiException(HttpStatusCode.Conflict, "builtin_immutable", "Built-in cannot be changed this way", "The Default preset cannot be deleted.")
            }
            if (!row["deleted_at"].isNull) return@rest Reply(HttpStatusCode.NoContent, null)
            tx.emit(OpType.FSRS_PARAMS_DELETED, payload(json.createObjectNode().put("id", id)))
            Reply(HttpStatusCode.NoContent, null, tx.lastSeq)
        }
        call.send(reply)
    }

    /** Enqueues the optimizer job only; the worker's result is published as `fsrs_params.published` elsewhere. */
    suspend fun optimize(call: ApplicationCall) {
        val id = call.pathId("preset_id")
        val (status, job) = db.tx { dsl ->
            val row = preset(dsl, id) ?: throw Problems.notFound("No such preset.")
            if (!row["deleted_at"].isNull) throw gone("preset", row["deleted_at"].asText())
            val r = JobRepo.enqueue(
                dsl, "fsrs-optimize", json.createObjectNode().put("preset_id", id),
                dedupeKey = "fsrs-optimize:$id", createdByTokenId = call.callerTokenId(),
            )
            (if (r.created) HttpStatusCode.Accepted else HttpStatusCode.OK) to r.job.toApi()
        }
        call.respondJson(job, status)
    }
}
