package kotobaya.android.media

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val HASH = Regex("^[0-9a-f]{64}$")

fun isBlobHash(s: String): Boolean = HASH.matches(s)

/** Cached bytes per reason, plus the dictionary (T302) for the Settings > Offline storage breakdown. */
data class StorageBreakdown(
    val cardAudioBytes: Long,
    val cardAudioCount: Int,
    val mediaBytes: Long,
    val coverBytes: Long,
    val dictionaryBytes: Long,
) {
    val totalBytes: Long get() = cardAudioBytes + mediaBytes + coverBytes + dictionaryBytes
}

/** blob_cache rows (raw SQL over the contract table, so no queries file outside this package) plus files by hash. */
class BlobStore(private val driver: SqlDriver, val dir: File) {
    init { dir.mkdirs() }

    fun file(hash: String): File {
        require(isBlobHash(hash)) { "bad blob hash" }
        return File(dir, hash)
    }

    fun partFile(hash: String): File {
        require(isBlobHash(hash)) { "bad blob hash" }
        return File(dir, "$hash.part")
    }

    private fun exec(sql: String, vararg args: Any?) {
        driver.execute(null, sql, args.size) {
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> bindLong(i, null)
                    is Long -> bindLong(i, a)
                    is String -> bindString(i, a)
                    else -> error("unsupported arg")
                }
            }
        }
    }

    fun entries(): List<CacheEntry> = driver.executeQuery(
        null,
        "SELECT hash, size_bytes, state, reason, requested_at, last_used_at FROM blob_cache",
        { c ->
            val out = mutableListOf<CacheEntry>()
            while (c.next().value) {
                out += CacheEntry(
                    c.getString(0)!!, c.getLong(1), BlobState.of(c.getString(2)!!),
                    BlobReason.of(c.getString(3)!!), c.getLong(4)!!, c.getLong(5),
                )
            }
            QueryResult.Value(out)
        },
        0,
    ).value

    /** Records interest in [hash]; an already cached blob stays cached. */
    fun want(hash: String, reason: BlobReason, nowMs: Long) = exec(
        "INSERT INTO blob_cache (hash, state, reason, requested_at) VALUES (?, 'wanted', ?, ?) " +
            "ON CONFLICT(hash) DO UPDATE SET state = CASE WHEN state = 'cached' THEN 'cached' ELSE 'wanted' END",
        hash, reason.db, nowMs,
    )

    fun markCached(hash: String, size: Long, contentType: String?, nowMs: Long) = exec(
        "UPDATE blob_cache SET state = 'cached', size_bytes = ?, content_type = ?, " +
            "last_used_at = COALESCE(last_used_at, ?) WHERE hash = ?",
        size, contentType, nowMs, hash,
    )

    fun markMissing(hash: String) = exec("UPDATE blob_cache SET state = 'missing' WHERE hash = ?", hash)

    /** LRU touch: call when the audio is played. */
    fun touch(hash: String, nowMs: Long) =
        exec("UPDATE blob_cache SET last_used_at = ? WHERE hash = ? AND state = 'cached'", nowMs, hash)

    fun evict(hash: String) {
        file(hash).delete()
        partFile(hash).delete()
        exec("DELETE FROM blob_cache WHERE hash = ?", hash)
    }

    /** Atomically publishes a verified temp file as the blob. */
    fun publish(hash: String, verified: File) {
        Files.move(
            verified.toPath(), file(hash).toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /** Deletes every cached blob and its row (wanted/missing rows are kept). */
    fun clearCache() {
        for (e in entries()) if (e.state == BlobState.CACHED) file(e.hash).delete()
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        exec("DELETE FROM blob_cache WHERE state = 'cached'")
    }

    fun breakdown(dictionaryBytes: Long): StorageBreakdown {
        val cached = entries().filter { it.state == BlobState.CACHED }
        fun bytes(r: BlobReason) = cached.filter { it.reason == r }.sumOf { it.sizeBytes ?: 0L }
        return StorageBreakdown(
            cardAudioBytes = bytes(BlobReason.CARD),
            cardAudioCount = cached.count { it.reason == BlobReason.CARD },
            mediaBytes = bytes(BlobReason.MEDIA),
            coverBytes = bytes(BlobReason.COVER),
            dictionaryBytes = dictionaryBytes,
        )
    }
}
