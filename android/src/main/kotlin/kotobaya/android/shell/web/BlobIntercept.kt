package kotobaya.android.shell.web

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotobaya.android.media.BlobReason
import kotobaya.android.media.BlobStore
import kotobaya.android.media.DownloadResult
import kotobaya.android.media.isBlobHash
import kotobaya.android.shell.api.Problem
import kotobaya.android.shell.api.Problems

/** One answer of the intercept, framework-free; [ShellWebClient] turns it into a `WebResourceResponse`. */
class InterceptAnswer(
    val status: Int,
    val reason: String,
    val mimeType: String,
    val encoding: String?,
    val headers: Map<String, String>,
    val body: InputStream,
)

/** `blob_cache.content_type` of a hash (the contract table; read-only). */
fun interface BlobContentTypes {
    fun contentType(hash: String): String?

    companion object {
        fun over(driver: SqlDriver): BlobContentTypes = BlobContentTypes { hash ->
            driver.executeQuery(
                null,
                "SELECT content_type FROM blob_cache WHERE hash = ?",
                { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
                1,
            ) { bindString(0, hash) }.value
        }
    }
}

/**
 * `shouldInterceptRequest` for everything under `/api/` and `/shell/` on the shell origin (SHELL.md 2.7):
 * `GET /api/v1/blobs/{hash}` from the [BlobStore] (downloaded on a miss while online), every other such
 * request `404 not_found`. Blocking; WebView calls it on a background thread.
 */
class BlobIntercept(
    private val store: BlobStore,
    private val contentTypes: BlobContentTypes,
    private val isPaired: () -> Boolean,
    private val isOnline: () -> Boolean,
    private val download: (hash: String) -> DownloadResult,
    private val newRequestId: () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<String, Any>()

    /** The answer for [method] [path] (URL path, no query), or null when the path is not under `/api/` or `/shell/`. */
    fun answer(method: String, path: String): InterceptAnswer? {
        if (!ShellOrigin.isApiPath(path)) return null
        val hash = path.removePrefix(BLOB_PREFIX).takeIf { method.equals("GET", ignoreCase = true) && path.startsWith(BLOB_PREFIX) }
            ?: return problem(path, Problems.notFound("Only blobs are loaded by URL in the app."))
        if (!isBlobHash(hash)) return problem(path, Problems.notFound("Not a blob hash."))
        if (!isPaired()) return problem(path, Problems.unauthenticated())
        val file = store.file(hash)
        if (!file.isFile) {
            if (!isOnline()) return problem(path, Problems.notFound("Blob not on this device."))
            val stored = synchronized(locks.getOrPut(hash) { Any() }) {
                file.isFile || fetch(hash)
            }
            if (!stored) return problem(path, Problems.notFound("Blob could not be downloaded."))
        }
        val bytes = try {
            file.inputStream()
        } catch (e: java.io.IOException) {
            return problem(path, Problems.notFound("Blob not on this device."))
        }
        runCatching { store.touch(hash, nowMs()) } // played: LRU last-used time
        val mime = contentTypes.contentType(hash)?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: OCTET_STREAM
        return InterceptAnswer(
            status = 200,
            reason = "OK",
            mimeType = mime,
            encoding = null,
            headers = mapOf(
                "Content-Length" to file.length().toString(),
                "Cache-Control" to "private, max-age=31536000, immutable",
            ),
            body = bytes,
        )
    }

    private fun fetch(hash: String): Boolean {
        val now = nowMs()
        return try {
            store.want(hash, BlobReason.CARD, now)
            when (val r = download(hash)) {
                is DownloadResult.Stored -> {
                    store.markCached(hash, r.size, r.contentType, now)
                    true
                }
                DownloadResult.NotFound, DownloadResult.HashMismatch -> {
                    store.markMissing(hash)
                    false
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun problem(path: String, p: Problem): InterceptAnswer {
        val r = p.toResponse(path, newRequestId())
        return InterceptAnswer(
            status = r.status,
            reason = REASONS[r.status] ?: "Error",
            mimeType = "application/problem+json",
            encoding = "utf-8",
            headers = mapOf("Cache-Control" to "no-store"),
            body = ByteArrayInputStream((r.body ?: "").toByteArray(Charsets.UTF_8)),
        )
    }

    companion object {
        const val BLOB_PREFIX: String = "/api/v1/blobs/"
        private const val OCTET_STREAM = "application/octet-stream"
        private val REASONS = mapOf(401 to "Unauthorized", 404 to "Not Found")
    }
}
