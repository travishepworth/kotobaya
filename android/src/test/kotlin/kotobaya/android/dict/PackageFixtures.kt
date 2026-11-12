package kotobaya.android.dict

import android.database.sqlite.SQLiteDatabase
import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** One dictionary entry of a test package. */
class Fx(
    val seq: Long,
    val headword: String,
    val reading: String,
    val glosses: List<String>,
    val rank: Int?,
    val common: Boolean = true,
    val retired: Boolean = false,
    val kanjiForm: String? = headword.takeIf { it != reading },
)

/** Builds tiny packages per DICT_PACKAGE section 2 from the real `Dict.sq`, plus diffs and compression. */
internal object PackageFixtures {
    private val dictSq = File("../contracts/db/sqldelight/ref/kotobaya/db/ref/Dict.sq")

    val schemaStatements: List<String> by lazy {
        dictSq.readLines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .replace(Regex("""\s+AS\s+kotlin\.\w+"""), "")
            .replace(Regex("""--[^\n]*"""), "")
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun create(file: File, releaseId: Long, scope: String, entries: List<Fx>): File {
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            schemaStatements.forEach { db.execSQL(it) }
            db.execSQL("PRAGMA user_version = 1")
            db.execSQL("INSERT INTO dict_release VALUES (?, ?, 1700000000000, ?, '{}')", arrayOf(releaseId, "v$releaseId", scope))
            db.execSQL("INSERT INTO freq_list VALUES ('jpdb', 'jpdb frequency', 0)")
            for (e in entries.sortedBy { it.seq }) insertEntry(db, e)
            db.execSQL("INSERT INTO kanji VALUES ('食', '[\"ショク\"]', '[\"た.べる\"]', '[]', '[\"eat\"]', 9, 2, 5, 300, '飠', '[]')")
            // Example sentence linked to the entry with the lowest seq, when it exists.
            entries.minByOrNull { it.seq }?.let { e ->
                db.execSQL("INSERT INTO example_sentence VALUES (1, 'ja', 'sentence', 'translation', NULL)")
                db.execSQL("INSERT INTO example_sentence_entry VALUES (1, ?)", arrayOf(e.seq))
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(it.count == 0) }
        } finally {
            db.close()
        }
        return file
    }

    private fun insertEntry(db: SQLiteDatabase, e: Fx) {
        val kanjiForms = JSONArray().also { a -> e.kanjiForm?.let { a.put(JSONObject().put("text", it).put("common", e.common)) } }
        val readings = JSONArray().put(JSONObject().put("text", e.reading).put("common", e.common))
        val gloss = JSONArray().also { a -> e.glosses.forEach { a.put(JSONObject().put("lang", "eng").put("text", it)) } }
        val senses = JSONArray().put(JSONObject().put("pos", JSONArray().put("n")).put("gloss", gloss))
        db.execSQL(
            "INSERT INTO dict_entry VALUES (?, 'ja', ?, ?, ?, ?, ?, ?, '[\"n\"]', '[]', ?, ?)",
            arrayOf<Any?>(
                e.seq, e.headword, e.reading, if (e.common) 1 else 0, kanjiForms.toString(), readings.toString(),
                senses.toString(), e.rank, if (e.retired) 1 else 0,
            ),
        )
        if (e.retired) return
        var ord = 0
        e.kanjiForm?.let {
            db.execSQL("INSERT INTO dict_entry_form VALUES (?, 'kanji', ?, ?, ?, ?)", arrayOf<Any?>(e.seq, ord++, it, norm(it), if (e.common) 1 else 0))
        }
        db.execSQL("INSERT INTO dict_entry_form VALUES (?, 'reading', 0, ?, ?, ?)", arrayOf<Any?>(e.seq, e.reading, norm(e.reading), if (e.common) 1 else 0))
        e.glosses.forEachIndexed { i, g ->
            db.execSQL("INSERT INTO dict_entry_gloss VALUES (?, 0, ?, 'eng', ?, ?)", arrayOf<Any?>(e.seq, i, g, g.lowercase()))
        }
        e.rank?.let { db.execSQL("INSERT INTO entry_freq VALUES (?, 'jpdb', ?)", arrayOf<Any?>(e.seq, it)) }
        if (e.kanjiForm?.contains('食') == true) {
            db.execSQL("INSERT INTO entry_kanji VALUES (?, '食')", arrayOf<Any?>(e.seq))
            db.execSQL("INSERT INTO entry_pitch VALUES (?, ?, '[0,2]')", arrayOf<Any?>(e.seq, e.reading))
        }
    }

