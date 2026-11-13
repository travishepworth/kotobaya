package kotobaya.blobs

import io.minio.GetObjectArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** The object-store operations the blobs API needs. Blocking; callers run on the IO dispatcher. */
interface BlobStore {
    /** Streams [input] to [key]; the size is unknown up front (spooled to a temp file). */
    fun put(key: String, input: InputStream, contentType: String)

    /** Copies [from] to [to] (content type not preserved; blob content types are not served from the store). */
    fun copy(from: String, to: String)

    fun delete(key: String)

    /** Size of the object, or null when it does not exist. */
    fun size(key: String): Long?

    /** Bytes `[offset, offset + length)` (the whole object when [length] is null). */
    fun get(key: String, offset: Long = 0, length: Long? = null): InputStream
}

/** Plain S3 API (ADR-003): MinIO, SeaweedFS or AWS, whatever `S3_ENDPOINT` points at. The bucket is created by `BucketInit`. */
class S3BlobStore(private val client: MinioClient, private val bucket: String) : BlobStore {
    override fun put(key: String, input: InputStream, contentType: String) {
        // Unknown-length streaming makes minio-java send per-part Content-MD5 values SeaweedFS 3.80 rejects.
        // Spool to a temp file so the length is known; always delete the file.
        val tmp = Files.createTempFile("kotobaya-blob-", ".part")
        try {
            val len = Files.newOutputStream(tmp).use { input.copyTo(it) }
            Files.newInputStream(tmp).use { ins ->
                client.putObject(
                    PutObjectArgs.builder().bucket(bucket).`object`(key).contentType(contentType)
                        .stream(ins, len, PART_SIZE).build(),
                )
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun copy(from: String, to: String) {
        // Server-side CopyObject sends a Content-MD5 SeaweedFS 3.80 answers with a 500; copy through get + put instead.
        get(from, 0, null).use { put(to, it, "application/octet-stream") }
    }

    override fun delete(key: String) {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(key).build())
    }

    override fun size(key: String): Long? = try {
        client.statObject(StatObjectArgs.builder().bucket(bucket).`object`(key).build()).size()
    } catch (e: ErrorResponseException) {
        if (e.errorResponse().code() in setOf("NoSuchKey", "NoSuchObject", "NoSuchBucket")) null else throw e
    }

    override fun get(key: String, offset: Long, length: Long?): InputStream {
        val b = GetObjectArgs.builder().bucket(bucket).`object`(key)
        if (offset > 0 || length != null) b.offset(offset)
        if (length != null) b.length(length)
        return client.getObject(b.build())
    }

    private companion object {
        const val PART_SIZE = 16L * 1024 * 1024
    }
}

/** In-memory store for tests. */
class InMemoryBlobStore : BlobStore {
    val objects = ConcurrentHashMap<String, ByteArray>()

    override fun put(key: String, input: InputStream, contentType: String) {
        objects[key] = input.readBytes()
    }

    override fun copy(from: String, to: String) {
        objects[to] = objects[from] ?: error("no such object $from")
    }

    override fun delete(key: String) {
        objects.remove(key)
    }

    override fun size(key: String): Long? = objects[key]?.size?.toLong()

    override fun get(key: String, offset: Long, length: Long?): InputStream {
        val all = objects[key] ?: error("no such object $key")
        val end = if (length == null) all.size else minOf(all.size.toLong(), offset + length).toInt()
        return all.copyOfRange(offset.toInt(), end).inputStream()
    }
}
