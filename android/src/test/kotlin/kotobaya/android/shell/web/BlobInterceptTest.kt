package kotobaya.android.shell.web

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotobaya.android.db.createAppDatabase
import kotobaya.android.media.BlobReason
import kotobaya.android.media.BlobStore
import kotobaya.android.media.DownloadResult
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.DefaultCanonicalJson

/** SHELL-A3 acceptance: `GET /api/v1/blobs/{hash}` through `shouldInterceptRequest` (SHELL.md 2.7). */
class BlobInterceptTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { createAppDatabase(it) }
    private val dir: File = Files.createTempDirectory("shell-blobs").toFile()
    private val store = BlobStore(driver, dir)
    private var paired = true
    private var online = false
    private var now = 1_000L
    private val downloads = ArrayList<String>()
    private var downloadResult: (String) -> DownloadResult = { DownloadResult.Failed("offline") }

    private val intercept = BlobIntercept(
        store = store,
        contentTypes = BlobContentTypes.over(driver),
        isPaired = { paired },
        isOnline = { online },
        download = { h -> downloads.add(h); downloadResult(h) },
        newRequestId = { "rid" },
        nowMs = { now },
    )

    @AfterTest
    fun cleanUp() {
        driver.close()
        dir.deleteRecursively()
    }

    private val bytes = "ID3 fake mp3 bytes".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun path(h: String = hash) = "/api/v1/blobs/$h"

    private fun cache(h: String, data: ByteArray, type: String?) {
        store.want(h, BlobReason.CARD, 1)
        store.file(h).also { store.dir.mkdirs() }.writeBytes(data)
        store.markCached(h, data.size.toLong(), type, 1)
    }

    private fun lastUsed(h: String): Long? = driver.executeQuery(
        null, "SELECT last_used_at FROM blob_cache WHERE hash = ?",
        { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) }, 1,
    ) { bindString(0, h) }.value

    private fun problemCode(a: InterceptAnswer): String {
        assertEquals("application/problem+json", a.mimeType)
        val o = DefaultCanonicalJson.decode(a.body.readBytes().decodeToString()).value as JsonObject
        return (o.members["code"] as JsonString).value
    }

    @Test
    fun cachedHashIs200WithBytesAndContentType() {
        cache(hash, bytes, "audio/mpeg")
        now = 5_000
        val a = intercept.answer("GET", path())!!
        assertEquals(200, a.status)
        assertEquals("audio/mpeg", a.mimeType)
        assertContentEquals(bytes, a.body.readBytes())
        assertEquals(bytes.size.toString(), a.headers["Content-Length"])
        assertEquals("private, max-age=31536000, immutable", a.headers["Cache-Control"])
        assertEquals(5_000L, lastUsed(hash), "played: last-used time updated")
        assertEquals(emptyList(), downloads, "a cached blob is never downloaded")
    }

    @Test
    fun cachedWithoutStoredTypeIsOctetStream() {
        cache(hash, bytes, null)
        assertEquals("application/octet-stream", intercept.answer("GET", path())!!.mimeType)
    }

    @Test
    fun uncachedAndOfflineIs404() {
        online = false
        val a = intercept.answer("GET", path())!!
        assertEquals(404, a.status)
        assertEquals("not_found", problemCode(a))
        assertEquals(emptyList(), downloads)
    }

    @Test
    fun uncachedAndOnlineIsDownloadedStoredAndServed() {
        online = true
        downloadResult = { h ->
            store.file(h).also { store.dir.mkdirs() }.writeBytes(bytes)
            DownloadResult.Stored(bytes.size.toLong(), "audio/ogg")
        }
        val a = intercept.answer("GET", path())!!
        assertEquals(200, a.status)
        assertEquals("audio/ogg", a.mimeType)
        assertContentEquals(bytes, a.body.readBytes())
        assertEquals(listOf(hash), downloads)
        // second time from the cache
        assertEquals(200, intercept.answer("GET", path())!!.status)
        assertEquals(1, downloads.size)
    }

    @Test
    fun downloadFailureIs404() {
        online = true
        downloadResult = { DownloadResult.NotFound }
        assertEquals(404, intercept.answer("GET", path())!!.status)
        downloadResult = { DownloadResult.Failed("http 500") }
        assertEquals("not_found", problemCode(intercept.answer("GET", path())!!))
    }

    @Test
    fun badHashIs404AndUnpairedIs401() {
        assertEquals(404, intercept.answer("GET", path("ABC"))!!.status)
        assertEquals(404, intercept.answer("GET", path(hash.uppercase()))!!.status)
        cache(hash, bytes, "audio/mpeg")
        paired = false
        val a = intercept.answer("GET", path())!!
        assertEquals(401, a.status)
        assertEquals("unauthenticated", problemCode(a))
    }

    @Test
    fun everyOtherApiOrShellRequestIs404AndNothingElseIsIntercepted() {
        cache(hash, bytes, "audio/mpeg")
        for ((m, p) in listOf("GET" to "/api/v1/notes", "POST" to path(), "GET" to "/shell/v1/info", "GET" to "/api/v1/blobs/")) {
            val a = intercept.answer(m, p)
            assertEquals(404, a?.status, "$m $p")
        }
        assertNull(intercept.answer("GET", "/review"))
        assertNull(intercept.answer("GET", "/_app/x.js"))
        assertTrue(downloads.isEmpty())
    }
}
