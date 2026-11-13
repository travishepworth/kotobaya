package kotobaya.android.ankiconnect

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** An error AnkiConnect reports as `{"result": null, "error": message}`. */
class AcError(message: String) : Exception(message)

/**
 * AnkiConnect protocol v6: `{action, version, params, key}` -> `{result, error}`. Pure over [AnkiConnectBackend]
 * (HTTP, CORS and origins are [AnkiConnectServer]'s).
 *
 * Actions: `version`, `requestPermission`, `deckNames`, `deckNamesAndIds`, `modelNames`, `modelNamesAndIds`,
 * `modelFieldNames`, `canAddNotes`, `canAddNotesWithErrorDetail`, `addNote`, `storeMediaFile`, `findNotes`,
 * `notesInfo`, `multi`. Anything else: `"unsupported action"` (AnkiConnect's own text).
 *
 * `multi` answers a list of full `{result, error}` replies, one per action.
 * `addNote` `audio`/`picture`/`video` entries with inline base64 `data` are stored like `storeMediaFile`
 * and their `[sound:name]` / `<img src="name">` reference is appended to the listed fields; entries with a `url` are
 * refused (this server never fetches URLs). References to names stored earlier with `storeMediaFile` are rewritten to
 * the stored name.
 */
class AnkiConnectProtocol(
    private val backend: AnkiConnectBackend,
    private val apiKey: () -> String?,
) {
    /** original filename -> stored name, for references written before the note is added. */
    private val storedNames = ConcurrentHashMap<String, String>()

    /** [body] is the raw POST body; the result is the JSON reply text. */
    fun handle(body: String): String {
        val request = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return reply(null, "failed to parse request: not a JSON object").toString()
        }
        return respond(request).toString()
    }

    private fun respond(request: JSONObject): JSONObject = try {
        reply(dispatch(request, topLevel = true), null)
    } catch (e: AcError) {
        reply(null, e.message ?: "error")
    } catch (e: JSONException) {
        reply(null, "bad request: ${e.message}")
    }

    private fun reply(result: Any?, error: String?): JSONObject =
        JSONObject().put("result", result ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)

    private fun dispatch(request: JSONObject, topLevel: Boolean): Any? {
        val action = request.optString("action", "")
        val params = request.optJSONObject("params") ?: JSONObject()
        if (action == "requestPermission") {
            return JSONObject().put("permission", "granted").put("requireApiKey", !apiKey().isNullOrEmpty()).put("version", VERSION)
        }
        val expected = apiKey()
        if (!expected.isNullOrEmpty() && (topLevel && request.optString("key", "") != expected)) {
            throw AcError("valid api key must be provided")
        }
        return when (action) {
            "version" -> VERSION
            "deckNames" -> JSONArray(backend.decks().map { it.name })
            "deckNamesAndIds" -> backend.decks().fold(JSONObject()) { o, d -> o.put(d.name, d.id) }
            "modelNames" -> JSONArray(backend.models().map { it.name })
            "modelNamesAndIds" -> backend.models().fold(JSONObject()) { o, m -> o.put(m.name, m.id) }
            "modelFieldNames" -> {
                val name = params.optString("modelName", "")
                JSONArray((backend.models().firstOrNull { it.name == name } ?: throw AcError("model was not found: $name")).fields)
            }
            "canAddNotes" -> JSONArray(notes(params).map { backend.check(it) is AcAdd.Added })
            "canAddNotesWithErrorDetail" -> JSONArray(
                notes(params).map { n ->
                    when (val c = backend.check(n)) {
                        is AcAdd.Added -> JSONObject().put("canAdd", true)
                        AcAdd.Duplicate -> JSONObject().put("canAdd", false).put("error", DUPLICATE)
                        is AcAdd.Invalid -> JSONObject().put("canAdd", false).put("error", c.message)
                    }
                },
            )
            "addNote" -> addNote(params.optJSONObject("note") ?: throw AcError("missing note"))
            "storeMediaFile" -> storeMediaFile(params)
            "findNotes" -> JSONArray(backend.findNotes(params.optString("query", "")))
            "notesInfo" -> notesInfo(params)
            "multi" -> {
                val actions = params.optJSONArray("actions") ?: throw AcError("missing actions")
                JSONArray().also { out ->
                    for (i in 0 until actions.length()) {
                        val sub = actions.optJSONObject(i) ?: JSONObject()
                        out.put(
                            try {
                                reply(dispatch(sub, topLevel = false), null)
                            } catch (e: AcError) {
                                reply(null, e.message ?: "error")
                            }
                        )
                    }
                }
            }
            else -> throw AcError("unsupported action")
        }
    }

    private fun notes(params: JSONObject): List<AcNote> {
        val arr = params.optJSONArray("notes") ?: throw AcError("missing notes")
        return (0 until arr.length()).map { parseNote(arr.optJSONObject(it) ?: throw AcError("note $it is not an object")) }
    }

    private fun parseNote(o: JSONObject): AcNote {
        val fields = LinkedHashMap<String, String>()
        o.optJSONObject("fields")?.let { f -> f.keys().forEach { k -> fields[k] = f.optString(k, "") } }
        val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty()
        val allow = o.optJSONObject("options")?.optBoolean("allowDuplicate", false) ?: false
        return AcNote(o.optString("deckName", ""), o.optString("modelName", ""), fields, tags, allow)
    }

    private fun addNote(raw: JSONObject): Any? {
        val note = parseNote(raw)
        val fields = LinkedHashMap(note.fields)
        // media attached to the note itself
        for ((kind, wrap) in MEDIA_KINDS) {
            val items = raw.optJSONArray(kind) ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val name = item.optString("filename", "")
                if (name.isEmpty()) throw AcError("$kind has no filename")
                val data = item.optString("data", "")
                if (data.isEmpty()) throw AcError("$kind '$name': only inline base64 data is supported (no url or path)")
                val stored = store(name, decode(data))
                val target = item.optJSONArray("fields")
                for (j in 0 until (target?.length() ?: 0)) {
                    val f = target!!.optString(j)
                    if (f.isNotEmpty()) fields[f] = fields[f].orEmpty() + wrap(stored)
                }
            }
        }
        val rewritten = fields.mapValues { (_, v) -> rewrite(v) }
        return when (val r = backend.addNote(note.copy(fields = rewritten))) {
            is AcAdd.Added -> r.noteId
            AcAdd.Duplicate -> throw AcError(DUPLICATE)
            is AcAdd.Invalid -> throw AcError(r.message)
        }
    }

    private fun rewrite(value: String): String {
        if (storedNames.isEmpty()) return value
        var out = value
        for ((original, stored) in storedNames) {
            if (original == stored) continue
            out = out.replace("[sound:$original]", "[sound:$stored]")
                .replace("src=\"$original\"", "src=\"$stored\"").replace("src='$original'", "src='$stored'")
        }
        return out
    }

    private fun storeMediaFile(params: JSONObject): Any? {
        val name = params.optString("filename", "")
        if (name.isEmpty()) throw AcError("missing filename")
        val data = params.optString("data", "")
        if (data.isEmpty()) throw AcError("only inline base64 data is supported (no url or path)")
        store(name, decode(data))
        return name
    }

    private fun store(name: String, bytes: ByteArray): String {
        if (bytes.size > MAX_MEDIA_BYTES) throw AcError("media file too large")
        val stored = backend.storeMedia(name, bytes)
        storedNames[name] = stored
        return stored
    }

    private fun decode(data: String): ByteArray = try {
        Base64.getMimeDecoder().decode(data)
    } catch (_: IllegalArgumentException) {
        throw AcError("data is not valid base64")
    }

    private fun notesInfo(params: JSONObject): Any? {
        val ids = params.optJSONArray("notes") ?: throw AcError("missing notes")
        val wanted = (0 until ids.length()).map { ids.optLong(it) }
        val byId = backend.notesInfo(wanted).associateBy { it.noteId }
        return JSONArray(
            wanted.map { id ->
                val n = byId[id] ?: return@map JSONObject()
                val fields = JSONObject()
                n.fields.entries.forEachIndexed { i, (k, v) -> fields.put(k, JSONObject().put("value", v).put("order", i)) }
                JSONObject().put("noteId", n.noteId).put("modelName", n.modelName).put("tags", JSONArray(n.tags))
                    .put("fields", fields).put("cards", JSONArray())
            },
        )
    }

    companion object {
        const val VERSION: Int = 6
        const val DUPLICATE: String = "cannot create note because it is a duplicate"
        const val MAX_MEDIA_BYTES: Int = 20 * 1024 * 1024

        private val MEDIA_KINDS: List<Pair<String, (String) -> String>> = listOf(
            "audio" to { n -> "[sound:$n]" },
            "video" to { n -> "[sound:$n]" },
            "picture" to { n -> "<img src=\"$n\">" },
        )
    }
}
