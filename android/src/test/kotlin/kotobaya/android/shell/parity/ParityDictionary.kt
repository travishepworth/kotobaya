package kotobaya.android.shell.parity

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import kotobaya.android.dict.DictStore

/**
 * The dictionary both parity harnesses load (SHELL.md 8.2): the server's `ref.*` fixture — the committed dump of the
 * T101 loader's output on `fixtures/dict/JMdict_subset.xml` + `kanjidic2_subset.xml`
 * (`server/src/test/resources/dict/ref_fixture.sql.gz`, what SHELL-P1 loads) — converted to an on-device package
 * (`contracts/db/sqldelight/ref/.../Dict.sq`, type mapping of its header: boolean -> 0/1, text[] / jsonb -> JSON text)
 * of scope **`all`**, built once per JVM with plain JDBC (no Android `SQLiteDatabase`, no Robolectric).
 *
 * Installing it ([install]) puts the file where [DictStore.installed] expects it. The "last known server release" of
 * L-7 is [releaseId]: the L ticket that adds the lookup sets it from [ParityDevice.dictionary] (there is no such
 * state on the device yet), with an empty sidecar cache and no network.
 */
class ParityDictionary private constructor(val packageFile: File, val releaseId: Long, val seqs: Set<Long>) {
    /** Copies the package into a fresh [DictStore] under [dir]. */
    fun install(dir: File): DictStore {
        val store = DictStore(dir)
        packageFile.copyTo(store.installed, overwrite = true)
        return store
    }

    companion object {
        const val SCOPE: String = "all"

        val fixture: ParityDictionary by lazy { build() }

        private val dumpFile: File get() = File(ParityScenario.repoRoot, "server/src/test/resources/dict/ref_fixture.sql.gz")
        private val dictSq: File get() = File(ParityScenario.repoRoot, "contracts/db/sqldelight/ref/kotobaya/db/ref/Dict.sq")

        private fun schemaStatements(): List<String> =
            dictSq.readLines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                .replace(Regex("""\s+AS\s+kotlin\.\w+"""), "")
                .replace(Regex("""--[^\n]*"""), "")
                .split(";").map { it.trim() }.filter { it.isNotEmpty() }

        private fun build(): ParityDictionary {
            val file = File.createTempFile("parity-dict", ".db").also { it.delete(); it.deleteOnExit() }
            val tables = readCopies()
            var releaseId = -1L
            val seqs = HashSet<Long>()
            DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
                c.autoCommit = false
                c.createStatement().use { s -> schemaStatements().forEach { s.execute(it) } }
                c.createStatement().use { it.execute("PRAGMA user_version = 1") }
                for (r in tables.getValue("dict_release")) {
                    releaseId = r.getValue("id")!!.toLong()
                    insert(c, "dict_release", listOf(releaseId, r["version"], instantMs(r.getValue("built_at")!!), SCOPE, r["sources"] ?: "{}"))
                }
                for (r in tables.getValue("dict_entry")) {
                    val seq = r.getValue("seq")!!.toLong()
                    seqs += seq
                    insert(
                        c, "dict_entry",
                        listOf(
                            seq, r["language"], r["headword"], r["headword_reading"], bool(r["is_common"]),
                            r["kanji_forms"], r["readings"], r["senses"], arrayJson(r["pos"]), arrayJson(r["misc"]),
                            r["freq_rank"]?.toLong(), if (r["retired_release_id"] != null) 1 else 0,
                        ),
                    )
                }
                for (r in tables.getValue("dict_entry_form")) {
                    insert(c, "dict_entry_form", listOf(r["seq"]!!.toLong(), r["kind"], r["ord"]!!.toLong(), r["form"], r["norm"], bool(r["common"])))
                }
                for (r in tables.getValue("dict_entry_gloss")) {
                    val gloss = r.getValue("gloss")!!
                    insert(
                        c, "dict_entry_gloss",
                        listOf(r["seq"]!!.toLong(), r["sense_ord"]!!.toLong(), r["gloss_ord"]!!.toLong(), r["lang"], gloss, gloss.lowercase()),
                    )
                }
                for (r in tables.getValue("entry_kanji")) insert(c, "entry_kanji", listOf(r["seq"]!!.toLong(), r["literal"]))
                for (r in tables.getValue("kanji")) {
                    insert(
                        c, "kanji",
                        listOf(
                            r["literal"], arrayJson(r["on_readings"]), arrayJson(r["kun_readings"]), arrayJson(r["nanori"]),
                            arrayJson(r["meanings"]), r["stroke_count"]?.toLong(), r["grade"]?.toLong(), r["jlpt"]?.toLong(),
                            r["freq_rank"]?.toLong(), r["radical"], arrayJson(r["components"]),
                        ),
                    )
                }
                c.createStatement().use { s ->
                    s.executeQuery("PRAGMA foreign_key_check").use { check(!it.next()) { "foreign key violation in the parity dictionary" } }
                }
                c.commit()
            }
            check(releaseId > 0) { "no dict_release in $dumpFile" }
            return ParityDictionary(file, releaseId, seqs)
        }

