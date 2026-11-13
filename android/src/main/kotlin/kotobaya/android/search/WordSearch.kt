package kotobaya.android.search

import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.dict.DictSearchCursor
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import org.json.JSONException
import java.net.URLEncoder

data class WordHit(val seq: Long, val headword: String, val reading: String, val gloss: String, val status: String)

/** [next] is an opaque cursor for the same source, or null at the end. */
data class WordPage(val items: List<WordHit>, val next: String?)

sealed interface WordSearchOutcome {
    data class Page(val page: WordPage) : WordSearchOutcome
    data class Failed(val offline: Boolean) : WordSearchOutcome
}

/** Dictionary mode: the installed package when there is one, otherwise `GET /dict/search`. */
class WordSearch(
    private val dict: InstalledDictionary,
    private val driver: SqlDriver,
    private val api: ApiClient,
) {
    suspend fun search(q: String, after: String? = null, limit: Int = 50): WordSearchOutcome {
        if (q.isBlank()) return WordSearchOutcome.Page(WordPage(emptyList(), null))
        if (dict.isAvailable()) {
            val local = runCatching { local(q, after, limit) }.getOrNull()
            if (local != null) return WordSearchOutcome.Page(local)
        }
        return remote(q, after, limit)
    }

    private fun local(q: String, after: String?, limit: Int): WordPage {
        val cursor = after?.split(':')?.let { DictSearchCursor(it[0].toInt(), it[1].toInt(), it[2].toLong()) }
        val page = dict.search(q.trim(), limit = limit, after = cursor)
        val status = driver.wordStatuses(page.items.map { it.seq })
        return WordPage(
            page.items.map { WordHit(it.seq, it.headword, it.headwordReading, it.glosses.firstOrNull().orEmpty(), status[it.seq] ?: "new") },
            page.next?.let { "${it.grade}:${it.freq}:${it.seq}" },
        )
    }

    private suspend fun remote(q: String, after: String?, limit: Int): WordSearchOutcome = try {
        var path = "/dict/search?q=${URLEncoder.encode(q.trim(), "UTF-8")}&limit=$limit"
        if (after != null) path += "&cursor=${URLEncoder.encode(after, "UTF-8")}"
        val body = api.get(path).body
        val items = body.optJSONArray("items")
        val hits = (0 until (items?.length() ?: 0)).map { i ->
            val o = items!!.getJSONObject(i)
            WordHit(
                o.getLong("seq"), o.getString("headword"), o.getString("headword_reading"),
                o.optJSONArray("glosses")?.optString(0).orEmpty(), o.optString("status", "new"),
            )
        }
        WordSearchOutcome.Page(WordPage(hits, if (body.isNull("next_cursor")) null else body.optString("next_cursor")))
    } catch (e: ApiError) {
        WordSearchOutcome.Failed(offline = e is ApiError.Offline || e is ApiError.Unreachable)
    } catch (e: JSONException) {
        WordSearchOutcome.Failed(offline = false)
    }
}
