package kotobaya.server

import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory

/** Creates the bucket on startup if missing (compose SeaweedFS has no S3 auth config and starts empty). */
fun interface BucketInit {
    suspend fun ensureBucket()

    companion object {
        val Disabled = BucketInit { }
    }
}

class S3BucketInit(private val config: Config) : BucketInit {
    private val log = LoggerFactory.getLogger(S3BucketInit::class.java)

    private val client: MinioClient = MinioClient.builder()
        .endpoint(config.s3Endpoint)
        .credentials(config.s3AccessKey, config.s3SecretKey)
        .apply { config.s3Region?.let { region(it) } }
        .build()

    override suspend fun ensureBucket() = withContext(Dispatchers.IO) {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(config.s3Bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(config.s3Bucket).build())
            log.info("created bucket", kv("bucket", config.s3Bucket))
        }
    }
}

/** Retries until it works; the object store is not part of readiness (the API spec says /health ignores it). */
suspend fun ensureBucketWithRetry(init: BucketInit) {
    val log = LoggerFactory.getLogger(BucketInit::class.java)
    var wait = 1_000L
    while (true) {
        try {
            init.ensureBucket()
            return
        } catch (e: Exception) {
            log.warn("bucket check failed, retrying", kv("retry_in_ms", wait), e)
            delay(wait)
            wait = minOf(wait * 2, 30_000L)
        }
    }
}
