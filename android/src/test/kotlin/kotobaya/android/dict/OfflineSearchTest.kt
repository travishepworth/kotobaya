package kotobaya.android.dict

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Search over an installed package with no network object anywhere: [InstalledDictionary] only takes a [DictStore].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class OfflineSearchTest {
    private fun installed(entries: List<Fx>): InstalledDictionary {
        val dir = File.createTempFile("offline", "").also { it.delete(); it.mkdirs() }
        val store = DictStore(File(dir, "store"))
        PackageFixtures.create(File(dir, "p.db"), 1, "top60k", entries)
        File(dir, "p.db").copyTo(store.installed)
        return InstalledDictionary(store)
    }

    private val entries = listOf(
        Fx(1, "食べる", "たべる", listOf("to eat", "to live on"), 100),
        Fx(2, "猫", "ねこ", listOf("cat"), 200),
        Fx(3, "学校", "がっこう", listOf("school"), 150),
        Fx(4, "ネコ", "ネコ", listOf("cat (colloquial)"), 900, common = false, kanjiForm = null),
        Fx(5, "古い", "ふるい", listOf("old"), 50, retired = true),
        Fx(7, "食べ物", "たべもの", listOf("food"), 120),
    )

    @Test
    fun kanaKanjiRomajiKatakanaAndEnglish() {
        val d = installed(entries)
        fun seqs(q: String) = d.search(q).items.map { it.seq }
        assertEquals(listOf(1L, 7L), seqs("たべ"))
        assertEquals(1L, seqs("たべる").first())
        assertEquals(3L, seqs("学校").first())
        assertEquals(listOf(2L, 4L), seqs("neko")) // romaji; ranked entry before the unranked-ish one
        assertEquals(listOf(2L, 4L), seqs("ネコ")) // katakana folds to hiragana
        assertEquals(listOf(2L, 4L), seqs("cat")) // English gloss
        assertEquals(3L, seqs("gakkou").first())
        assertTrue(seqs("ふるい").isEmpty(), "retired entries are not returned")
        assertEquals(listOf(2L), d.search("neko", commonOnly = true).items.map { it.seq })
        val hit = d.search("たべる").items.first()
        assertEquals(listOf("to eat", "to live on"), hit.glosses)
        assertEquals("たべる", hit.headwordReading)
        assertEquals(100, hit.freqRank)
    }

    @Test
    fun paginationWalksEveryHitOnce() {
        val d = installed(entries)
        val seen = mutableListOf<Long>()
        var cursor: DictSearchCursor? = null
        do {
            val p = d.search("たべ", limit = 1, after = cursor)
            seen += p.items.map { it.seq }
            cursor = p.next
        } while (cursor != null)
        assertEquals(seen.distinct(), seen)
        assertEquals(d.search("たべ", limit = 50).items.map { it.seq }, seen)
    }

    @Test
    fun entryKanjiAndExamples() {
        val d = installed(entries)
        val e = assertNotNull(d.entry(1))
        assertEquals("食べる", e.headword)
        assertEquals(listOf(FreqRank("jpdb", 100)), e.freqs)
        assertEquals(listOf(PitchInfo("たべる", listOf(0, 2))), e.pitch)
        assertEquals(listOf("食"), e.kanji)
        assertNull(d.entry(999))
        assertEquals("eat", assertNotNull(d.kanji("食")).meanings.single())
        assertEquals(1, d.examples(1).size)
        assertTrue(d.examples(2).isEmpty())
    }

    @Test
    fun noPackageMeansNoResults() {
        val dir = File.createTempFile("none", "").also { it.delete(); it.mkdirs() }
        val d = InstalledDictionary(DictStore(File(dir, "store")))
        assertTrue(d.search("neko").items.isEmpty())
        assertNull(d.info())
    }

    // ------------------------------------------------------------------ parity with the server (T106 queries)

    @Test
    fun firstResultMatchesTheServerOnTheFiftyFixtureQueries() {
        val file = loadServerFixture()
        val dir = File.createTempFile("parity", "").also { it.delete(); it.mkdirs() }
        val store = DictStore(File(dir, "store"))
        file.copyTo(store.installed)
        val d = InstalledDictionary(store)
        val spec = JSONObject(File("../server/src/test/resources/dict/queries.json").readText())
        val queries = spec.getJSONArray("queries")
        assertEquals(50, queries.length())
        val diffs = mutableListOf<String>()
        for (i in 0 until queries.length()) {
            val q = queries.getJSONObject(i)
            val expect = if (q.isNull("expect")) null else q.getLong("expect")
            val got = d.search(q.getString("q")).items.firstOrNull()?.seq
            if (got != expect) diffs += "${q.getString("kind")} '${q.getString("q")}': expected $expect, got $got"
        }
        assertTrue(diffs.isEmpty(), diffs.joinToString("\n"))
    }

    /** Builds a device package from the server's data-only Postgres dump of the T101 fixture (freq_rank as the server test sets it). */
    private fun loadServerFixture(): File {
        val retired = setOf(1584660L)
        val copy = mutableMapOf<String, MutableList<List<String?>>>()
        val lines = GZIPInputStream(File("../server/src/test/resources/dict/ref_fixture.sql.gz").inputStream()).bufferedReader().readLines()
        var i = 0
        while (i < lines.size) {
            val m = Regex("""^COPY ref\.(\w+) \(.*\) FROM stdin;""").find(lines[i++]) ?: continue
            val rows = copy.getOrPut(m.groupValues[1]) { mutableListOf() }
            while (lines[i] != "\\.") rows += lines[i++].split("\t").map { unescape(it) }
            i++
        }
        val file = PackageFixtures.create(File.createTempFile("fixture", ".db"), 1, "top60k", emptyList())
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            db.execSQL("DELETE FROM example_sentence_entry")
            db.execSQL("DELETE FROM example_sentence")
            db.execSQL("DELETE FROM kanji")
            val entryRows = copy.getValue("dict_entry").sortedBy { it[0]!!.toLong() }
            var n = 0
            for (r in entryRows) {
                val seq = r[0]!!.toLong()
                val common = r[4] == "t"
                val rank = if (common) ++n else null
                db.execSQL(
                    "INSERT INTO dict_entry VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        seq, r[1], r[2], r[3], if (common) 1 else 0, r[5], r[6], r[7], array(r[8]!!), array(r[9]!!),
                        rank, if (seq in retired) 1 else 0,
                    ),
                )
            }
            for (r in copy.getValue("dict_entry_form")) {
                if (r[0]!!.toLong() in retired) continue
                db.execSQL(
                    "INSERT INTO dict_entry_form VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(r[0]!!.toLong(), r[1], r[2]!!.toLong(), r[3], r[4], if (r[5] == "t") 1 else 0),
                )
            }
            for (r in copy.getValue("dict_entry_gloss")) {
                if (r[0]!!.toLong() in retired) continue
                db.execSQL(
                    "INSERT INTO dict_entry_gloss VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(r[0]!!.toLong(), r[1]!!.toLong(), r[2]!!.toLong(), r[3], r[4], r[4]!!.lowercase()),
                )
            }
            for (r in copy.getValue("entry_kanji")) db.execSQL("INSERT INTO entry_kanji VALUES (?, ?)", arrayOf<Any?>(r[0]!!.toLong(), r[1]))
            for (r in copy.getValue("kanji")) {
                db.execSQL(
                    "INSERT INTO kanji VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        r[0], array(r[1]!!), array(r[2]!!), array(r[3]!!), array(r[4]!!), r[5]?.toLong(), r[6]?.toLong(),
                        r[7]?.toLong(), r[8]?.toLong(), r[9], array(r[10]!!),
                    ),
                )
            }
            db.setTransactionSuccessful()
            db.endTransaction()
        } finally {
            db.close()
        }
        return file
    }

    private fun unescape(s: String): String? {
        if (s == "\\N") return null
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(
                    when (val n = s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> n },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Postgres text[] literal to a JSON array string. */
    private fun array(literal: String): String {
        val body = literal.removePrefix("{").removeSuffix("}")
        val items = mutableListOf<String>()
        var i = 0
        while (i < body.length) {
            if (body[i] == '"') {
                val sb = StringBuilder()
                i++
                while (body[i] != '"') {
                    if (body[i] == '\\') i++
                    sb.append(body[i++])
                }
                i++
                items += sb.toString()
            } else {
                val end = body.indexOf(',', i).let { if (it < 0) body.length else it }
                items += body.substring(i, end)
                i = end
            }
            if (i < body.length && body[i] == ',') i++
        }
        return JSONArray(items).toString()
    }
}
