package kotobaya.android.struggle

import org.json.JSONArray
import org.json.JSONObject

/** The parts of a `HotWord` the phone shows. */
data class HotWordView(
    val noteId: String,
    val entrySeq: Long?,
    val headword: String,
    val reading: String,
    val glosses: List<String>,
    val score: Double,
    val failures14d: Int,
    val mnemonic: String,
)

data class HotKanjiView(val literal: String, val meanings: List<String>, val score: Double, val wordCount: Int)

data class LeechView(val cardId: String, val noteId: String, val label: String, val lapses: Int, val suspended: Boolean)

data class ConfusableView(
    val noteAId: String,
    val noteBId: String,
    val reason: String,
    val status: String,
    val labelA: String,
    val labelB: String,
)

data class SentenceItem(val id: String, val mediaId: String, val text: String, val reading: String, val audioBlobHash: String?)

/** One card of a drill session: what to show on each side. */
data class DrillItem(val cardId: String, val front: String, val back: String)

private fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

/** Best label of a `NoteSummary`: its word, sentence or kanji. */
fun noteLabel(note: JSONObject?): String {
    if (note == null) return ""
    val f = note.optJSONObject("fields")
    return f?.str("word") ?: f?.str("expression") ?: f?.str("sentence") ?: note.str("kanji_literal") ?: note.optString("id")
}

fun parseHotWords(j: JSONObject): List<HotWordView> = j.optJSONArray("items").mapObjects {
    val entry = if (it.isNull("entry")) null else it.optJSONObject("entry")
    val note = it.optJSONObject("note")
    HotWordView(
        noteId = it.getString("note_id"),
        entrySeq = if (it.isNull("entry_seq")) null else it.getLong("entry_seq"),
        headword = entry?.str("headword") ?: noteLabel(note),
        reading = entry?.str("headword_reading").orEmpty(),
        glosses = entry?.optJSONArray("glosses").strings(),
        score = it.optDouble("score", 0.0),
        failures14d = it.optInt("failures_14d"),
        mnemonic = note?.optString("mnemonic").orEmpty(),
    )
}

fun parseHotKanji(j: JSONObject): List<HotKanjiView> = j.optJSONArray("items").mapObjects {
    HotKanjiView(it.getString("literal"), it.optJSONArray("meanings").strings(), it.optDouble("score", 0.0), it.optInt("word_count"))
}

fun parseLeeches(j: JSONObject): List<LeechView> = j.optJSONArray("items").mapObjects {
    LeechView(
        it.getString("id"), it.getString("note_id"), noteLabel(it.optJSONObject("note")).ifEmpty { it.getString("note_id") },
        it.optInt("lapses"), it.optBoolean("suspended"),
    )
}

fun parseNextCursor(j: JSONObject): String? = if (j.isNull("next_cursor")) null else j.optString("next_cursor").ifEmpty { null }

fun parseConfusables(j: JSONObject): List<ConfusableView> = j.optJSONArray("items").mapObjects {
    ConfusableView(
        it.getString("note_a_id"), it.getString("note_b_id"), it.getString("reason"), it.getString("status"),
        noteLabel(it.optJSONObject("note_a")), noteLabel(it.optJSONObject("note_b")),
    )
}

fun parseSentenceItems(j: JSONObject): List<SentenceItem> = j.optJSONArray("items").mapObjects {
    SentenceItem(
        it.getString("id"), it.getString("media_id"), it.getString("text"), sentenceReading(it),
        if (it.isNull("audio_blob_hash")) null else it.getString("audio_blob_hash"),
    )
}

/** Reading line of a sentence: token readings (surface when none), untokenised gaps copied from the text. */
fun sentenceReading(s: JSONObject): String {
    val text = s.getString("text")
    val tokens = s.optJSONArray("tokens") ?: return text
    val out = StringBuilder()
    var pos = 0
    for (i in 0 until tokens.length()) {
        val t = tokens.getJSONObject(i)
        val a = t.optInt("s").coerceIn(0, text.length)
        val b = t.optInt("e").coerceIn(a, text.length)
        if (a > pos) out.append(text, pos, a)
        out.append(t.str("reading") ?: text.substring(a, b))
        pos = maxOf(pos, b)
    }
    if (pos < text.length) out.append(text, pos, text.length)
    return out.toString()
}

fun parseDrill(j: JSONObject): List<DrillItem> = j.optJSONArray("items").mapObjects {
    val card = it.getJSONObject("card")
    val note = card.optJSONObject("note")
    val entry = if (it.isNull("entry")) null else it.optJSONObject("entry")
    val reading = entry?.str("headword_reading") ?: note?.optJSONObject("fields")?.str("reading").orEmpty()
    val gloss = entry?.optJSONArray("glosses").strings().take(3).joinToString("; ")
        .ifEmpty { note?.optJSONObject("fields")?.str("meaning").orEmpty() }
    DrillItem(card.getString("id"), noteLabel(note), listOf(reading, gloss).filter { s -> s.isNotEmpty() }.joinToString("\n"))
}
