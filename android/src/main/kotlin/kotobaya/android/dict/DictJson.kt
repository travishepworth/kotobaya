package kotobaya.android.dict

import org.json.JSONArray
import org.json.JSONObject

/** The API's `DictEntry` / `Kanji` bodies (openapi.yaml) as the sidecar cache stores and reads them. */
internal object DictJson {
    /** Per-user overlay keys of `DictEntry` / `Kanji`: never cached (the replica answers them). */
    private val OVERLAY = listOf("status", "note_id", "deck_ids")

    /** [body] without the overlay, as compact JSON. Throws on a body that is not an object. */
    fun stripOverlay(body: String): String = JSONObject(body).also { o -> OVERLAY.forEach { o.remove(it) } }.toString()

    fun entry(body: String): DictEntryDetail {
        val o = JSONObject(body)
        return DictEntryDetail(
            seq = o.getLong("seq"),
            language = o.optString("language", "ja"),
            headword = o.getString("headword"),
            headwordReading = o.getString("headword_reading"),
            isCommon = o.optBoolean("is_common"),
            kanjiForms = o.optJSONArray("kanji_forms") ?: JSONArray(),
            readings = o.optJSONArray("readings") ?: JSONArray(),
            senses = o.optJSONArray("senses") ?: JSONArray(),
            pos = strings(o.optJSONArray("pos")),
            misc = strings(o.optJSONArray("misc")),
            freqRank = intOrNull(o, "freq_rank"),
            retired = o.optBoolean("retired"),
            freqs = objects(o.optJSONArray("freqs")).map { FreqRank(it.getString("list_id"), it.getInt("rank")) },
            pitch = objects(o.optJSONArray("pitch")).map { p ->
                val a = p.optJSONArray("positions") ?: JSONArray()
                PitchInfo(p.getString("reading"), (0 until a.length()).map { a.getInt(it) })
            },
            kanji = strings(o.optJSONArray("kanji")),
        )
    }

    fun kanji(body: String): KanjiDetail {
        val o = JSONObject(body)
        return KanjiDetail(
            literal = o.getString("literal"),
            onReadings = strings(o.optJSONArray("on_readings")),
            kunReadings = strings(o.optJSONArray("kun_readings")),
            nanori = strings(o.optJSONArray("nanori")),
            meanings = strings(o.optJSONArray("meanings")),
            strokeCount = intOrNull(o, "stroke_count"),
            grade = intOrNull(o, "grade"),
            jlpt = intOrNull(o, "jlpt"),
            freqRank = intOrNull(o, "freq_rank"),
            radical = if (o.isNull("radical")) null else o.optString("radical"),
            components = strings(o.optJSONArray("components")),
        )
    }

    private fun intOrNull(o: JSONObject, key: String): Int? = if (!o.has(key) || o.isNull(key)) null else o.getInt(key)

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
}
