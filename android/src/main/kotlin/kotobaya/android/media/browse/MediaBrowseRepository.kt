package kotobaya.android.media.browse

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import org.json.JSONException
import org.json.JSONObject

/** One row of the local `media` table (arrives by ops, so the list works offline). */
data class MediaRow(val id: String, val title: String, val kind: String, val parseStatus: String)

fun interface MediaListSource {
    fun list(): List<MediaRow>
}

class SqlMediaListSource(private val driver: SqlDriver) : MediaListSource {
    override fun list(): List<MediaRow> = driver.executeQuery(
        null,
        "SELECT id, title, kind, parse_status FROM media WHERE deleted_at IS NULL ORDER BY title",
        { c ->
            val out = mutableListOf<MediaRow>()
            while (c.next().value) out += MediaRow(c.getString(0)!!, c.getString(1)!!, c.getString(2)!!, c.getString(3)!!)
            QueryResult.Value(out)
        },
        0,
    ).value
}

/** A cached response body and when it was fetched (epoch ms). */
data class CachedBody(val body: String, val savedAtMs: Long)

interface BrowseCache {
    fun get(key: String): CachedBody?
    fun put(key: String, body: String, nowMs: Long)
}

/** One file per key under [dir]; first line is the timestamp. Keys are ids, sanitised. */
class FileBrowseCache(dirProvider: Lazy<File>) : BrowseCache {
    constructor(dir: File) : this(lazyOf(dir))

    private val dir: File by dirProvider

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    override fun get(key: String): CachedBody? {
        val f = file(key)
        if (!f.isFile) return null
        val text = f.readText()
        val nl = text.indexOf('\n')
        if (nl < 0) return null
        val ts = text.substring(0, nl).toLongOrNull() ?: return null
        return CachedBody(text.substring(nl + 1), ts)
    }

    override fun put(key: String, body: String, nowMs: Long) {
        dir.mkdirs()
        val tmp = File(dir, "${file(key).name}.tmp")
        tmp.writeText("$nowMs\n$body")
        tmp.renameTo(file(key))
    }
}

sealed interface Loaded<out T> {
    /** [fromCache] true when the server call failed and the last good copy is shown. */
    data class Data<T>(val value: T, val updatedAtMs: Long, val fromCache: Boolean) : Loaded<T>

    /** Offline or unreachable and nothing cached. */
    data object NeedsConnection : Loaded<Nothing>

    /** The server answered with an error and nothing is cached. */
    data class Failed(val error: ApiError) : Loaded<Nothing>
}

/** Coverage and i+1 sentences per media, online with a last-good cache for offline viewing. */
class MediaBrowseRepository(
    private val api: ApiClient,
    private val cache: BrowseCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun coverage(mediaId: String): Loaded<CoverageView> =
        load("coverage-$mediaId", "/media/$mediaId/coverage", ::parseCoverage)

    /** First page of i+1 sentences (`filter=i+1` is sent URL-encoded). */
    suspend fun sentences(mediaId: String): Loaded<List<SentenceView>> =
        load("sentences-$mediaId", "/media/$mediaId/sentences?filter=i%2B1&limit=50", ::parseSentences)

    private suspend fun <T> load(key: String, path: String, parse: (JSONObject) -> T): Loaded<T> = withContext(Dispatchers.IO) { loadOnIo(key, path, parse) }

    private suspend fun <T> loadOnIo(key: String, path: String, parse: (JSONObject) -> T): Loaded<T> {
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
}
