package kotobaya.android.dict

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.util.JsonReader
import android.util.JsonToken
import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

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

    /** Envelope and per-table metadata of a diff, read without its row arrays. */
    private class DiffHead {
        var format: String? = null
        var formatVersion: Long = -1
        var from: Long = -1
        var to: Long = -1
        var scope: String? = null
        val tables = mutableMapOf<String, TableHead>()
        var counts: Map<String, Long?>? = null
        var digests: Map<String, String?>? = null
    }

    private class TableHead {
        var columns: List<String>? = null
        var pk: List<String>? = null
        var hasUpsert = false
        var hasDelete = false
    }

    /**
     * Applies the decompressed diff JSON in [diffFile] to [file] (a copy of the installed file). Throws
     * [PackageException] after a rollback on any failed check; the file is then unusable for this diff.
     *
     * The diff is streamed from disk three times (validate, deletes, upserts) so the `all` scope's row arrays are
     * never held in memory at once; a DOM parse of them exhausted the 256 MB app heap on device.
     */
    fun applyDiff(file: File, diffFile: File, artifactScope: String, artifactFrom: Long, artifactTo: Long) {
        val head = jsonPass(diffFile) { readHead(it) }
        // Step 1
        if (head.format != DictSchema.DIFF_FORMAT) throw PackageException("wrong diff format")
        if (head.formatVersion != DictSchema.DIFF_VERSION.toLong()) throw PackageException("unsupported diff version")
        val from = head.from
        val to = head.to
        val scope = head.scope
        if (from != artifactFrom || to != artifactTo || scope != artifactScope) {
            throw PackageException("diff envelope does not match artifact")
        }
        val counts = head.counts ?: throw PackageException("row_counts missing")
        val digests = head.digests ?: throw PackageException("table_digests missing")
        if (head.tables.isEmpty()) throw PackageException("tables missing")
        val names = DictSchema.tables.map { it.name }.toSet()
        for (keys in listOf(head.tables.keys, counts.keys, digests.keys)) {
            if (keys != names) throw PackageException("table set is not exactly the 11 tables")
        }
        for (t in DictSchema.tables) {
            val td = head.tables.getValue(t.name)
            if (td.columns != t.columns || td.pk != t.pk) {
                throw PackageException("${t.name}: columns/pk differ from the device schema")
            }
            if (!td.hasUpsert || !td.hasDelete) throw PackageException("${t.name}: upsert/delete missing")
        }
        val byName = DictSchema.tables.associateBy { it.name }
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
                // Foreign keys are off and checked at the end, so per-table order is all that matters:
                // every delete runs before any upsert.
                jsonPass(diffFile) { r ->
                    forEachRowArray(r, "delete") { name, rows ->
                        val t = byName.getValue(name)
                        val sql = "DELETE FROM \"${t.name}\" WHERE " + t.pk.joinToString(" AND ") { "\"$it\" = ?" }
                        db.compileStatement(sql).use { st ->
                            forEachRow(rows) {
                                st.clearBindings()
                                bindAll(st, rows, t.pk.size, t.name)
                                st.executeUpdateDelete()
                            }
                        }
                    }
                }
                jsonPass(diffFile) { r ->
                    forEachRowArray(r, "upsert") { name, rows ->
                        val t = byName.getValue(name)
                        val sql = "INSERT OR REPLACE INTO \"${t.name}\" (" + t.columns.joinToString(", ") { "\"$it\"" } +
                            ") VALUES (" + t.columns.joinToString(", ") { "?" } + ")"
                        db.compileStatement(sql).use { st ->
                            forEachRow(rows) {
                                st.clearBindings()
                                bindAll(st, rows, t.columns.size, t.name)
                                st.executeInsert()
                            }
                        }
                    }
                }
                for (t in DictSchema.tables) {
                    val n = rowCount(db, t.name)
                    if (n != (counts[t.name] ?: -1L)) throw PackageException("${t.name}: row count $n != ${counts[t.name]}")
                }
                for (t in DictSchema.tables) {
                    if (tableDigest(db, t) != digests[t.name]) throw PackageException("${t.name}: digest mismatch")
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

    /** One streaming read of the diff; malformed JSON becomes a [PackageException]. */
    private fun <T> jsonPass(diffFile: File, body: (JsonReader) -> T): T =
        try {
            JsonReader(diffFile.bufferedReader(Charsets.UTF_8, 1 shl 16)).use { r ->
                val out = body(r)
                if (r.peek() != JsonToken.END_DOCUMENT) throw PackageException("diff is not JSON: trailing data")
                out
            }
        } catch (e: IOException) {
            throw PackageException("diff is not JSON: ${e.message}")
        } catch (e: IllegalStateException) {
            throw PackageException("diff is not JSON: ${e.message}")
        } catch (e: NumberFormatException) {
            throw PackageException("diff is not JSON: ${e.message}")
        }

    private fun readHead(r: JsonReader): DiffHead {
        val h = DiffHead()
        if (r.peek() != JsonToken.BEGIN_OBJECT) throw PackageException("diff is not a JSON object")
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> h.format = optString(r)
                "format_version" -> h.formatVersion = optLong(r) ?: -1
                "from_release" -> h.from = optLong(r) ?: -1
                "to_release" -> h.to = optLong(r) ?: -1
                "scope" -> h.scope = optString(r)
                "tables" -> readObject(r) { name -> h.tables[name] = readTableHead(r) }
                "row_counts" -> h.counts = mutableMapOf<String, Long?>().also { m -> readObject(r) { m[it] = optLong(r) } }
                "table_digests" -> h.digests = mutableMapOf<String, String?>().also { m -> readObject(r) { m[it] = optString(r) } }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return h
    }

    private fun readTableHead(r: JsonReader): TableHead {
        val t = TableHead()
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return t
        }
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "columns" -> t.columns = optStrings(r)
                "pk" -> t.pk = optStrings(r)
                "upsert" -> { t.hasUpsert = r.peek() == JsonToken.BEGIN_ARRAY; r.skipValue() }
                "delete" -> { t.hasDelete = r.peek() == JsonToken.BEGIN_ARRAY; r.skipValue() }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return t
    }

    /** Calls [onArray] with the reader positioned at each table's [key] array (validated by [readHead]). */
    private fun forEachRowArray(r: JsonReader, key: String, onArray: (String, JsonReader) -> Unit) {
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "tables") {
                r.skipValue()
                continue
            }
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == key) onArray(name, r) else r.skipValue()
                }
                r.endObject()
            }
            r.endObject()
        }
        r.endObject()
    }

    /** Iterates the rows of the array at the reader; [onRow] must consume exactly one row array. */
    private inline fun forEachRow(r: JsonReader, onRow: () -> Unit) {
        r.beginArray()
        while (r.hasNext()) onRow()
        r.endArray()
    }

    private inline fun readObject(r: JsonReader, onKey: (String) -> Unit) {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return
        }
        r.beginObject()
        while (r.hasNext()) onKey(r.nextName())
        r.endObject()
    }

    private fun optString(r: JsonReader): String? = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        else -> { r.skipValue(); null }
    }

    private fun optLong(r: JsonReader): Long? = when (r.peek()) {
        JsonToken.NUMBER, JsonToken.STRING -> r.nextString().toLongOrNull()
        else -> { r.skipValue(); null }
    }

    private fun optStrings(r: JsonReader): List<String>? {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return null
        }
        val out = mutableListOf<String>()
        r.beginArray()
        while (r.hasNext()) out += optString(r) ?: ""
        r.endArray()
        return out
    }

    /** Binds one row array from the reader: JSON null, integer or string only. */
    private fun bindAll(st: SQLiteStatement, r: JsonReader, expected: Int, table: String) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) throw PackageException("$table: row is not an array")
        r.beginArray()
        var i = 0
        while (r.hasNext()) {
            if (i >= expected) throw PackageException("$table: row has more than $expected values")
            when (r.peek()) {
                JsonToken.NULL -> { r.nextNull(); st.bindNull(i + 1) }
                JsonToken.NUMBER -> {
                    val lit = r.nextString()
                    val v = lit.toLongOrNull() ?: throw PackageException("$table: unsupported JSON number $lit")
                    st.bindLong(i + 1, v)
                }
                JsonToken.STRING -> st.bindString(i + 1, r.nextString())
                else -> throw PackageException("$table: unsupported JSON value ${r.peek()}")
            }
            i++
        }
        r.endArray()
        if (i != expected) throw PackageException("$table: row has $i values, expected $expected")
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