        private fun insert(c: Connection, table: String, values: List<Any?>) {
            c.prepareStatement("INSERT INTO $table VALUES (${values.joinToString(",") { "?" }})").use { ps ->
                values.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
                ps.executeUpdate()
            }
        }

        private fun bool(v: String?): Int = if (v == "t") 1 else 0

        private fun instantMs(pg: String): Long =
            OffsetDateTime.parse(pg.replace(' ', 'T').let { if (Regex("[+-]\\d\\d$").containsMatchIn(it)) "$it:00" else it }, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant().toEpochMilli()

        /** `COPY ref.<table> (cols) FROM stdin;` blocks of the dump: table -> rows (column -> text, null for `\N`). */
        private fun readCopies(): Map<String, List<Map<String, String?>>> {
            val lines = GZIPInputStream(dumpFile.inputStream()).bufferedReader().readLines()
            val out = HashMap<String, MutableList<Map<String, String?>>>()
            val header = Regex("""^COPY ref\.(\w+) \(([^)]*)\) FROM stdin;$""")
            var i = 0
            while (i < lines.size) {
                val m = header.matchEntire(lines[i++]) ?: continue
                val cols = m.groupValues[2].split(", ")
                val rows = out.getOrPut(m.groupValues[1]) { ArrayList() }
                while (lines[i] != "\\.") {
                    val fields = lines[i++].split('\t').map { unescapeCopy(it) }
                    check(fields.size == cols.size) { "${m.groupValues[1]}: ${fields.size} fields for ${cols.size} columns" }
                    rows += cols.zip(fields).toMap()
                }
                i++
            }
            return out
        }

        /** COPY text format: `\N` is null; backslash escapes `\\ \t \n \r \b \f \v`. */
        private fun unescapeCopy(s: String): String? {
            if (s == "\\N") return null
            if ('\\' !in s) return s
            val b = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i++]
                if (c != '\\' || i >= s.length) {
                    b.append(c)
                    continue
                }
                when (val e = s[i++]) {
                    't' -> b.append('\t')
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000c')
                    'v' -> b.append('\u000b')
                    else -> b.append(e)
                }
            }
            return b.toString()
        }

        /** A Postgres one-dimensional array literal (`{a,"b c"}`) as a JSON array of strings; null stays null. */
        internal fun arrayJson(pg: String?): String? {
            if (pg == null) return null
            require(pg.startsWith("{") && pg.endsWith("}")) { "not an array literal: $pg" }
            val items = ArrayList<String>()
            val body = pg.substring(1, pg.length - 1)
            var i = 0
            while (i < body.length) {
                val cur = StringBuilder()
                if (body[i] == '"') {
                    i++
                    while (body[i] != '"') {
                        if (body[i] == '\\') i++
                        cur.append(body[i++])
                    }
                    i++ // closing quote
                } else {
                    while (i < body.length && body[i] != ',') cur.append(body[i++])
                }
                items += cur.toString()
                i++ // the comma
            }
            return items.joinToString(",", "[", "]") { quote(it) }
        }

        private fun quote(s: String): String = buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
