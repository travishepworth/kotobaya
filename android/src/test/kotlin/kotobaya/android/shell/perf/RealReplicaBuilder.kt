package kotobaya.android.shell.perf

import android.app.Application
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotobaya.android.db.APP_DB_FILE
import kotobaya.android.sync.DurableDriver
import kotobaya.android.sync.HttpSyncTransport
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SyncClient
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.bookkeeping
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PERF-LF, a developer tool (skipped unless `PERF_LF_SERVER`, `PERF_LF_TOKEN` and `PERF_LF_OUT` are set): builds a
 * device replica with the real [SyncClient]'s first pull from a running server (e.g. one over a restored backup) and
 * copies the database file to `PERF_LF_OUT`. [HomeLoadBenchmarkTest] measures on it when `PERF_LF_REPLICA` names it.
 * The replica holds user data: write it to a scratch directory, never into the repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RealReplicaBuilder {
    @Test
    fun firstSyncIntoAReplicaFile() {
        val server = System.getenv("PERF_LF_SERVER")
        val token = System.getenv("PERF_LF_TOKEN")
        val out = System.getenv("PERF_LF_OUT")
        assumeTrue(server != null && token != null && out != null)
        val app: Application = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(APP_DB_FILE)
        val driver = DurableDriver.open(app)
        val store = SqlDelightDeviceStore(driver)
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, System.getenv("PERF_LF_DEVICE") ?: "01931f3e-0000-7000-8000-0000000000e1") }
        val client = SyncClient(store, HttpSyncTransport({ server }, { token }, { null }))
        client.startup.run(store)
        val t0 = System.nanoTime()
        val r = runBlocking { client.engine.pullAll() }
        println("PERF-LF first pull: $r in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(r is CycleResult.Completed, "$r")
        store.transaction { it.bookkeeping.lastSyncAt = System.currentTimeMillis() }
        driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { c: SqlCursor -> c.next(); QueryResult.Value(Unit) }, 0)
        driver.close()
        app.getDatabasePath(APP_DB_FILE).copyTo(File(out), overwrite = true)
        app.deleteDatabase(APP_DB_FILE)
    }
}
