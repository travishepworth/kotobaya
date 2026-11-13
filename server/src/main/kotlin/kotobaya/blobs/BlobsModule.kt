package kotobaya.blobs

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.Limits
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.formatInstant
import kotobaya.server.json
import kotobaya.server.respondJson
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

private val HASH_RE = Regex("^[0-9a-f]{64}$")
private val UPLOAD_KINDS = setOf("audio", "image", "upload")

/**
 * Blobs (T411a): `putBlob`, `getBlob`, `queryBlobs`. Objects are keyed by their hash; `app.blob` rows are inserted
 * only after the object is durable at its final key. Wiring (Main.kt, W-5): `handlers += BlobsModule(db, store).handlers`
 * with `S3BlobStore(MinioClient..., config.s3Bucket)`. Scopes are enforced by the framework from the spec.
 */
class BlobsModule(
    private val db: Database,
    private val store: BlobStore,
    private val maxBlobBytes: Long = Limits.MAX_BLOB_BYTES,
) {
    val handlers: Map<String, OperationHandler> = mapOf(
        "putBlob" to { put() },
        "getBlob" to { get() },
        "queryBlobs" to { query() },
    )

    private class Meta(val hash: String, val size: Long, val contentType: String, val kind: String, val origin: String, val createdAt: String) {
        fun toApi() = mapOf(
            "hash" to hash, "size_bytes" to size, "content_type" to contentType, "kind" to kind, "origin" to origin, "created_at" to createdAt,
        )
    }

    private fun findMeta(hash: String): Meta? = db.txBlocking { dsl ->
        dsl.fetchOptional(
            "select hash, size_bytes, content_type, kind, origin, created_at from app.blob where hash = ?", hash,
        ).map {
            Meta(
                it.get(0, String::class.java), it.get(1, Long::class.javaObjectType), it.get(2, String::class.java),
                it.get(3, String::class.java), it.get(4, String::class.java),
                formatInstant(it.get(5, java.time.OffsetDateTime::class.java).toInstant()),
            )
        }.orElse(null)
    }

    private fun ApplicationCall.hashParam(): String {
        val h = parameters["hash"] ?: ""
        if (!HASH_RE.matches(h)) throw validation("/hash", "A lowercase hex SHA-256.")
        return h
    }

    private fun validation(pointer: String, detail: String) = ApiException(
        HttpStatusCode.UnprocessableEntity, "validation_failed", "Validation failed", "$pointer: $detail",
        mapOf("errors" to listOf(mapOf("pointer" to pointer, "code" to "invalid_value", "detail" to detail))),
    )

    private fun tooLarge() = ApiException(
        HttpStatusCode.PayloadTooLarge, "payload_too_large", "Payload too large", "Blobs may be at most $maxBlobBytes bytes.",
        mapOf("limit" to "max_blob_bytes"),
    )

    // ------------------------------------------------------------------ PUT

    private class Overflow : IOException("blob too large")

    /** Hashes and counts what passes through; fails once more than [limit] bytes were read. */
    private class Metered(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var count = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) account(byteArrayOf(b.toByte()), 0, 1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) account(b, off, n)
            return n
        }

        private fun account(b: ByteArray, off: Int, n: Int) {
            count += n
            if (count > limit) throw Overflow()
            digest.update(b, off, n)
        }

        override fun skip(n: Long): Long = 0
        override fun markSupported(): Boolean = false
    }

    private suspend fun ApplicationCall.put() {
        val hash = hashParam()
        val kind = request.queryParameters["kind"] ?: throw validation("/kind", "kind is required.")
        if (kind !in UPLOAD_KINDS) throw validation("/kind", "kind must be one of audio, image, upload.")
        val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (declared != null && declared > maxBlobBytes) throw tooLarge()
        val contentType = request.header(HttpHeaders.ContentType)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

        findMeta(hash)?.let {
            respondJson(it.toApi(), HttpStatusCode.OK)
            return
        }

        val body = receiveStream()
        val tmpKey = "tmp/${UUID.randomUUID()}"
        val (meta, created) = withContext(Dispatchers.IO) {
            val metered = Metered(body, maxBlobBytes)
            try {
                store.put(tmpKey, metered, contentType)
            } catch (e: Exception) {
                runCatching { store.delete(tmpKey) }
                if (metered.count > maxBlobBytes || generateSequence(e as Throwable) { it.cause }.any { it is Overflow }) throw tooLarge()
                throw e
            }
            val actual = metered.digest.digest().joinToString("") { "%02x".format(it) }
            try {
                if (actual != hash) {
                    throw ApiException(
                        HttpStatusCode.UnprocessableEntity, "hash_mismatch", "Hash mismatch",
                        "The uploaded bytes hash to $actual, not $hash.",
                    )
                }
                if (store.size(hash) != metered.count) store.copy(tmpKey, hash)
            } finally {
                runCatching { store.delete(tmpKey) }
            }
            // The object is durable; now the row. A concurrent identical upload wins the insert and we report 200.
            val inserted = db.txBlocking { dsl ->
                dsl.execute(
                    "insert into app.blob (hash, size_bytes, content_type, kind, origin) values (?, ?, ?, ?, 'upload') on conflict (hash) do nothing",
                    hash, metered.count, contentType, kind,
                ) == 1
            }
            findMeta(hash) to inserted
        }
        respondJson((meta ?: throw Problems.internal()).toApi(), if (created) HttpStatusCode.Created else HttpStatusCode.OK)
    }

    // ------------------------------------------------------------------ GET

    private sealed interface Range {
        data class Span(val start: Long, val end: Long) : Range // inclusive
        data object Unsatisfiable : Range
        data object Ignore : Range
    }

    private fun parseRange(header: String?, size: Long): Range {
        if (header == null) return Range.Ignore
        val spec = header.trim()
        if (!spec.startsWith("bytes=") || spec.contains(',')) return Range.Ignore
        val parts = spec.removePrefix("bytes=").split('-', limit = 2)
        if (parts.size != 2) return Range.Ignore
        val a = parts[0].trim()
        val b = parts[1].trim()
        if (a.isEmpty()) {
            val n = b.toLongOrNull()?.takeIf { it >= 0 } ?: return Range.Ignore
            if (n == 0L || size == 0L) return Range.Unsatisfiable
            return Range.Span(maxOf(0, size - n), size - 1)
        }
        val start = a.toLongOrNull()?.takeIf { it >= 0 } ?: return Range.Ignore
        val end = if (b.isEmpty()) size - 1 else b.toLongOrNull()?.takeIf { it >= start } ?: return Range.Ignore
        if (start >= size) return Range.Unsatisfiable
        return Range.Span(start, minOf(end, size - 1))
    }

    private fun etagMatches(header: String, hash: String): Boolean =
        header.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == "\"$hash\"" }

    private suspend fun ApplicationCall.get() {
        val hash = hashParam()
        val meta = findMeta(hash) ?: throw Problems.notFound("No blob $hash.")
        response.header(HttpHeaders.ETag, "\"$hash\"")
        response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
        response.header(HttpHeaders.AcceptRanges, "bytes")
        val inm = request.header(HttpHeaders.IfNoneMatch)
        if (inm != null && etagMatches(inm, hash)) {
            respond(HttpStatusCode.NotModified)
            return
        }
        val type = runCatching { ContentType.parse(meta.contentType) }.getOrDefault(ContentType.Application.OctetStream)
        val outStatus: HttpStatusCode
        val offset: Long
        val length: Long
        when (val r = parseRange(request.header(HttpHeaders.Range), meta.size)) {
            Range.Ignore -> {
                outStatus = HttpStatusCode.OK
                offset = 0
                length = meta.size
            }
            Range.Unsatisfiable -> {
                response.header(HttpHeaders.ContentRange, "bytes */${meta.size}")
                throw ApiException(HttpStatusCode.RequestedRangeNotSatisfiable, "range_not_satisfiable", "Range not satisfiable")
            }
            is Range.Span -> {
                response.header(HttpHeaders.ContentRange, "bytes ${r.start}-${r.end}/${meta.size}")
                outStatus = HttpStatusCode.PartialContent
                offset = r.start
                length = r.end - r.start + 1
            }
        }
        val stream = withContext(Dispatchers.IO) {
            if (store.size(hash) == null) throw Problems.unavailable("The object store has no object for $hash.")
            store.get(hash, offset, length)
        }
        respond(
            object : OutgoingContent.ReadChannelContent() {
                override val contentType: ContentType = type
                override val contentLength: Long = length
                override val status: HttpStatusCode = outStatus
                override fun readFrom(): ByteReadChannel = stream.toByteReadChannel()
            },
        )
    }

    // ---------------------------------------------------------------- query

    private suspend fun ApplicationCall.query() {
        val body = try {
            json.readTree(receiveStream())
        } catch (e: Exception) {
            throw Problems.invalidRequest("The body is not valid JSON.")
        }
        val hashes = body?.get("hashes")
        if (body !is ObjectNode || body.size() != 1 || hashes == null || !hashes.isArray) {
            throw validation("/hashes", "Exactly one property, hashes, an array.")
        }
        if (hashes.size() !in 1..1000) throw validation("/hashes", "1 to 1000 hashes.")
        val distinct = sortedSetOf<String>()
        for (n in hashes) {
            if (!n.isTextual || !HASH_RE.matches(n.asText())) throw validation("/hashes", "Every entry must be a lowercase hex SHA-256.")
            distinct += n.asText()
        }
        val present = db.tx { dsl ->
            dsl.fetch("select hash from app.blob where hash = any(?::text[])", distinct.toTypedArray()).map { it.get(0, String::class.java) }.toSet()
        }
        respondJson(mapOf("present" to distinct.filter { it in present }, "missing" to distinct.filter { it !in present }))
    }
}
