package kotobaya.android.shell.api.mining

import kotobaya.android.dict.DictLookup
import kotobaya.android.media.SpeechResolveQueue
import kotobaya.android.media.TtsAudioTable
import kotobaya.android.media.WordAudios
import kotobaya.android.review.CardRows
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.actions.UUID_RE
import kotobaya.android.shell.api.actions.escapePointer
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.stringValue
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.get
import kotobaya.android.sync.NetworkMonitor
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.render.WordAudio
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable

private val VOICE_RE = Regex("^[0-9]{1,9}$")
private const val MAX_ITEMS = 200
private const val MAX_URGENT_ITEMS = 20
private const val RETRY_ONLINE_MS = 2_000L
private const val RETRY_OFFLINE_MS = 60_000L

private sealed interface SpeechItem {
    data class Text(val text: String, val voice: String?) : SpeechItem

    data class Note(val id: String) : SpeechItem
}

/** The server's validation of `SpeechResolveRequest` (same pointers): items 1..200, exactly one of `text` / `note_id`. */
private fun parseRequest(call: LocalCall): Pair<List<SpeechItem>, Boolean> {
    call.query()
    val body = call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, setOf("items", "urgent"))
    val urgent = when (val u = body.members["urgent"]) {
        null -> false
        is JsonBool -> u.value
        else -> false.also { e.add("/urgent", "invalid_type", "Must be a boolean.") }
    }
    val raw = body.members["items"]
    val list = (raw as? JsonArray)?.items
    val items = ArrayList<SpeechItem>()
    when {
        raw == null -> e.add("/items", "required", "items is required.")
        list == null -> e.add("/items", "invalid_type", "Must be an array.")
        list.isEmpty() || list.size > MAX_ITEMS -> e.add("/items", "invalid_value", "items holds 1 to $MAX_ITEMS entries.")
        else -> list.forEachIndexed { i, node -> item(node, "/items/$i", e)?.let(items::add) }
    }
    if (urgent && list != null && list.size > MAX_URGENT_ITEMS) e.add("/urgent", "invalid_value", "urgent is for at most $MAX_URGENT_ITEMS items.")
    e.throwIfAny()
    return items to urgent
}

private fun item(node: JsonValue, at: String, e: FieldErrors): SpeechItem? {
    val o = node as? JsonObject ?: return null.also { e.add(at, "invalid_type", "Must be an object.") }
    for (k in o.members.keys) if (k !in setOf("text", "voice", "note_id")) e.add("$at/${escapePointer(k)}", "unknown_field", "Unknown property $k.")
    val text = o.members["text"]
    val note = o.members["note_id"]
    val voice = o.members["voice"]?.takeIf { it != JsonNull }
    if ((text == null) == (note == null)) {
        e.add(at, "invalid_value", "Give exactly one of text and note_id.")
        return null
    }
    if (note != null) {
        if (voice != null) e.add(at, "invalid_value", "voice is not allowed with note_id.")
        val id = (note as? JsonString)?.value
        if (id == null || !UUID_RE.matches(id)) e.add("$at/note_id", "invalid_value", "Must be a lowercase UUID.")
        return id?.takeIf { UUID_RE.matches(it) && voice == null }?.let { SpeechItem.Note(it) }
    }
    val t = e.stringValue(checkNotNull(text), "$at/text", 1, 500)
    val v = voice?.let {
        val s = (it as? JsonString)?.value
        if (s == null || !VOICE_RE.matches(s)) e.add("$at/voice", "invalid_value", "A VOICEVOX style id (digits).").let { null } else s
    }
    return t?.let { SpeechItem.Text(it, v) }
}

private fun resolution(
    status: String,
    source: String?,
    text: String?,
    voice: String?,
    hash: String? = null,
    durationMs: Long? = null,
    retryMs: Long? = null,
): JsonObject = jsonObj(
    "status" to JsonString(status),
    "source" to (source?.let(::JsonString) ?: JsonNull),
    "text" to (text?.let(::JsonString) ?: JsonNull),
    "voice" to (voice?.let(::JsonString) ?: JsonNull),
    "blob_hash" to (hash?.let(::JsonString) ?: JsonNull),
    "duration_ms" to (durationMs?.let { JsonNumber(it.toDouble()) } ?: JsonNull),
    "retry_after_ms" to (retryMs?.let { JsonNumber(it.toDouble()) } ?: JsonNull),
)

/**
 * `resolveSpeech` (SHELL.md 4.3, C-31): answered on the device from the table `tts_audio`, never touching the network.
 * A pair with a row is `ready`; without one it is `pending` (`retry_after_ms` 2000 online, 60000 offline) and handed to
 * the native [SpeechResolveQueue]; a pair the server reported `failed` less than 600 s ago is `failed`. A `note_id` is
 * decided by core `WordAudioResolver` over the replica and the L-7 lookup (steps 1-2): the note's own sound is `ready`
 * with its hash, a note that is unknown, deleted, merged away, not `vocab` or without speakable text is `none`.
 */
internal suspend fun resolveSpeech(call: LocalCall): ShellResponse {
    val (items, urgent) = parseRequest(call)
    val store = call.services.get<SqlDelightDeviceStore>()
    val lookup = call.services.get<DictLookup>()
    val table = runCatching { call.services.get<TtsAudioTable>() }.getOrNull()
    val queue = runCatching { call.services.get<SpeechResolveQueue>() }.getOrDefault(SpeechResolveQueue.None)
    val online = runCatching { call.services.get<NetworkMonitor>().isOnline() }.getOrDefault(false)

    fun pair(voice: String, text: String): JsonObject {
        table?.get(voice, text)?.let { return resolution("ready", "tts", text, voice, it.blobHash, it.durationMs) }
        queue.failedRemainingMs(voice, text)?.let { return resolution("failed", "tts", text, voice, retryMs = it.coerceIn(1L, 600_000L)) }
        queue.request(voice, text, urgent)
        return resolution("pending", "tts", text, voice, retryMs = if (online) RETRY_ONLINE_MS else RETRY_OFFLINE_MS)
    }

    val out = store.read { tx ->
        val t = tx.learning
        val defaultVoice = WordAudios.voice(t)
        items.map { item ->
            when (item) {
                is SpeechItem.Text -> pair(item.voice ?: defaultVoice, item.text)
                is SpeechItem.Note -> {
                    val note = CardRows.get(t, LearningTable.NOTE, item.id)?.takeIf(CardRows::live)
                    val typeId = note?.let { CardRows.str(it["note_type_id"]) }
                    val info = note?.let {
                        typeId?.let { id -> CardRows.get(t, LearningTable.NOTE_TYPE, id)?.let(CardRows::noteTypeInfo) }
                            ?: BuiltInNoteTypes.ALL.firstOrNull { b -> b.info.baseType.wire == CardRows.str(it["type"]) }?.info
                    }
                    val seq = (note?.get("entry_seq") as? JsonNumber)?.value?.toLong()
                    val entry = seq?.let { lookup.entry(it) }
                    val audio = if (note == null || info == null) null else WordAudios.resolve(info, CardRows.fieldsOf(note), entry?.let(WordAudios::dictionary))
                    when (audio) {
                        null -> resolution("none", null, null, null)
                        is WordAudio.Note -> resolution("ready", "note", null, null, audio.blobHash)
                        is WordAudio.Tts -> pair(defaultVoice, audio.text)
                    }
                }
            }
        }
    }
    return ShellResponse.json(jsonObj("items" to JsonArray(out)))
}
