package kotobaya.android.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractSchemaTest {
    private val contractDir = File("../contracts/db/sqldelight/app/kotobaya/db/app")

    private fun newDb() = createAppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

    @Test
    fun everyContractTableIsCreated() {
        val expected = contractDir.listFiles { f -> f.extension == "sq" && f.name in setOf("Learning.sq", "Local.sq", "Media.sq") }!!
            .flatMap { f -> Regex("(?im)^CREATE TABLE (\\w+)").findAll(f.readText()).map { it.groupValues[1] }.toList() }
            .toSet()
        assertTrue(expected.containsAll(setOf("pending_op", "sync_state", "card", "review_log", "media")))

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createAppDatabase(driver)
        val actual = driver.executeQuery(
            null, "SELECT name FROM sqlite_master WHERE type = 'table'", { c ->
                val names = mutableSetOf<String>()
                while (c.next().value) names += c.getString(0)!!
                app.cash.sqldelight.db.QueryResult.Value(names)
            }, 0,
        ).value
        assertEquals(expected, actual.filterNot { it.startsWith("sqlite_") }.toSet())
    }

    @Test
    fun pendingOpAndSyncStateRoundTrip() {
        val q = newDb().syncOpsQueries
        q.insertPendingOp("op-1", "review.logged", 1, "card", "c-1", "hlc-1", "{}", 1000)
        val rows = q.pendingOpsByState("pending").executeAsList()
        assertEquals(1, rows.size)
        assertEquals("op-1", rows[0].op_id)
        assertEquals("review.logged", rows[0].op_type)

        q.putSyncState("pull_seq", "0")
        q.putSyncState("pull_seq", "5")
        assertEquals("5", q.getSyncState("pull_seq").executeAsOne())
    }
}
