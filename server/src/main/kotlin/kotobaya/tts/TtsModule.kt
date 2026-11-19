package kotobaya.tts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.render.WordAudio
import kotobaya.contracts.render.WordAudioRules
import kotobaya.jobs.JobRepo
import kotobaya.notes.Errors
import kotobaya.notes.NoteViews
import kotobaya.notes.callerTokenId
import kotobaya.notes.jsonBody
import kotobaya.notes.toNoteTypeInfo
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.json
import kotobaya.server.respondJson
import org.jooq.DSLContext
import java.security.MessageDigest
import java.util.UUID

/** VOICEVOX speaker used when neither the request nor a valid setting `tts.voice` names one (core `WordAudioRules`). */
const val DEFAULT_VOICE = WordAudioRules.DEFAULT_VOICE

/** `tts:<sha256 hex of voice + NUL + text>` (contract: `synthesizeSpeech`). The worker's cache lookup uses the same key. */
fun ttsDedupeKey(voice: String, text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest((voice + "\u0000" + text).toByteArray(Charsets.UTF_8))
    return "tts:" + digest.joinToString("") { "%02x".format(it) }
}

private val VOICE_RE = Regex(WordAudioRules.VOICE_PATTERN)
private const val MAX_ITEMS = 200
private const val MAX_URGENT_ITEMS = 20
private const val RETRY_PENDING_MS = 1_000L
private const val RETRY_HELD_MS = 60_000L

/**
 * `synthesizeSpeech` and `resolveSpeech` (ADR-047, C-29): both enqueue `tts` jobs and emit no ops (they change no note,
 * so CLAUDE.md rule 8 is not touched). The resolved voice is written into the job payload so the worker never needs the
 * settings table.
 */
class TtsModule(private val db: Database) {
    val handlers: Map<String, OperationHandler> = mapOf(
        "synthesizeSpeech" to { synthesize(this) },
        "resolveSpeech" to { resolve(this) },
    )

