package kotobaya.android.media

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotobaya.android.db.createAppDatabase
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioPrefetcherTest {
    private val server = MockWebServer()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { createAppDatabase(it) }
    private val dir: File = Files.createTempDirectory("blobs").toFile()
    private val store = BlobStore(driver, dir)
    private val prefs = MediaPrefs(
        object : KeyValue {
            val m = mutableMapOf<String, Any>()
            override fun int(key: String, default: Int) = m[key] as? Int ?: default
            override fun bool(key: String, default: Boolean) = m[key] as? Boolean ?: default
            override fun put(key: String, value: Int) { m[key] = value }
            override fun put(key: String, value: Boolean) { m[key] = value }
        },
    )
    private var metered = false
    private val now = 1_000_000L

    @AfterTest
    fun tearDown() { server.shutdown(); dir.deleteRecursively() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun prefetcher(vararg hashes: String): AudioPrefetcher {
        val downloader = BlobDownloader(store, { server.url("/").toString() }, { "tok" }, OkHttpClient())
        return AudioPrefetcher(store, { _, _ -> hashes.map { AudioNeed(it, now) } }, downloader, prefs, { metered }, { now })
    }

    @Test
    fun downloadsVerifiesAndCaches() {
        val data = ByteArray(1000) { it.toByte() }
        val h = sha(data)
        server.enqueue(MockResponse().setBody(Buffer().write(data)))
        val s = prefetcher(h).run()
        assertEquals(1, s.downloaded)
        assertTrue(store.file(h).readBytes().contentEquals(data))
        assertEquals(BlobState.CACHED, store.entries().single().state)
        assertEquals(1000L, store.breakdown(0).cardAudioBytes)
    }

    @Test
    fun hashMismatchRetriedThenMarkedFailed() {
        val h = sha("right".toByteArray())
        repeat(3) { server.enqueue(MockResponse().setBody("wrong")) }
        val s = prefetcher(h).run()
        assertEquals(3, server.requestCount)
        assertEquals(1, s.missing)
        assertEquals(BlobState.MISSING, store.entries().single().state)
        assertFalse(store.file(h).exists())
        assertFalse(store.partFile(h).exists())
    }

    @Test
    fun resumesAfterPartial() {
        val data = ByteArray(2000) { (it * 7).toByte() }
        val h = sha(data)
        store.partFile(h).writeBytes(data.copyOfRange(0, 800))
        var range: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                range = request.getHeader("Range")
                return MockResponse().setResponseCode(206).setBody(Buffer().write(data.copyOfRange(800, 2000)))
            }
        }
        val s = prefetcher(h).run()
        assertEquals("bytes=800-", range)
        assertEquals(1, s.downloaded)
        assertTrue(store.file(h).readBytes().contentEquals(data))
    }

    @Test
    fun notFoundMarksMissing() {
        val h = sha("x".toByteArray())
        server.enqueue(MockResponse().setResponseCode(404))
        val s = prefetcher(h).run()
        assertEquals(1, s.missing)
        assertEquals(BlobState.MISSING, store.entries().single().state)
    }

    @Test
    fun noDownloadOnMeteredByDefault() {
        metered = true
        val s = prefetcher(sha("x".toByteArray())).run()
        assertTrue(s.skippedMetered)
        assertEquals(0, server.requestCount)
        prefs.allowMetered = true
        server.enqueue(MockResponse().setResponseCode(404))
        prefetcher(sha("x".toByteArray())).run()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun clearCacheAndTouch() {
        val data = ByteArray(10) { 1 }
        val h = sha(data)
        server.enqueue(MockResponse().setBody(Buffer().write(data)))
        prefetcher(h).run()
        store.touch(h, 99)
        assertEquals(99L, store.entries().single().lastUsedAt)
        store.clearCache()
        assertTrue(store.entries().isEmpty())
        assertFalse(store.file(h).exists())
    }
}
