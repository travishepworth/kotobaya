package kotobaya.android.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotobaya.android.db.createAppDatabase
import kotobaya.contracts.search.SearchErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** My-cards search: core parser + SQLite compiler against an in-memory replica (the device schema). */
class LocalCardSearchTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = createAppDatabase(driver)
    private val search = LocalCardSearch(driver, db, { NOW })

    private fun uuid(kind: Int, n: Int) = "0193a1b2-%04d-7000-8000-%012d".format(kind, n)

    private fun note(n: Int, entry: Long?, expression: String, deleted: Boolean = false) = driver.execute(
        null,
        "INSERT INTO note (id, note_type_id, type, entry_seq, fields, created_by_device_id, created_at, updated_at, deleted_at) " +
            "VALUES ('${uuid(1, n)}', '00000000-0000-7000-8000-000000000101', '${if (entry == null) "custom" else "vocab"}', " +
            "${entry ?: "NULL"}, '{\"expression\":\"$expression\"}', 'dev', $n, $n, ${if (deleted) "1" else "NULL"})",
        0,
    )

    private fun card(n: Int, noteN: Int, kind: String = "meaning", lapses: Int = 0, suspended: Boolean = false) = driver.execute(
        null,
        "INSERT INTO card (id, note_id, kind, suspended, lapses, created_at, updated_at) VALUES " +
            "('${uuid(2, n)}', '${uuid(1, noteN)}', '$kind', ${if (suspended) 1 else 0}, $lapses, $n, $n)",
        0,
    )

    private fun fixture() {
        note(1, 100, "食べる")
        note(2, 200, "飲む")
        note(3, null, "custom thing")
        note(4, 400, "deleted", deleted = true)
        card(1, 1, "meaning", lapses = 5)
        card(2, 1, "reading", lapses = 0)
        card(3, 2, "meaning", lapses = 2, suspended = true)
        card(4, 3, "meaning")
        card(5, 4, "meaning") // note deleted: never matches
    }

    private fun ids(q: String): List<String> {
        val r = search.search(q)
        assertTrue(r is CardSearchOutcome.Results, "$q: $r")
        return r.hits.map { it.cardId }
    }

    @Test
    fun queriesReturnTheExpectedCards() {
        fixture()
        assertEquals(listOf(4, 3, 2, 1).map { uuid(2, it) }, ids(""))
        assertEquals(listOf(2, 1).map { uuid(2, it) }, ids("entry:100"))
        assertEquals(listOf(3, 1).map { uuid(2, it) }, ids("lapses>1"))
        assertEquals(listOf(uuid(2, 3)), ids("is:suspended"))
        assertEquals(listOf(uuid(2, 2)), ids("entry:100 kind:reading"))
        val hit = (search.search("entry:200") as CardSearchOutcome.Results).hits.single()
        assertEquals(200L, hit.entrySeq)
        assertEquals("飲む", hit.title)
        assertTrue(hit.suspended)
    }

    @Test
    fun aParseErrorIsAnErrorStateNotACrash() {
        fixture()
        val unknown = search.search("dek:x")
        assertEquals(SearchErrorCode.UNKNOWN_KEY, (unknown as CardSearchOutcome.ParseError).errors.first().code)
        val paren = search.search("(entry:100")
        assertEquals(SearchErrorCode.UNBALANCED_PAREN, (paren as CardSearchOutcome.ParseError).errors.first().code)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
