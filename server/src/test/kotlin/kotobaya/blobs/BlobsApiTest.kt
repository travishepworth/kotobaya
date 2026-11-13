package kotobaya.blobs

import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.AuthFixture
import kotobaya.auth.TokenKind
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T411a: blobs over HTTP with real tokens; S3 is the in-memory [InMemoryBlobStore] (the MinIO adapter is a thin pass-through). */
class BlobsApiTest : AuthFixture() {
    private val store = InMemoryBlobStore()
    private val bytes = ByteArray(1000) { (it * 7).toByte() }
    private val hash = sha(bytes)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun blobApp(max: Long = 10_000, block: suspend ApplicationTestBuilder.() -> Unit) =
        app(extraHandlers = BlobsModule(db, store, max).handlers, block = block)

    private fun blobRows() = db.txBlocking { (it.fetchValue("select count(*) from app.blob") as Number).toInt() }

    private suspend fun ApplicationTestBuilder.put(token: String, h: String = hash, data: ByteArray = bytes, kind: String = "audio"): HttpResponse =
        client.call(HttpMethod.Put, "/blobs/$h?kind=$kind", bearer = token) {
            contentType(ContentType.parse("audio/ogg"))
            setBody(data)
        }

    @Test
    fun uploadThenGetRoundTripsAndRepeatUploadIsIdempotent() = blobApp {
        val token = mint(TokenKind.PERSONAL, listOf("read", "write")).second
        val first = put(token)
        assertEquals(201, first.status.value, first.json().toString())
        assertEquals(hash, first.json()["hash"].asText())
        assertEquals(1000, first.json()["size_bytes"].asInt())
        assertEquals("audio", first.json()["kind"].asText())
        assertEquals("upload", first.json()["origin"].asText())
        assertEquals(setOf(hash), store.objects.keys, "temp object removed")

        val again = put(token, kind = "image")
        assertEquals(200, again.status.value)
        assertEquals("audio", again.json()["kind"].asText(), "kind is not changed")
        assertEquals(1, blobRows())

        val got = client.call(HttpMethod.Get, "/blobs/$hash", bearer = token)
        assertEquals(200, got.status.value)
        assertContentEquals(bytes, got.readRawBytes())
        assertEquals("\"$hash\"", got.headers[HttpHeaders.ETag])
        assertEquals("audio/ogg", got.headers[HttpHeaders.ContentType])
        assertEquals("1000", got.headers[HttpHeaders.ContentLength])
        assertTrue(got.headers[HttpHeaders.CacheControl]!!.contains("immutable"))
    }

    @Test
    fun hashMismatchIs422AndLeavesNoRowAndNoObject() = blobApp {
        val token = mint(TokenKind.PERSONAL, listOf("write")).second
        val r = put(token, h = sha("other".toByteArray()))
        assertProblem(r, 422, "hash_mismatch")
        assertEquals(0, blobRows())
        assertTrue(store.objects.isEmpty())
        assertProblem(put(token, kind = "snapshot"), 422, "validation_failed")
    }

    @Test
    fun tooLargeBodyIs413AndNothingIsKept() = blobApp(max = 500) {
        val token = mint(TokenKind.PERSONAL, listOf("write")).second
        assertProblem(put(token), 413, "payload_too_large")
        assertEquals(0, blobRows())
        assertTrue(store.objects.isEmpty())
    }

    @Test
    fun rangeAndConditionalGetAndUnknownHash() = blobApp {
        val token = mint(TokenKind.PERSONAL, listOf("read", "write")).second
        put(token)
        val part = client.call(HttpMethod.Get, "/blobs/$hash", bearer = token) { header(HttpHeaders.Range, "bytes=10-19") }
        assertEquals(206, part.status.value)
        assertContentEquals(bytes.copyOfRange(10, 20), part.readRawBytes())
        assertEquals("bytes 10-19/1000", part.headers[HttpHeaders.ContentRange])
        val tail = client.call(HttpMethod.Get, "/blobs/$hash", bearer = token) { header(HttpHeaders.Range, "bytes=-5") }
        assertContentEquals(bytes.copyOfRange(995, 1000), tail.readRawBytes())
        val open = client.call(HttpMethod.Get, "/blobs/$hash", bearer = token) { header(HttpHeaders.Range, "bytes=990-") }
        assertContentEquals(bytes.copyOfRange(990, 1000), open.readRawBytes())
        assertEquals(416, client.call(HttpMethod.Get, "/blobs/$hash", bearer = token) { header(HttpHeaders.Range, "bytes=2000-") }.status.value)

        val nm = client.call(HttpMethod.Get, "/blobs/$hash", bearer = token) { header(HttpHeaders.IfNoneMatch, "\"$hash\"") }
        assertEquals(304, nm.status.value)
        assertEquals(0, nm.readRawBytes().size)
        assertEquals(200, client.call(HttpMethod.Get, "/blobs/$hash", bearer = token) { header(HttpHeaders.IfNoneMatch, "\"x\"") }.status.value)

        assertProblem(client.call(HttpMethod.Get, "/blobs/${sha("none".toByteArray())}", bearer = token), 404, "not_found")
    }

    @Test
    fun aReadOnlyTokenCannotPutAndAnImportTokenCan() = blobApp {
        val read = mint(TokenKind.PERSONAL, listOf("read")).second
        assertProblem(put(read), 403, "insufficient_scope")
        assertEquals(0, blobRows())
        val import = mint(TokenKind.PERSONAL, listOf("import")).second
        assertEquals(201, put(import, kind = "upload").status.value)
        val writeOnly = mint(TokenKind.PERSONAL, listOf("write")).second
        assertProblem(client.call(HttpMethod.Get, "/blobs/$hash", bearer = writeOnly), 403, "insufficient_scope")
    }

    @Test
    fun queryPartitionsDistinctHashesSorted() = blobApp {
        val token = mint(TokenKind.PERSONAL, listOf("read", "write")).second
        put(token)
        val absent = sha("absent".toByteArray())
        val r = client.call(HttpMethod.Post, "/blobs/query", bearer = token, body = mapOf("hashes" to listOf(absent, hash, hash)))
        assertEquals(200, r.status.value)
        assertEquals(listOf(hash), r.json()["present"].map { it.asText() })
        assertEquals(listOf(absent), r.json()["missing"].map { it.asText() })
        assertProblem(client.call(HttpMethod.Post, "/blobs/query", bearer = token, body = mapOf("hashes" to listOf("nope"))), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Post, "/blobs/query", bearer = token, body = mapOf("hashes" to emptyList<String>())), 422, "validation_failed")
    }
}