    fun norm(s: String): String = buildString {
        for (ch in java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)) {
            append(if (ch.code in 0x30A1..0x30F6) (ch.code - 0x60).toChar() else ch)
        }
    }

    fun zstd(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZstdOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    fun zstd(file: File): ByteArray = zstd(file.readBytes())

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** The diff of DICT_PACKAGE 3 between two package files; [tamper] edits the JSON before it is serialised. */
    fun diff(from: File, to: File, tamper: (JSONObject) -> Unit = {}): JSONObject {
        val a = SQLiteDatabase.openDatabase(from.path, null, SQLiteDatabase.OPEN_READONLY)
        val b = SQLiteDatabase.openDatabase(to.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val fromInfo = PackageFile.info(a)!!
            val toInfo = PackageFile.info(b)!!
            val tables = JSONObject()
            val counts = JSONObject()
            val digests = JSONObject()
            for (t in DictSchema.tables) {
                val ra = rows(a, t)
                val rb = rows(b, t)
                val up = JSONArray()
                val del = JSONArray()
                for ((k, row) in rb) if (ra[k] != row) up.put(JSONArray(row.map { it ?: JSONObject.NULL }))
                for ((k, _) in ra) if (k !in rb) del.put(JSONArray(pkOf(t, ra.getValue(k))))
                tables.put(t.name, JSONObject().put("columns", JSONArray(t.columns)).put("pk", JSONArray(t.pk)).put("upsert", up).put("delete", del))
                counts.put(t.name, rb.size.toLong())
                digests.put(t.name, PackageFile.tableDigest(b, t))
            }
            val doc = JSONObject()
                .put("format", "kotobaya-dict-diff").put("format_version", 1)
                .put("from_release", fromInfo.releaseId).put("to_release", toInfo.releaseId).put("scope", toInfo.scope)
                .put("tables", tables).put("row_counts", counts).put("table_digests", digests)
            tamper(doc)
            return doc
        } finally {
            a.close()
            b.close()
        }
    }

    private fun pkOf(t: DictTable, row: List<Any?>): List<Any?> = t.pk.map { row[t.columns.indexOf(it)] }

    private fun rows(db: SQLiteDatabase, t: DictTable): Map<List<Any?>, List<Any?>> =
        db.rawQuery("SELECT ${t.columns.joinToString(",")} FROM ${t.name} ORDER BY ${t.pk.joinToString(",")}", null).use { c ->
            val out = LinkedHashMap<List<Any?>, List<Any?>>()
            while (c.moveToNext()) {
                val row = t.columns.indices.map { i ->
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        else -> c.getString(i)
                    }
                }
                out[pkOf(t, row)] = row
            }
            out
        }

    fun artifact(kind: String, scope: String, from: Long?, blob: ByteArray, uncompressed: Long, format: Int = 1) =
        Artifact(kind, scope, from, format, sha256(blob), blob.size.toLong(), uncompressed)
}

/** In-memory server: blobs by hash, with switches for the failure cases of the acceptance list. */
class FakeRemote : DictRemote {
    var release: DictReleaseInfo? = null
    val blobs = mutableMapOf<String, ByteArray>()
    val fetched = mutableListOf<String>()
    val offsets = mutableListOf<Long>()
    val missing = mutableSetOf<String>()

    /** Number of upcoming fetches whose bytes are flipped (hash mismatch). */
    var corruptNext = 0

    /** When set, the next fetch writes this many bytes and then fails (interrupted download). */
    var interruptAfter: Int? = null

    override suspend fun release(): DictReleaseInfo? = release

    override suspend fun fetchBlob(hash: String, part: File, onProgress: (bytes: Long) -> Unit) {
        fetched += hash
        if (hash in missing) throw BlobNotFound(hash)
        val data = blobs.getValue(hash)
        val have = if (part.exists()) part.length() else 0L
        offsets += have
        var chunk = data.copyOfRange(have.toInt(), data.size)
        if (corruptNext > 0) {
            corruptNext--
            chunk = chunk.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0].toInt() xor 0x55).toByte() }
        }
        val cut = interruptAfter
        if (cut != null) {
            interruptAfter = null
            part.appendBytes(chunk.copyOf(cut))
            throw IOException("connection reset")
        }
        part.appendBytes(chunk)
        onProgress(part.length())
    }
}
