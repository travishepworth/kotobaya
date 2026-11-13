package kotobaya.android.sync

import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotobaya.android.db.APP_DB_FILE
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BUG-8 (I-4/I-7): on the real Android driver (WAL), [SqlDelightDeviceStore.read] runs on a reader
 * connection: it neither waits for an open sync write transaction nor sees its uncommitted rows, and
 * it refuses writes (they would autocommit one by one outside any transaction).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WalReaderTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val driver = DurableDriver.open(context)
    private val store = SqlDelightDeviceStore(driver)

    @After
    fun tearDown() {
        driver.close()
        context.deleteDatabase(APP_DB_FILE)
    }

    @Test
    fun readsProceedWhileASyncWriteTransactionIsOpen() {
        assertEquals("wal", DurableDriver.journalMode(driver))
        val noteTypes = store.read { it.learning.all(LearningTable.NOTE_TYPE).size }
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = thread {
            store.transaction { tx ->
                tx.syncState.put("bug8.probe", "uncommitted")
                tx.learning.put(LearningTable.SETTINGS, mapOf("key" to JsonString("bug8"), "value" to JsonString("x"), "hlc" to JsonString("h"), "updated_at" to kotobaya.contracts.sync.JsonNumber(1.0)))
                inside.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "never released" }
            }
        }
        assertTrue(inside.await(10, TimeUnit.SECONDS), "the writer opened its transaction")
        val started = System.nanoTime()
        val seen = store.read { tx -> tx.syncState.get("bug8.probe") to tx.learning.all(LearningTable.NOTE_TYPE).size }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertNull(seen.first, "a reader never sees an uncommitted write")
        assertEquals(noteTypes, seen.second)
        assertTrue(ms < 2_000, "the read waited $ms ms for the writer")
        release.countDown()
        writer.join(10_000)
        assertEquals("uncommitted", store.read { it.syncState.get("bug8.probe") }, "committed now")
    }

    @Test
    fun aReadRefusesWrites() {
        assertFailsWith<IllegalStateException> { store.read { it.syncState.put("k", "v") } }
        assertFailsWith<IllegalStateException> { store.read { it.learning.delete(LearningTable.SETTINGS, listOf(JsonString("k"))) } }
        assertNull(store.read { it.syncState.get("k") })
        // inside a transaction, a read joins it and may see (not write) its rows
        store.transaction { tx ->
            tx.syncState.put("k", "v")
            assertEquals("v", store.read { it.syncState.get("k") })
        }
    }
}
