package kotobaya.android.dict

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [DictRemote] over the public API. Blobs are streamed to disk with `Range` resume; OkHttp follows the `302` to
 * object storage and drops the Authorization header on the cross-host hop.
 */
class HttpDictRemote(
    private val api: ApiClient,
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val http: OkHttpClient = blobHttp(),
) : DictRemote {
    override suspend fun release(): DictReleaseInfo? = try {
        DictReleaseInfo.parse(api.get("/dict/release").body)
    } catch (e: ApiError.Other) {
        if (e.status == 404) null else throw e
    } catch (e: org.json.JSONException) {
        throw ApiError.BadBody(e.message ?: "release")
    }

    override suspend fun fetchBlob(hash: String, part: File, onProgress: (bytes: Long) -> Unit) = withContext(Dispatchers.IO) {
        val base = baseUrl()?.trimEnd('/') ?: throw ApiError.Unreachable("no server configured")
        val have = if (part.exists()) part.length() else 0L
        val b = Request.Builder().url("$base/blobs/$hash")
        token()?.let { b.header("Authorization", "Bearer $it") }
        if (have > 0) b.header("Range", "bytes=$have-")
        run {
            http.newCall(b.build()).execute().use { resp ->
                when {
                    resp.code == 404 -> throw BlobNotFound(hash)
                    resp.code == 416 -> {
                        // The partial file is not a prefix of the blob (or the blob changed): start over.
                        part.delete()
                        throw IOException("range not satisfiable; partial download discarded")
                    }
                    !resp.isSuccessful -> throw ApiClient.mapError(resp.code, "", null)
                }
                val append = resp.code == 206 && have > 0
                val body = resp.body ?: throw IOException("empty body")
                var done = if (append) have else 0L
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done)
                        }
                    }
                    out.fd.sync()
                }
            }
        }
    }

    companion object {
        fun blobHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}
