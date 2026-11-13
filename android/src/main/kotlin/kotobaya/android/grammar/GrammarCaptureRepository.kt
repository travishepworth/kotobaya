package kotobaya.android.grammar

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class GrammarPointView(val id: String, val title: String, val pattern: String, val rules: List<JSONObject>)

data class RuleMatch(val text: String, val start: Int, val end: Int)

data class RuleTestView(val scanned: Int, val truncated: Boolean, val matches: List<RuleMatch>)

/**
 * "Saw something weird" server calls (grammar library, rule tester, manual span). Online only:
 * the API handlers emit the sync ops (rule 8). Mirrors the web flow (T508a).
 */
class GrammarCaptureRepository(
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val http: OkHttpClient = ApiClient.defaultHttp(),
) {
    suspend fun search(q: String): List<GrammarPointView> {
        val enc = URLEncoder.encode(q, "UTF-8")
        val items = call("GET", "/grammar?q=$enc&limit=10", null).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { parsePoint(items.getJSONObject(it)) }
    }

    /** Idempotent on [id]: a retry returns the existing point. */
    suspend fun createPoint(id: String, title: String, pattern: String, rule: String) {
        val body = JSONObject().put("id", id).put("title", title).put("pattern", pattern)
            .put("detection_rules", JSONArray().apply { if (rule.isNotBlank()) put(draft(rule)) })
        call("POST", "/grammar", body)
    }

    suspend fun testRule(pointId: String, rule: String): RuleTestView {
        val body = JSONObject().put("rules", JSONArray().put(draft(rule))).put("limit", 20)
        val j = call("POST", "/grammar/$pointId/rules/test", body)
        val ms = j.optJSONArray("matches") ?: JSONArray()
        return RuleTestView(
            j.optInt("scanned"),
            j.optBoolean("truncated"),
            (0 until ms.length()).map {
                val m = ms.getJSONObject(it)
                val sp = m.optJSONArray("spans")?.optJSONObject(0)
                RuleMatch(
                    m.getJSONObject("sentence").getString("text"),
                    sp?.optInt("start_offset", -1) ?: -1,
                    sp?.optInt("end_offset", -1) ?: -1,
                )
            },
        )
    }

    suspend fun putSpan(pointId: String, sentenceId: String, start: Int, end: Int, mediaId: String?) {
        val body = JSONObject().apply { if (mediaId != null) put("media_id", mediaId) }
        call("PUT", "/grammar/$pointId/spans/$sentenceId/$start/$end", body)
    }

    /** Attach mode: appends a draft rule to the chosen point's existing rules (whole-array write). */
    suspend fun appendRule(point: GrammarPointView, rule: String) {
        val all = JSONArray().apply {
            point.rules.forEach { put(it) }
            put(draft(rule))
        }
        call("PATCH", "/grammar/${point.id}", JSONObject().put("detection_rules", all))
    }

    private fun draft(rule: String) = JSONObject().put("rule", rule.trim()).put("draft", true)

    private fun parsePoint(j: JSONObject): GrammarPointView {
        val rs = j.optJSONArray("detection_rules") ?: JSONArray()
        return GrammarPointView(
            j.getString("id"), j.optString("title"), j.optString("pattern"),
            (0 until rs.length()).map { rs.getJSONObject(it) },
        )
    }

    private suspend fun call(method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val base = baseUrl()?.trimEnd('/') ?: throw ApiError.Unreachable("no server configured")
        val req = Request.Builder().url(base + path).header("Accept", "application/json")
            .method(method, body?.toString()?.toRequestBody(JSON))
        token()?.let { req.header("Authorization", "Bearer $it") }
        try {
            http.newCall(req.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiClient.mapError(resp.code, text, null)
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } catch (e: java.io.IOException) {
            throw ApiError.Unreachable(e.javaClass.simpleName)
        } catch (e: org.json.JSONException) {
            throw ApiError.BadBody(e.message ?: "json")
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
