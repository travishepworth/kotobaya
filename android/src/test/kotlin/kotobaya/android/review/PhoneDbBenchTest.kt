package kotobaya.android.review

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.fsrs.Button
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.StartupReplay
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.UuidV7Source

/**
 * AND-REVIEW-SNAPPY: the per-phase timing lines of a session on a copy of a real app database. Opt-in and never
 * committed with data: `KOTOBAYA_BENCH_DB=/path/to/kotobaya-app.db` (the file is copied; the original is not touched).
 * Prints `BENCH` lines only; the asserting bench on a synthetic collection of the same size is [ReviewLatencyTest].
 */
class PhoneDbBenchTest {
    @Test
    fun phasesOnAPhoneDatabaseCopy() {
        val src = System.getenv("KOTOBAYA_BENCH_DB")?.let(::File)?.takeIf { it.isFile } ?: return
        Class.forName("org.sqlite.JDBC")
        val dir = kotlin.io.path.createTempDirectory("kb-bench").toFile()
        try {
            val f = File(dir, "app.db")
            src.copyTo(f)
            val driver = JdbcSqliteDriver(
                "jdbc:sqlite:${f.path}",
                Properties().apply {
                    put("journal_mode", "WAL")
                    put("synchronous", "FULL")
                },
            )
            val store = SqlDelightDeviceStore(driver)
            val cache = DeviceCardCache()
            val ids = UuidV7Source(Random(1))
            val minter = OpMinter(store, DeviceOpApplier<SqlLearningTables>(cache, StartupReplay(cache)), Clock { System.currentTimeMillis() }, ids)
            val reads = object : ReviewReads<SqlLearningTables> {
                override fun <R> read(block: (StoreTransaction<SqlLearningTables>) -> R): R = store.read(block)
            }
            val s = ReviewSession(
                store, minter, LocalQueueLoader(AppDatabase(driver), { System.currentTimeMillis() }).asQueueSource(),
                { System.currentTimeMillis() }, { 540 }, { ids.newOpId(it) }, { ReviewPrefs() },
                timings = { println("BENCH " + it.line()) }, reads = reads,
            )
            s.start()
            repeat(30) { i ->
                val st = s.state.value as? ReviewState.Showing ?: return
                s.flip()
                s.answer(st.card.token, if (i % 4 == 0) Button.AGAIN else Button.GOOD)
            }
            driver.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
