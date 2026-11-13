package kotobaya.android.media.browse

import org.json.JSONArray
import org.json.JSONObject

data class CountsView(val total: Int, val known: Int, val learning: Int, val unknown: Int)

data class SegmentView(val ordinal: Int, val title: String, val uniqueTotal: Int, val uniqueUnknown: Int, val knownPct: Double)

data class CurvePoint(val words: Int, val knownPct: Double)

/** The parts of the `Coverage` response the phone shows. */
data class CoverageView(
    val unique: CountsView,
    val tokens: CountsView,
    val tokenKnownPct: Double,
    val segments: List<SegmentView>,
    val curve: List<CurvePoint>,
)

data class SentenceView(
    val id: String,
    val text: String,
    val origin: String,
    val unknownCount: Int,
    val audioBlobHash: String?,
) {
    val isGenerated: Boolean get() = origin == "generated"
}

private fun JSONObject.counts() = CountsView(optInt("total"), optInt("known"), optInt("learning"), optInt("unknown"))

private fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

fun parseCoverage(j: JSONObject): CoverageView {
    val tokens = j.getJSONObject("tokens")
    return CoverageView(
        unique = j.getJSONObject("unique").counts(),
        tokens = tokens.counts(),
        tokenKnownPct = tokens.optDouble("known_pct", 0.0),
        segments = j.optJSONArray("segments").mapObjects {
            SegmentView(
                it.optInt("ordinal"), it.optString("title"), it.optInt("unique_total"),
                it.optInt("unique_unknown"), it.optDouble("known_pct", 0.0),
            )
        },
        curve = j.optJSONArray("curve").mapObjects { CurvePoint(it.optInt("words"), it.optDouble("known_pct", 0.0)) },
    )
}

fun parseSentences(j: JSONObject): List<SentenceView> = j.optJSONArray("items").mapObjects {
    SentenceView(
        it.getString("id"), it.getString("text"), it.optString("origin", "media"), it.optInt("unknown_count"),
        if (it.isNull("audio_blob_hash")) null else it.getString("audio_blob_hash"),
    )
}
