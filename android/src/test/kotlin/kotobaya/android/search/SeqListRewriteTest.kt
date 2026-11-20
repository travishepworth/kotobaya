package kotobaya.android.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.search.sql.Dialect
import kotobaya.search.sql.DictFormRow
import kotobaya.search.sql.SearchContext
import kotobaya.search.sql.SearchSqlCompiler
import kotobaya.search.sql.SqlFragment
import kotobaya.contracts.queue.StudyDay

/**
 * LF-A5: [ReplicaCardQuery.indexSeqLists] turns core's per-note `instr` over the C-24 seq list into an indexed
 * `IN (subquery)` with the same meaning. If core stops emitting [ReplicaCardQuery.SEQ_LIST_TEST] the first test fails,
 * so the rewrite never silently stops applying.
 */
class SeqListRewriteTest {
    private fun compiled(q: String, seqs: List<Long>): SqlFragment {
        val ctx = SearchContext(
            studyDay = StudyDay(0L, 86_400_000L), nowMs = 1L, leechThreshold = 8,
            dictForms = { _ -> seqs.map { DictFormRow(it, "たべる", "たべる") } },
        )
        return SearchSqlCompiler.compile(checkNotNull(DefaultSearchQueryParser.parse(q).query), Dialect.SQLITE, ctx).select()
    }

    @Test
    fun coreStillEmitsTheSeqListTestAndTheRewriteReplacesEveryOne() {
        val f = compiled("たべ or -(べる x)", listOf(5, 7))
        assertTrue(f.sql.contains(ReplicaCardQuery.SEQ_LIST_TEST), f.sql)
        val r = ReplicaCardQuery.indexSeqLists(f)
        assertFalse(r.sql.contains("instr(?, ','"), r.sql)
        assertEquals(r.sql.count { it == '?' }, r.params.size)
    }

    @Test
    fun theRewriteSelectsTheSameSeqs() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE n (entry_seq INTEGER)", 0)
        listOf(1L, 5L, 7L, 50L, 1_000_000_007L, 9_000_000_000_000L).forEach { driver.execute(null, "INSERT INTO n VALUES ($it)", 0) }
        driver.execute(null, "INSERT INTO n VALUES (NULL)", 0)
        val list = listOf(5L, 50L, 1_000_000_007L, 9_000_000_000_000L, 123L)
        val original = SqlFragment("SELECT entry_seq FROM n WHERE ${ReplicaCardQuery.SEQ_LIST_TEST} ORDER BY entry_seq", listOf(list.joinToString(",", ",", ",")))
        fun run(f: SqlFragment): List<Long?> = driver.rows(f.sql, f.params) { it.getLong(0) }
        assertEquals(listOf<Long?>(5, 50, 1_000_000_007, 9_000_000_000_000), run(original))
        assertEquals(run(original), run(ReplicaCardQuery.indexSeqLists(original)))
        // negated, with placeholders before and after, and a quoted literal holding a '?'
        val mixed = SqlFragment(
            "SELECT entry_seq FROM n WHERE entry_seq > ? AND '?' = '?' AND NOT (${ReplicaCardQuery.SEQ_LIST_TEST}) AND entry_seq < ? ORDER BY entry_seq",
            listOf(1L, list.joinToString(",", ",", ","), 1_000_000L),
        )
        assertEquals(listOf<Long?>(7), run(mixed))
        assertEquals(run(mixed), run(ReplicaCardQuery.indexSeqLists(mixed)))
        driver.close()
    }
}
