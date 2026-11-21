package kotobaya.android.media

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotobaya.android.db.createAppDatabase

/** C-31: the native resolver behind the local `resolveSpeech` fills `tts_audio`, asks again on `pending`, remembers `failed`. */
class SpeechResolveQueueTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { createAppDatabase(it) }
    private val table = TtsAudioTable(driver)
    private val blobs = BlobStore(driver, Files.createTempDirectory("blobs").toFile())
    private var now = 1_000_000L
    private val hash = "b".repeat(64)

    private class Server(val answers: MutableList<(WantedAudio.Pair) -> SpeechAnswer>) : UrgentSpeechResolver {
        val calls = ArrayList<Pair<List<WantedAudio.Pair>, Boolean>>()
        var offline = false

        override fun resolve(pairs: List<WantedAudio.Pair>, urgent: Boolean): List<SpeechAnswer>? {
            if (offline) return null
            calls += pairs to urgent
            val f = answers.removeAt(0)
            return pairs.map(f)
        }
    }

    private fun queue(server: Server) = BackgroundSpeechResolveQueue(
        CoroutineScope(Dispatchers.Unconfined), table, blobs, server, nowMs = { now }, sleep = { now += it },
    )

    @Test
    fun pendingIsAskedAgainReadyIsStoredAndTheBlobWanted() = runBlocking {
        val server = Server(
            mutableListOf(
                { SpeechAnswer("pending", null, null, 1_000) },
                { SpeechAnswer("ready", hash, 640, null) },
            ),
        )
        val q = queue(server)
        server.offline = true // the enqueue's own drain finds the server unreachable and keeps the pair
        q.request("3", "あり", urgent = true)
        assertEquals(1, q.pendingCount())
        assertNull(table.get("3", "あり"))
        server.offline = false
        q.drain()
        assertEquals(0, q.pendingCount())
        assertEquals(listOf(true, false), server.calls.map { it.second }, "urgent once, then the plain repeat")
        val row = assertNotNull(table.get("3", "あり"))
        assertEquals(hash, row.blobHash)
        assertEquals(640, row.durationMs)
        assertTrue(blobs.entries().any { it.hash == hash })
    }

    @Test
    fun aFailedPairIsRememberedForTheBackoffAndNotAskedAgain() = runBlocking {
        val server = Server(mutableListOf({ SpeechAnswer("failed", null, null, 600_000) }))
        val q = queue(server)
        q.request("3", "だめ", urgent = false)
        assertEquals(600_000L, q.failedRemainingMs("3", "だめ"))
        now += 100_000
        assertEquals(500_000L, q.failedRemainingMs("3", "だめ"))
        q.request("3", "だめ", urgent = false) // inside the back-off: no new request
        assertEquals(1, server.calls.size)
        now += 600_000
        assertNull(q.failedRemainingMs("3", "だめ"))
    }
}
