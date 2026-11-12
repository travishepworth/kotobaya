package kotobaya.android.dict

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The installed (or staged) release as its own `dict_release` row says (authoritative, DICT_PACKAGE 5). */
data class PackageInfo(val releaseId: Long, val scope: String, val version: String, val userVersion: Int)

/** A package file failed a check of DICT_PACKAGE 2.3 / 3.4. */
class PackageException(message: String) : Exception(message)

/** SQLite and file helpers of DICT_PACKAGE: reading, table digests (3.5), diff apply (3.4), durable swap. */
internal object PackageFile {
    private val SIDE = listOf("-journal", "-wal", "-shm")

    fun openReadOnly(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    fun openWritable(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            file.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )

    fun deleteWithSidecars(file: File) {
        file.delete()
        for (s in SIDE) File(file.path + s).delete()
    }

    private fun <T> SQLiteDatabase.pragma(sql: String, read: (Cursor) -> T): T =
        rawQuery(sql, null).use { c -> c.moveToFirst(); read(c) }

    /** Null when the file is unreadable or has no single `dict_release` row. */
    fun readInfo(file: File): PackageInfo? {
        if (!file.isFile) return null
        return try {
            openReadOnly(file).use { db -> info(db) }
        } catch (_: Exception) {
            null
        }
    }

    fun info(db: SQLiteDatabase): PackageInfo? {
        val uv = db.pragma("PRAGMA user_version") { it.getInt(0) }
        return db.rawQuery("SELECT id, scope, version FROM dict_release", null).use { c ->
            if (c.count != 1) null else { c.moveToFirst(); PackageInfo(c.getLong(0), c.getString(1), c.getString(2), uv) }
        }
    }

    /** Streams a zstd (possibly multi-frame) file into [dest]. */
    fun decompress(src: File, dest: File) {
        src.inputStream().buffered().use { raw ->
            ZstdInputStream(raw).use { z -> dest.outputStream().use { z.copyTo(it, 1 shl 16) } }
        }
    }

    fun decompressToBytes(src: File): ByteArray =
        src.inputStream().buffered().use { raw -> ZstdInputStream(raw).use(InputStream::readBytes) }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    // ------------------------------------------------------------------ digests (3.5)

    fun tableDigest(db: SQLiteDatabase, t: DictTable): String {
        val md = MessageDigest.getInstance("SHA-256")
        val cols = t.columns.joinToString(", ") { "\"$it\"" }
        val order = t.pk.joinToString(", ") { "\"$it\"" }
        val eight = ByteBuffer.allocate(8)
        val four = ByteBuffer.allocate(4)
        db.rawQuery("SELECT $cols FROM \"${t.name}\" ORDER BY $order", null).use { c ->
            while (c.moveToNext()) {
                for (i in t.columns.indices) {
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> md.update(0)
                        Cursor.FIELD_TYPE_INTEGER -> {
                            md.update(1)
                            eight.clear()
                            eight.putLong(c.getLong(i))
                            md.update(eight.array())
                        }
                        Cursor.FIELD_TYPE_STRING -> {
                            val b = c.getString(i).toByteArray(Charsets.UTF_8)
                            md.update(2)
                            four.clear()
                            four.putInt(b.size)
                            md.update(four.array())
                            md.update(b)
                        }
                        else -> throw PackageException("${t.name}.${t.columns[i]}: real or blob value")
                    }
                }
            }
        }
        return md.digest().toHex()
    }

    fun rowCount(db: SQLiteDatabase, t: String): Long = db.pragma("SELECT count(*) FROM \"$t\"") { it.getLong(0) }

    fun foreignKeyViolations(db: SQLiteDatabase): Boolean = db.rawQuery("PRAGMA foreign_key_check", null).use { it.count > 0 }

    /** Checks of 5.7 for a freshly decompressed full package; also normalises the file to rollback-journal mode. */
    fun verifyFull(file: File, releaseId: Long, scope: String, formatVersion: Int) {
        val db = try {
            openWritable(file)
        } catch (e: Exception) {
            throw PackageException("not a SQLite file: ${e.message}")
        }
        try {
            db.pragma("PRAGMA journal_mode=DELETE") { }
            val info = try {
                info(db)
            } catch (e: Exception) {
                throw PackageException("not a dictionary package: ${e.message}")
            } ?: throw PackageException("dict_release is not exactly one row")
            if (info.userVersion != formatVersion) throw PackageException("user_version ${info.userVersion} != $formatVersion")
            if (info.releaseId != releaseId || info.scope != scope) {
                throw PackageException("package is release ${info.releaseId}/${info.scope}, expected $releaseId/$scope")
            }
            if (foreignKeyViolations(db)) throw PackageException("foreign_key_check failed")
        } finally {
            db.close()
        }
        for (s in SIDE) File(file.path + s).delete()
    }

    // ------------------------------------------------------------------ diff (3.4)

