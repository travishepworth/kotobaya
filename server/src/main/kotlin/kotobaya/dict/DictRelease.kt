package kotobaya.dict

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotobaya.jobs.JobRepo
import kotobaya.notes.Errors
import kotobaya.notes.callerTokenId
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.formatInstant
import kotobaya.server.json
import kotobaya.server.respondJson
import java.time.OffsetDateTime

private val SLUG = Regex("^[a-z0-9][a-z0-9_-]*$")

/** `getDictRelease` and `refreshDictionary` (W-5a). Scopes are enforced by the framework from the spec. */
internal object DictReleaseApi {
    const val KIND = "dict-refresh"

    suspend fun get(db: Database, call: ApplicationCall) {
        val body = db.tx { dsl ->
            val r = dsl.fetchOne(
                "select id, version, built_at, loaded_at, sources::text, artifacts::text " +
                    "from ref.dict_release where is_current",
            ) ?: return@tx null
            val lists = dsl.fetch("select id, title, priority, entry_count from ref.freq_list order by priority").map {
                linkedMapOf(
                    "id" to it.get(0, String::class.java), "title" to it.get(1, String::class.java),
                    "priority" to it.get(2, Int::class.java), "entry_count" to it.get(3, Int::class.java),
                )
            }
            linkedMapOf(
                "id" to r.get(0, Int::class.java),
                "version" to r.get(1, String::class.java),
                "built_at" to formatInstant(r.get(2, OffsetDateTime::class.java).toInstant()),
                "loaded_at" to formatInstant(r.get(3, OffsetDateTime::class.java).toInstant()),
                "sources" to json.readTree(r.get(4, String::class.java)),
                "freq_lists" to lists,
                "artifacts" to json.readTree(r.get(5, String::class.java)),
            )
        } ?: throw Problems.notFound("No dictionary release has been loaded yet.")
        call.respondJson(body)
    }

    suspend fun refresh(db: Database, call: ApplicationCall) {
        val text = call.receiveText()
        val payload: ObjectNode = if (text.isBlank()) {
            json.createObjectNode()
        } else {
            val node = try {
                json.readTree(text)
            } catch (e: JsonProcessingException) {
                throw Problems.invalidRequest("The body is not valid JSON.")
            }
            node as? ObjectNode ?: throw Problems.invalidRequest("The body must be a JSON object.")
        }
        val e = Errors()
        e.unknownKeys(payload, setOf("freq_lists"))
        payload.get("freq_lists")?.let { f ->
            val items = if (f.isArray) f.map { it.takeIf { n -> n.isTextual }?.asText() } else null
            if (items == null || items.any { it == null || !SLUG.matches(it) } || items.toSet().size != items.size) {
                e.add("/freq_lists", "invalid_value", "An array of distinct frequency list slugs.")
            }
        }
        e.throwIfAny()
        val tokenId = call.callerTokenId()
        val (status, job) = db.tx { dsl ->
            val r = JobRepo.enqueue(dsl, KIND, payload, dedupeKey = KIND, createdByTokenId = tokenId)
            (if (r.created) HttpStatusCode.Accepted else HttpStatusCode.OK) to r.job.toApi()
        }
        call.respondJson(job, status)
    }
}
