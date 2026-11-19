package kotobaya.android.media

import java.io.IOException
import kotobaya.android.queue.EntryMaterializer
import kotobaya.android.queue.EntryPlan
import kotobaya.android.queue.EntryRef
import kotobaya.contracts.queue.PendingEntry
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.DefaultCanonicalJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `POST /tts/resolve` (`resolveSpeech`) with the device token: the prefetch pass asks for its `(voice, text)` pairs,
 * never urgent (SCHEMA.md 6.6 "Device" rule 4). Any failure (no server, offline, non-2xx, a malformed answer) is null;
 * the pass is simply repeated by the next trigger. A status other than `ready` keeps nothing.
 */
class HttpSpeechResolver(
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val http: OkHttpClient,
) : SpeechResolver {
    override fun resolve(pairs: List<WantedAudio.Pair>): List<SpeechAnswer>? {
        val base = baseUrl()?.trimEnd('/') ?: return null
        val tok = token() ?: return null
        val items = pairs.map { p -> JsonObject(linkedMapOf("text" to JsonString(p.text), "voice" to JsonString(p.voice))) }
        val body = DefaultCanonicalJson.encode(JsonObject(linkedMapOf("items" to JsonArray(items))))
        val req = try {
            Request.Builder().url("$base/tts/resolve").header("Authorization", "Bearer $tok").header("Accept", "application/json")
                .post(body.toRequestBody("application/json".toMediaType())).build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                parse(resp.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        /** The `items` of a `SpeechResolveResult`; null when the body is not one. Unknown members are ignored. */
        fun parse(text: String): List<SpeechAnswer>? {
            val items = ((DefaultCanonicalJson.decode(text).value as? JsonObject)?.members?.get("items") as? JsonArray)?.items ?: return null
            return items.map { v ->
                val m = (v as? JsonObject)?.members ?: return null
                SpeechAnswer(
                    status = (m["status"] as? JsonString)?.value ?: "failed",
                    blobHash = (m["blob_hash"] as? JsonString)?.value,
                    durationMs = (m["duration_ms"] as? JsonNumber)?.value?.toLong(),
                )
            }
        }
    }
}

/**
 * [EntryMaterializer] that plans but never creates: the prefetch pass reads the queue build at `now` (the entries it
 * would draw stay in `toMaterialize`) without minting notes.
 */
class ReadOnlyMaterializer(private val notes: EntryMaterializer) : EntryMaterializer {
    override fun ready(): Boolean = notes.ready()

    override fun plan(entries: List<EntryRef>, burySiblings: Boolean): Map<EntryRef, EntryPlan> = notes.plan(entries, burySiblings)

    override fun kanjiKinds(literals: Collection<String>): Map<String, List<String>> = notes.kanjiKinds(literals)

    override fun materialize(kanji: List<String>, entries: List<PendingEntry>): Set<String> = emptySet()
}