    /**
     * Applies the diff JSON [diffBytes] to [file] (a copy of the installed file). Throws [PackageException] after a
     * rollback on any failed check; the file is then unusable for this diff.
     */
    fun applyDiff(file: File, diffBytes: ByteArray, artifactScope: String, artifactFrom: Long, artifactTo: Long) {
        val doc = try {
            JSONObject(String(diffBytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw PackageException("diff is not JSON: ${e.message}")
        }
        // Step 1
        if (doc.optString("format") != DictSchema.DIFF_FORMAT) throw PackageException("wrong diff format")
        if (doc.optInt("format_version", -1) != DictSchema.DIFF_VERSION) throw PackageException("unsupported diff version")
        val from = doc.optLong("from_release", -1)
        val to = doc.optLong("to_release", -1)
        val scope = doc.optString("scope")
        if (from != artifactFrom || to != artifactTo || scope != artifactScope) {
            throw PackageException("diff envelope does not match artifact")
        }
        val tables = doc.optJSONObject("tables") ?: throw PackageException("tables missing")
        val counts = doc.optJSONObject("row_counts") ?: throw PackageException("row_counts missing")
        val digests = doc.optJSONObject("table_digests") ?: throw PackageException("table_digests missing")
        val names = DictSchema.tables.map { it.name }.toSet()
        for (o in listOf(tables, counts, digests)) {
            if (o.keys().asSequence().toSet() != names) throw PackageException("table set is not exactly the 11 tables")
        }
        for (t in DictSchema.tables) {
            val td = tables.optJSONObject(t.name) ?: throw PackageException("${t.name}: not an object")
            if (strings(td.optJSONArray("columns")) != t.columns || strings(td.optJSONArray("pk")) != t.pk) {
                throw PackageException("${t.name}: columns/pk differ from the device schema")
            }
            if (td.optJSONArray("upsert") == null || td.optJSONArray("delete") == null) {
                throw PackageException("${t.name}: upsert/delete missing")
            }
        }
        val db = openWritable(file)
        try {
            db.pragma("PRAGMA journal_mode=DELETE") { }
            db.execSQL("PRAGMA foreign_keys = OFF")
            // Step 2
            val info = info(db) ?: throw PackageException("dict_release is not exactly one row")
            if (info.userVersion != DictSchema.USER_VERSION) throw PackageException("user_version ${info.userVersion}")
            if (info.releaseId != from || info.scope != scope) throw PackageException("installed file is not the diff base")
            db.beginTransaction()
            try {
                for (t in DictSchema.tables.reversed()) {
                    val del = tables.getJSONObject(t.name).getJSONArray("delete")
                    val sql = "DELETE FROM \"${t.name}\" WHERE " + t.pk.joinToString(" AND ") { "\"$it\" = ?" }
                    db.compileStatement(sql).use { st ->
                        for (i in 0 until del.length()) {
                            st.clearBindings()
                            bindAll(st, del.getJSONArray(i), t.pk.size, t.name)
                            st.executeUpdateDelete()
                        }
                    }
                }
                for (t in DictSchema.tables) {
                    val up = tables.getJSONObject(t.name).getJSONArray("upsert")
                    val sql = "INSERT OR REPLACE INTO \"${t.name}\" (" + t.columns.joinToString(", ") { "\"$it\"" } +
                        ") VALUES (" + t.columns.joinToString(", ") { "?" } + ")"
                    db.compileStatement(sql).use { st ->
                        for (i in 0 until up.length()) {
                            st.clearBindings()
                            bindAll(st, up.getJSONArray(i), t.columns.size, t.name)
                            st.executeInsert()
                        }
                    }
                }
                for (t in DictSchema.tables) {
                    val n = rowCount(db, t.name)
                    if (n != counts.optLong(t.name, -1)) throw PackageException("${t.name}: row count $n != ${counts.opt(t.name)}")
                }
                for (t in DictSchema.tables) {
                    if (tableDigest(db, t) != digests.optString(t.name)) throw PackageException("${t.name}: digest mismatch")
                }
                val after = info(db)
                if (after == null || after.releaseId != to || after.scope != scope) {
                    throw PackageException("dict_release after apply is wrong")
                }
                if (foreignKeyViolations(db)) throw PackageException("foreign_key_check failed")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
        for (s in SIDE) File(file.path + s).delete()
    }

    private fun strings(a: JSONArray?): List<String>? = a?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }

    private fun bindAll(st: SQLiteStatement, row: JSONArray, expected: Int, table: String) {
        if (row.length() != expected) throw PackageException("$table: row has ${row.length()} values, expected $expected")
        for (i in 0 until expected) {
            when (val v = row.get(i)) {
                JSONObject.NULL -> st.bindNull(i + 1)
                is Int -> st.bindLong(i + 1, v.toLong())
                is Long -> st.bindLong(i + 1, v)
                is String -> st.bindString(i + 1, v)
                else -> throw PackageException("$table: unsupported JSON value ${v.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------------ durable swap (5.8)

    fun fsync(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
    }

    fun fsyncDir(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Some file systems refuse to open a directory; the rename itself is still atomic.
        }
    }

    fun atomicReplace(staging: File, target: File) {
        fsync(staging)
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        fsyncDir(target.absoluteFile.parentFile ?: File("."))
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
