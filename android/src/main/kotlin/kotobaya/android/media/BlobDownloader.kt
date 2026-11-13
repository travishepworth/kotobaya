package kotobaya.android.media

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface DownloadResult {
    data class Stored(val size: Long, val contentType: String?) : DownloadResult

    /** Server returned 404. */
    data object NotFound : DownloadResult

    /** The bytes never matched the hash after all attempts; the server copy is corrupt. */
    data object HashMismatch : DownloadResult

    /** Size does not fit the remaining budget. */
    data object OverBudget : DownloadResult

    /** Transient (network, 5xx, auth); stays wanted and is retried by a later run. */
    data class Failed(val reason: String) : DownloadResult
}

/** Downloads `GET /blobs/{hash}` with Range resume into `<hash>.part`, verifies SHA-256, then atomically publishes. */
class BlobDownloader(
    private val store: BlobStore,
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val http: OkHttpClient,
    private val maxAttempts: Int = 3,
) {
    private sealed interface Fetched {
        data class Done(val size: Long, val contentType: String?) : Fetched
        data object Retry : Fetched
        data class Final(val result: DownloadResult) : Fetched
    }

    fun download(hash: String, remainingBudget: Long): DownloadResult {
        val base = baseUrl()?.trimEnd('/') ?: return DownloadResult.Failed("no server")
        var attempts = 0
        var resets = 0
        while (attempts < maxAttempts) {
            when (val r = fetch(base, hash, remainingBudget)) {
                is Fetched.Done -> {
                    val part = store.partFile(hash)
                    if (sha256(part) == hash) {
                        store.publish(hash, part)
                        return DownloadResult.Stored(r.size, r.contentType)
                    }
                    part.delete()
                    attempts++
                }
                is Fetched.Retry -> if (++resets > 3) return DownloadResult.Failed("range")
                is Fetched.Final -> return r.result
            }
        }
        return DownloadResult.HashMismatch
    }

    private fun fetch(base: String, hash: String, remaining: Long): Fetched {
        val part = store.partFile(hash)
        val have = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url("$base/blobs/$hash").apply {
            token()?.let { header("Authorization", "Bearer $it") }
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> return Fetched.Final(DownloadResult.NotFound)
                    resp.code == 416 -> {
                        // Our partial is not a usable prefix: restart from zero.
                        part.delete()
                        return Fetched.Retry
                    }
                    !resp.isSuccessful -> return Fetched.Final(DownloadResult.Failed("http ${resp.code}"))
                }
                val body = resp.body ?: return Fetched.Final(DownloadResult.Failed("empty body"))
                val resume = resp.code == 206 && have > 0
                val len = body.contentLength()
                val total = if (len >= 0) len + (if (resume) have else 0L) else -1L
                if (total > remaining) return Fetched.Final(DownloadResult.OverBudget)
                RandomAccessFile(part, "rw").use { raf ->
                    if (resume) raf.seek(have) else raf.setLength(0)
                    val buf = ByteArray(16 * 1024)
                    body.byteStream().use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            if (raf.length() > remaining) {
                                part.delete()
                                return Fetched.Final(DownloadResult.OverBudget)
                            }
                        }
                    }
                }
                return Fetched.Done(part.length(), resp.header("Content-Type"))
            }
        } catch (e: IOException) {
            return Fetched.Final(DownloadResult.Failed(e.javaClass.simpleName)) // part kept for resume
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
