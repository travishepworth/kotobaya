package kotobaya.android.dict

import android.database.sqlite.SQLiteDatabase
import java.text.Normalizer
import java.util.Locale
import kotobaya.dict.Romaji
import org.json.JSONArray

/** Search hit, shaped like the API's `DictEntrySummary` (status and note overlays belong to the learning DB). */
data class DictSummary(
    val seq: Long,
    val headword: String,
    val headwordReading: String,
    val isCommon: Boolean,
    val glosses: List<String>,
    val pos: List<String>,
    val freqRank: Int?,
    val retired: Boolean,
)

/** Keyset position after the last row of a page (grade, frequency sort key, seq). */
data class DictSearchCursor(val grade: Int, val freq: Int, val seq: Long)

data class DictSearchPage(val items: List<DictSummary>, val next: DictSearchCursor?)

data class FreqRank(val listId: String, val rank: Int)
data class PitchInfo(val reading: String, val positions: List<Int>)

/** Shaped like the API's `DictEntry`; JSON columns stay as parsed arrays. */
data class DictEntryDetail(
    val seq: Long,
    val language: String,
    val headword: String,
    val headwordReading: String,
    val isCommon: Boolean,
    val kanjiForms: JSONArray,
    val readings: JSONArray,
    val senses: JSONArray,
    val pos: List<String>,
    val misc: List<String>,
    val freqRank: Int?,
    val retired: Boolean,
    val freqs: List<FreqRank>,
    val pitch: List<PitchInfo>,
    val kanji: List<String>,
)

data class KanjiDetail(
    val literal: String,
    val onReadings: List<String>,
    val kunReadings: List<String>,
    val nanori: List<String>,
    val meanings: List<String>,
    val strokeCount: Int?,
    val grade: Int?,
    val jlpt: Int?,
    val freqRank: Int?,
    val radical: String?,
    val components: List<String>,
)

data class ExampleSentence(val id: Long, val text: String, val translation: String)

/**
 * Read-only view of the installed ref database. Opens the file with `SQLITE_OPEN_READONLY`, reopens when the
 * installer swaps the file ([DictStore.generation]), and never touches the network. Search follows the server's
 * `/dict/search` (kana, kanji, romaji, English gloss; same grading and ordering) over the entries the device
 * package holds. Supersedes the `db.DictionaryProvider` seam of T301a (nothing reads that one).
 */
