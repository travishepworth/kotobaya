package kotobaya.blobs

import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** F-14: S3BlobStore against the same SeaweedFS image compose/helm ship (the in-memory store hid the Content-MD5 failure). */
@Testcontainers
class S3BlobStoreSeaweedFsTest {
    companion object {
        @Container
        @JvmStatic
        val seaweed: GenericContainer<*> = GenericContainer(DockerImageName.parse("chrislusf/seaweedfs:3.80"))
            .withCommand("server", "-s3", "-dir=/data")
            .withExposedPorts(8333, 9333)
            .withTmpFs(mapOf("/data" to "rw")) // the image declares VOLUME /data: tmpfs avoids an anonymous volume per run (D-2)
            .waitingFor(Wait.forListeningPorts(8333, 9333))
    }

    private fun store(): S3BlobStore {
        val client = MinioClient.builder()
            .endpoint("http://${seaweed.host}:${seaweed.getMappedPort(8333)}")
            .credentials("kotobaya", "kotobaya-dev-secret")
            .build()
        val bucket = "kotobaya-test"
        var tries = 0
        while (runCatching { client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()) }.isFailure && ++tries < 30) {
            Thread.sleep(1000) // the S3 gateway accepts connections slightly before it serves
        }
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
        }
        return S3BlobStore(client, bucket)
    }

    @Test
    fun smallAndMultipartRoundTrip() {
        val s = store()
        for ((key, size) in listOf("small" to 1000, "big" to 20 * 1024 * 1024 + 123)) {
            val data = Random(size).nextBytes(size)
            s.put(key, data.inputStream(), "application/octet-stream")
            assertEquals(size.toLong(), s.size(key))
            assertContentEquals(data, s.get(key, 0, null).use { it.readBytes() })
        }
        s.copy("small", "small-copy")
        assertEquals(1000L, s.size("small-copy"))
        s.delete("small-copy")
        assertNull(s.size("small-copy"))
    }
}