    private suspend fun synthesize(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("text", "voice"))
        val text = e.string(body, "text", 1, 500, required = true)
        val voice = e.string(body, "voice", 1, 100)
        e.throwIfAny()
        val tokenId = call.callerTokenId()
        val (status, job) = db.tx { dsl ->
            val resolved = voice ?: WordAudioStore.effectiveVoice(dsl)
            val t = requireNotNull(text)
            val payload = json.createObjectNode().put("text", t).put("voice", resolved)
            val r = JobRepo.enqueue(dsl, "tts", payload, dedupeKey = ttsDedupeKey(resolved, t), createdByTokenId = tokenId)
            (if (r.created) HttpStatusCode.Accepted else HttpStatusCode.OK) to r.job.toApi()
        }
        call.respondJson(job, status)
    }

    /** One request item: a pair (voice null = effective voice) or a note. */
    private sealed interface Item {
        data class Text(val text: String, val voice: String?) : Item
        data class Note(val id: String) : Item
    }

    private suspend fun resolve(call: ApplicationCall) {
        val body = call.jsonBody()
        val items = parse(body)
        val urgent = body.get("urgent")?.asBoolean() ?: false
        val tokenId = call.callerTokenId()
        val out = db.tx { dsl -> answer(dsl, items, urgent, tokenId) }
        call.respondJson(mapOf("items" to out))
    }

    private fun parse(body: ObjectNode): List<Item> {
        val e = Errors()
        e.unknownKeys(body, setOf("items", "urgent"))
        val urgentNode = body.get("urgent")
        if (urgentNode != null && !urgentNode.isBoolean) e.add("/urgent", "invalid_type", "Must be a boolean.")
        val arr = body.get("items")
        val items = ArrayList<Item>()
        when {
            arr == null -> e.add("/items", "required", "items is required.")
            !arr.isArray -> e.add("/items", "invalid_type", "Must be an array.")
            arr.size() < 1 || arr.size() > MAX_ITEMS -> e.add("/items", "invalid_value", "1 to $MAX_ITEMS items.")
            else -> arr.forEachIndexed { i, n -> parseItem(e, n, "/items/$i")?.let(items::add) }
        }
        if (urgentNode?.isBoolean == true && urgentNode.asBoolean() && arr?.isArray == true && arr.size() > MAX_URGENT_ITEMS) {
            e.add("/urgent", "invalid_value", "urgent is allowed with at most $MAX_URGENT_ITEMS items.")
        }
        e.throwIfAny()
        return items
    }

    private fun parseItem(e: Errors, n: JsonNode, at: String): Item? {
        if (n !is ObjectNode) {
            e.add(at, "invalid_type", "Must be an object.")
            return null
        }
        val unknown = n.fieldNames().asSequence().filter { it !in setOf("text", "voice", "note_id") }.toList()
        if (unknown.isNotEmpty()) {
            e.add("$at/${unknown.first()}", "unknown_field", "Unknown property ${unknown.first()}.")
            return null
        }
        val hasText = n.has("text")
        val hasNote = n.has("note_id")
        val voiceNode = n.get("voice")
        if (hasText == hasNote) {
            e.add(at, "invalid_value", "Exactly one of text or note_id.")
            return null
        }
        if (hasNote) {
            if (voiceNode != null) {
                e.add(at, "invalid_value", "voice is not allowed with note_id.")
                return null
            }
            return e.uuidValue(n.get("note_id"), "$at/note_id")?.let { Item.Note(it) }
        }
        val text = e.stringValue(n.get("text"), "$at/text", 1, 500) ?: return null
        val voice = when {
            voiceNode == null || voiceNode.isNull -> null
            voiceNode.isTextual && VOICE_RE.matches(voiceNode.asText()) -> voiceNode.asText()
            else -> {
                e.add("$at/voice", "invalid_value", "A VOICEVOX style id in decimal digits (1 to 9).")
                return null
            }
        }
        return Item.Text(text, voice)
    }

    /** What an item resolves to before the lookups: a pair, a note's own sound, or nothing. */
    private sealed interface Target {
        data class Pair(val pair: SpeechPair) : Target
        data class NoteSound(val hash: String) : Target
        data object None : Target
    }

    private fun answer(dsl: DSLContext, items: List<Item>, urgent: Boolean, tokenId: UUID?): List<Map<String, Any?>> {
        val voice = WordAudioStore.effectiveVoice(dsl)
        val notes = loadNotes(dsl, items.filterIsInstance<Item.Note>().map { it.id }, voice)
        val targets = items.map { item ->
            when (item) {
                is Item.Text -> Target.Pair(SpeechPair(item.voice ?: voice, item.text))
                is Item.Note -> notes[item.id] ?: Target.None
            }
        }
        val pairs = targets.filterIsInstance<Target.Pair>().map { it.pair }.distinct()
        val generated = WordAudioStore.generated(dsl, pairs)
        val missing = pairs.filter { it !in generated }
        val state = WordAudioStore.jobState(dsl, missing)
        val toEnqueue = missing.filter { it !in state.backoffLeftMs }
        val limit = if (urgent) WordAudioStore.URGENT_LIMIT else WordAudioStore.BACKLOG_LIMIT
        val outcome = WordAudioStore.enqueue(dsl, toEnqueue, urgent, limit, tokenId)
        val blobs = WordAudioStore.blobsPresent(dsl, targets.filterIsInstance<Target.NoteSound>().map { it.hash })
        return targets.map { t ->
            when (t) {
                Target.None -> resolution("none", null, null, null, null, null, null)
                is Target.NoteSound ->
                    if (t.hash in blobs) {
                        resolution("ready", "note", null, null, t.hash, null, null)
                    } else {
                        resolution("failed", "note", null, null, null, null, WordAudioStore.BACKOFF_MS)
                    }
                is Target.Pair -> {
                    val p = t.pair
                    val g = generated[p]
                    val left = state.backoffLeftMs[p]
                    when {
                        g != null -> resolution("ready", "tts", p.text, p.voice, g.blobHash, g.durationMs, null)
                        left != null -> resolution("failed", "tts", p.text, p.voice, null, null, left.coerceIn(1, WordAudioStore.BACKOFF_MS))
                        outcome[p] == EnqueueOutcome.HELD -> resolution("pending", "tts", p.text, p.voice, null, null, RETRY_HELD_MS)
                        else -> resolution("pending", "tts", p.text, p.voice, null, null, RETRY_PENDING_MS)
                    }
                }
            }
        }
    }

    private fun resolution(
        status: String, source: String?, text: String?, voice: String?, blobHash: String?, durationMs: Int?, retryAfterMs: Long?,
    ): Map<String, Any?> = linkedMapOf(
        "status" to status,
        "source" to source,
        "text" to text,
        "voice" to voice,
        "blob_hash" to blobHash,
        "duration_ms" to durationMs,
        "retry_after_ms" to retryAfterMs,
    )

    /** The word audio of each live note of [ids] (unknown, deleted and merged notes are absent → `none`). */
    private fun loadNotes(dsl: DSLContext, ids: List<String>, voice: String): Map<String, Target> {
        if (ids.isEmpty()) return emptyMap()
        val rows = dsl.fetch(
            "select n.id::text, n.note_type_id::text, n.type, n.fields::text, e.headword, e.headword_reading " +
                "from app.note n left join ref.dict_entry e on e.seq = n.entry_seq " +
                "where n.id = any(array(select unnest(?::uuid[]))) and n.deleted_at is null",
            ids.toSet().toTypedArray(),
        )
        val views = NoteViews(dsl)
        val types = HashMap<String, kotobaya.contracts.cards.NoteTypeInfo?>()
        return rows.associate { r ->
            val typeId = r.get(1, String::class.java)
            val info = types.getOrPut(typeId) { views.noteType(typeId)?.toNoteTypeInfo() }
            val headword = r.get(4, String::class.java)
            val dict = headword?.let { WordAudioStore.dictionaryData(it, r.get(5, String::class.java)) }
            val audio = WordAudioStore.resolve(info, r.get(2, String::class.java), json.readTree(r.get(3, String::class.java)), dict)
            r.get(0, String::class.java) to when (audio) {
                null -> Target.None
                is WordAudio.Note -> Target.NoteSound(audio.blobHash)
                is WordAudio.Tts -> Target.Pair(SpeechPair(voice, audio.text))
            }
        }
    }
}
