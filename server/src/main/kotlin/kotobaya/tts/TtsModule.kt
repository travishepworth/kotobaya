package kotobaya.tts

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.jobs.JobRepo
import kotobaya.notes.Errors
import kotobaya.notes.callerTokenId
import kotobaya.notes.jsonBody
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.json
import kotobaya.server.respondJson
import org.jooq.DSLContext
import java.security.MessageDigest

/** VOICEVOX speaker used when neither the request nor the setting `tts.voice` names one. */
const val DEFAULT_VOICE = "3"

/** `tts:<sha256 hex of voice + NUL + text>` (contract: `synthesizeSpeech`). The worker's cache lookup uses the same key. */
fun ttsDedupeKey(voice: String, text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest((voice + "\u0000" + text).toByteArray(Charsets.UTF_8))
    return "tts:" + digest.joinToString("") { "%02x".format(it) }
}

/**
 * `synthesizeSpeech`: enqueues a `tts` job and emits no ops (it changes no note, so CLAUDE.md rule 8 is not touched).
 * The resolved voice is written into the job payload so the worker never needs the settings table.
 */
class TtsModule(private val db: Database) {
    val handlers: Map<String, OperationHandler> = mapOf("synthesizeSpeech" to { synthesize(this) })

    private suspend fun synthesize(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("text", "voice"))
        val text = e.string(body, "text", 1, 500, required = true)
        val voice = e.string(body, "voice", 1, 100)
        e.throwIfAny()
        val tokenId = call.callerTokenId()
        val (status, job) = db.tx { dsl ->
            val resolved = voice ?: setting(dsl) ?: DEFAULT_VOICE
            val t = requireNotNull(text)
            val payload = json.createObjectNode().put("text", t).put("voice", resolved)
            val r = JobRepo.enqueue(dsl, "tts", payload, dedupeKey = ttsDedupeKey(resolved, t), createdByTokenId = tokenId)
            (if (r.created) HttpStatusCode.Accepted else HttpStatusCode.OK) to r.job.toApi()
        }
        call.respondJson(job, status)
    }

    private fun setting(dsl: DSLContext): String? =
        dsl.fetchOptional("select value #>> '{}' from app.settings where key = 'tts.voice' and jsonb_typeof(value) = 'string'")
            .map { it.get(0, String::class.java) }.orElse(null)?.takeIf { it.isNotEmpty() }
}
