package kotobaya.android.net

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotobaya.db.app.AppDatabase

class MetaLimitsStoreTest {
    @Test
    fun survivesReopenAndDefaultsOnlyWhenNeverFetched() {
        val file = File.createTempFile("kotobaya", ".db").also { it.deleteOnExit() }
        val url = "jdbc:sqlite:${file.path}"

        val d1 = JdbcSqliteDriver(url)
        AppDatabase.Schema.create(d1)
        val s1 = MetaLimitsStore(AppDatabase(d1))
        assertFalse(s1.hasFetched())
        assertEquals(MetaLimits.DEFAULTS, s1.current())
        val custom = MetaLimits(maxOpBytes = 2048, maxPushBytes = 20_000, maxPageSize = 50)
        s1.save(custom)
        d1.close()

        val d2 = JdbcSqliteDriver(url)
        val s2 = MetaLimitsStore(AppDatabase(d2))
        assertTrue(s2.hasFetched())
        assertEquals(custom, s2.current())
        d2.close()
    }
}
