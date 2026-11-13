package kotobaya.android.struggle

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.media.browse.BrowseCache
import kotobaya.android.media.browse.Loaded
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/** Seam so screens can be driven without a server. Reads fall back to the last good copy; writes need the server. */
interface StruggleSource {
    suspend fun hotWords(): Loaded<List<HotWordView>>
    suspend fun hotKanji(): Loaded<List<HotKanjiView>>
    suspend fun leeches(cursor: String?): Loaded<Pair<List<LeechView>, String?>>
    suspend fun confusables(): Loaded<List<ConfusableView>>
    suspend fun wordConfusables(entrySeq: Long): Loaded<List<ConfusableView>>
    suspend fun sentences(entrySeq: Long): Loaded<List<SentenceItem>>
    suspend fun drill(source: String): Loaded<List<DrillItem>>

    suspend fun setConfusable(c: ConfusableView, status: String): Result<Unit>
    suspend fun saveMnemonic(noteId: String, mnemonic: String): Result<Unit>
    suspend fun makeSentenceCard(s: SentenceItem): Result<Unit>
    suspend fun answerDrill(cardId: String, rating: Int): Result<Unit>
}

class StruggleRepository(
    private val api: ApiClient,
    private val cache: BrowseCache,
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val http: OkHttpClient = ApiClient.defaultHttp(),
    private val now: () -> Long = System::currentTimeMillis,
) : StruggleSource {
    override suspend fun hotWords() = load("struggle-hot", "/words/hot?limit=200", ::parseHotWords)
    override suspend fun hotKanji() = load("struggle-kanji", "/kanji/hot?limit=30", ::parseHotKanji)
    override suspend fun leeches(cursor: String?) = load(
        "struggle-leeches-${cursor ?: "first"}",
        "/words/leeches?limit=50" + (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""),
    ) { parseLeeches(it) to parseNextCursor(it) }

    override suspend fun confusables() = load("struggle-confusables", "/confusables?limit=100", ::parseConfusables)
    override suspend fun wordConfusables(entrySeq: Long) =
        load("struggle-wc-$entrySeq", "/words/$entrySeq/confusables", ::parseConfusables)

    override suspend fun sentences(entrySeq: Long) =
        load("struggle-sent-$entrySeq", "/sentences?entry_seq=$entrySeq&limit=10", ::parseSentenceItems)

    /** Drill sessions are built fresh on the server each time (never cached). Single-word drills do not exist (F-21). */
    override suspend fun drill(source: String): Loaded<List<DrillItem>> = try {
        val body = JSONObject().put("source", source).put("mode", "drill").put("limit", 20)
        Loaded.Data(parseDrill(api.post("/drill/session", body, auth = true).body), now(), fromCache = false)
    } catch (e: ApiError) {
        if (e is ApiError.Offline || e is ApiError.Unreachable) Loaded.NeedsConnection else Loaded.Failed(e)
    } catch (e: JSONException) {
        Loaded.Failed(ApiError.BadBody(e.message ?: "json"))
    }

    override suspend fun setConfusable(c: ConfusableView, status: String) =
        write("PUT", "/confusables/${c.noteAId}/${c.noteBId}/${c.reason}", JSONObject().put("status", status))

    override suspend fun saveMnemonic(noteId: String, mnemonic: String) =
        write("PATCH", "/notes/$noteId", JSONObject().put("mnemonic", mnemonic))

    override suspend fun makeSentenceCard(s: SentenceItem) = write(
        "POST", "/notes",
        JSONObject().put("id", newId()).put("note_type_id", SENTENCE_NOTE_TYPE)
            .put(
                "fields",
                JSONObject().put("sentence", s.text).put("reading", s.reading).put("translation", "")
                    .put("audio", s.audioBlobHash?.let { "[sound:$it]" } ?: ""),
            )
            .put("source_sentence", s.text).put("source_sentence_id", s.id).put("source_media_id", s.mediaId),
    )

    /** Drill answers only log; the schedule is untouched (`mode = drill`). */
    override suspend fun answerDrill(cardId: String, rating: Int) = write(
        "POST", "/review/answer",
        JSONObject().put("id", newId()).put("card_id", cardId).put("rating", rating).put("mode", "drill"),
    )

    private fun newId(): String = UUID.randomUUID().toString()

    private suspend fun <T> load(key: String, path: String, parse: (JSONObject) -> T): Loaded<T> {
        try {
            val body = api.get(path).body
            val value = parse(body)
            val t = now()
            cache.put(key, body.toString(), t)
            return Loaded.Data(value, t, fromCache = false)
        } catch (e: ApiError) {
            val cached = cache.get(key)?.let { c ->
                runCatching { Loaded.Data(parse(JSONObject(c.body)), c.savedAtMs, fromCache = true) }.getOrNull()
            }
            if (cached != null) return cached
            return if (e is ApiError.Offline || e is ApiError.Unreachable) Loaded.NeedsConnection else Loaded.Failed(e)
        } catch (e: JSONException) {
            return Loaded.Failed(ApiError.BadBody(e.message ?: "json"))
        }
    }

    /** Server-side writes: the API handler emits the sync op (rule 8); the phone never writes tables here. */
    private suspend fun write(method: String, path: String, body: JSONObject): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val base = baseUrl()?.trimEnd('/') ?: throw ApiError.Unreachable("no server configured")
            val req = Request.Builder().url(base + path).header("Accept", "application/json")
                .method(method, body.toString().toRequestBody(JSON))
            token()?.let { req.header("Authorization", "Bearer $it") }
            try {
                http.newCall(req.build()).execute().use { resp ->
                    // 409 on note create = the note already exists: nothing to do.
                    if (!resp.isSuccessful && !(resp.code == 409 && method == "POST")) {
                        throw ApiClient.mapError(resp.code, resp.body?.string().orEmpty(), null)
                    }
                }
            } catch (e: java.io.IOException) {
                throw ApiError.Unreachable(e.javaClass.simpleName)
            }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val SENTENCE_NOTE_TYPE = "00000000-0000-7000-8000-000000000105"
    }
}