class InstalledDictionary(
    private val store: DictStore,
    /** Reports reads on the main thread (BUG-8); null in tests that build it alone. */
    private val guard: kotobaya.android.db.DbThreadGuard? = null,
) {
    private var handle: SQLiteDatabase? = null
    private var handleGeneration = -1L

    /** The installed release, or null when none is installed. */
    fun info(): PackageInfo? = withDb { PackageFile.info(it) }

    private fun <T> withDb(block: (SQLiteDatabase) -> T?): T? = synchronized(this) {
        guard?.check("dictionary read")
        val gen = store.generation.value
        if (handle != null && (gen != handleGeneration || handle?.isOpen != true)) {
            handle?.close()
            handle = null
        }
        if (handle == null) {
            if (!store.installed.isFile) return null
            handle = PackageFile.openReadOnly(store.installed)
            handleGeneration = gen
        }
        block(handle ?: return null)
    }

    fun close() = synchronized(this) {
        handle?.close()
        handle = null
    }

    /** True once a package is installed. */
    fun isAvailable(): Boolean = store.installed.isFile

    fun search(raw: String, commonOnly: Boolean = false, limit: Int = 50, after: DictSearchCursor? = null): DictSearchPage {
        val q = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        require(q.isNotEmpty()) { "empty query" }
        val latin = !containsKanjiOrKana(q)
        val kana: String? = if (latin) Romaji.toHiragana(q) else q
        val binds = mutableListOf<String>()
        val branches = mutableListOf<String>()
        if (kana != null) {
            val key = foldKatakana(kana)
            val short = key.codePointCount(0, key.length) <= 2
            // Exact (0), prefix (1), substring (2). 1-2 characters: exact or prefix through the norm index.
            val sub = if (short) "norm >= ? AND norm < ?" else "instr(norm, ?) > 0"
            branches += "SELECT seq, CASE WHEN norm = ? THEN 0 WHEN norm >= ? AND norm < ? THEN 1 ELSE 2 END AS g " +
                "FROM dict_entry_form WHERE $sub"
            binds += key; binds += key; binds += key + TOP; if (short) { binds += key; binds += key + TOP } else binds += key
        }
        if (latin) {
            branches += "SELECT seq, 2 AS g FROM dict_entry_gloss WHERE instr(gloss_lower, ?) > 0"
            binds += q.lowercase(Locale.ROOT)
        }
        val sql = StringBuilder("WITH hits AS (SELECT seq, min(g) AS g FROM (")
        sql.append(branches.joinToString(" UNION ALL ")).append(") GROUP BY seq) ")
        sql.append(
            "SELECT e.seq, e.headword, e.headword_reading, e.is_common, e.senses, e.pos, e.freq_rank, e.retired, h.g, " +
                "coalesce(e.freq_rank, $NO_RANK) AS f FROM hits h JOIN dict_entry e ON e.seq = h.seq WHERE e.retired = 0 ",
        )
        if (commonOnly) sql.append("AND e.is_common = 1 ")
        if (after != null) {
            sql.append("AND (h.g, coalesce(e.freq_rank, $NO_RANK), e.seq) > (${after.grade}, ${after.freq}, ${after.seq}) ")
        }
        sql.append("ORDER BY h.g, f, e.seq LIMIT ${limit + 1}")
        val rows = withDb { db ->
            db.rawQuery(sql.toString(), binds.toTypedArray()).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            Triple(
                                DictSummary(
                                    c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) != 0,
                                    firstSenseGlosses(c.getString(4)), stringList(c.getString(5)),
                                    if (c.isNull(6)) null else c.getInt(6), c.getInt(7) != 0,
                                ),
                                c.getInt(8), c.getInt(9),
                            ),
                        )
                    }
                }
            }
        } ?: emptyList()
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.last().let { DictSearchCursor(it.second, it.third, it.first.seq) } else null
        return DictSearchPage(page.map { it.first }, next)
    }

    fun entry(seq: Long): DictEntryDetail? = withDb { db ->
        val base = db.rawQuery(
            "SELECT language, headword, headword_reading, is_common, kanji_forms, readings, senses, pos, misc, freq_rank, retired " +
                "FROM dict_entry WHERE seq = ?",
            arrayOf(seq.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return@withDb null
            arrayOf(
                c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5),
                c.getString(6), c.getString(7), c.getString(8), if (c.isNull(9)) null else c.getInt(9), c.getInt(10),
            )
        }
        val freqs = db.rawQuery(
            "SELECT f.list_id, f.rank FROM entry_freq f JOIN freq_list l ON l.id = f.list_id WHERE f.seq = ? ORDER BY l.priority",
            arrayOf(seq.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(FreqRank(c.getString(0), c.getInt(1))) } }
        val pitch = db.rawQuery(
            "SELECT reading, positions FROM entry_pitch WHERE seq = ? ORDER BY reading", arrayOf(seq.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val arr = JSONArray(c.getString(1))
                    add(PitchInfo(c.getString(0), (0 until arr.length()).map { arr.getInt(it) }))
                }
            }
        }
        val kanjiForms = JSONArray(base[4] as String)
        val kanji = LinkedHashSet<String>()
        for (i in 0 until kanjiForms.length()) {
            val text = kanjiForms.getJSONObject(i).getString("text")
            text.codePoints().forEach { cp ->
                if (isKanji(cp) && cp != 0x3005 && cp != 0x3007) kanji += String(Character.toChars(cp))
            }
        }
        DictEntryDetail(
            seq, base[0] as String, base[1] as String, base[2] as String, (base[3] as Int) != 0, kanjiForms,
            JSONArray(base[5] as String), JSONArray(base[6] as String), stringList(base[7] as String),
            stringList(base[8] as String), base[9] as Int?, (base[10] as Int) != 0, freqs, pitch, kanji.toList(),
        )
    }

    fun kanji(literal: String): KanjiDetail? = withDb { db ->
        db.rawQuery(
            "SELECT on_readings, kun_readings, nanori, meanings, stroke_count, grade, jlpt, freq_rank, radical, components " +
                "FROM kanji WHERE literal = ?",
            arrayOf(literal),
        ).use { c ->
            if (!c.moveToFirst()) return@withDb null
            fun i(n: Int) = if (c.isNull(n)) null else c.getInt(n)
            KanjiDetail(
                literal, stringList(c.getString(0)), stringList(c.getString(1)), stringList(c.getString(2)),
                stringList(c.getString(3)), i(4), i(5), i(6), i(7), if (c.isNull(8)) null else c.getString(8),
                stringList(c.getString(9)),
            )
        }
    }

    /** Example sentences linked to [seq]; empty when the package carries none. */
    fun examples(seq: Long, limit: Int = 20): List<ExampleSentence> = withDb { db ->
        db.rawQuery(
            "SELECT s.id, s.text, s.translation FROM example_sentence_entry x JOIN example_sentence s ON s.id = x.sentence_id " +
                "WHERE x.seq = ? ORDER BY s.id LIMIT ${limit.coerceAtLeast(0)}",
            arrayOf(seq.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(ExampleSentence(c.getLong(0), c.getString(1), c.getString(2))) } }
    } ?: emptyList()

    private fun firstSenseGlosses(json: String): List<String> {
        val senses = JSONArray(json)
        val gl = senses.optJSONObject(0)?.optJSONArray("gloss") ?: return emptyList()
        return (0 until gl.length()).map { gl.getJSONObject(it).getString("text") }
    }

    private fun stringList(json: String): List<String> = JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } }

    private companion object {
        const val NO_RANK = Int.MAX_VALUE
        /** U+10FFFF: sorts after every other code point, so `[key, key + TOP)` is the prefix range. */
        const val TOP = "􏿿"

        fun foldKatakana(text: String): String = buildString(text.length) {
            for (ch in text) {
                val cp = ch.code
                append(if (cp in 0x30A1..0x30F6 || cp == 0x30FD || cp == 0x30FE) (cp - 0x60).toChar() else ch)
            }
        }

        fun isKana(cp: Int) = cp in 0x3041..0x3096 || cp in 0x309D..0x309F || cp in 0x30A1..0x30FA ||
            cp in 0x30FC..0x30FF || cp in 0xFF66..0xFF9F

        fun isKanji(cp: Int) = cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF ||
            cp in 0x20000..0x2FA1F || cp in 0x30000..0x323AF || cp == 0x3005 || cp == 0x3007

        fun containsKanjiOrKana(q: String) = q.codePoints().anyMatch { isKana(it) || isKanji(it) }
    }
}
