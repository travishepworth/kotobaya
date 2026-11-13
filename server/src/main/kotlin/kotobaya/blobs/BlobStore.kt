package kotobaya.blobs

import io.minio.CopyObjectArgs
import io.minio.CopySource
import io.minio.GetObjectArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** The object-store operations the blobs API needs. Blocking; callers run on the IO dispatcher. */
interface BlobStore {
    /** Streams [input] to [key]; the size is unknown up front. */
    fun put(key: String, input: InputStream, contentType: String)

    /** Server-side copy. */
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
        client.putObject(
            PutObjectArgs.builder().bucket(bucket).`object`(key).contentType(contentType)
                .stream(input, -1, PART_SIZE).build(),
        )
    }

    override fun copy(from: String, to: String) {
        client.copyObject(
            CopyObjectArgs.builder().bucket(bucket).`object`(to)
                .source(CopySource.builder().bucket(bucket).`object`(from).build()).build(),
        )
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
