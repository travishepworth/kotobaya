package kotobaya.android.review

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver

/**
 * [BackExtrasIndex] from the device's learning database: four narrow-column reads (seq/status, seq, id pairs), run once
 * per queue build. Same rules as [BackExtrasIndex.from] (C-25): `word_status` rows that are not `new`; `entry_seq` of
 * live notes; `entry_seq` of live `deck_item`s whose deck row exists and is live; `merged_into_id` links.
 */
class SqlBackExtrasIndex(private val driver: SqlDriver) : BackExtrasIndexSource {
    override fun load(): BackExtrasIndex {
        val b = BackExtrasIndex.Builder()
        scan(STATUS) { b.status(it.getLong(0) ?: return@scan, it.getString(1)) }
        scan(NOTES) { b.note(it.getLong(0) ?: return@scan) }
        scan(DECK_ITEMS) { b.deckItem(it.getLong(0) ?: return@scan) }
        scan(MERGED) { c -> b.merged(c.getString(1) ?: return@scan, c.getString(0) ?: return@scan) }
        return b.build()
    }

    private fun scan(sql: String, row: (SqlCursor) -> Unit) {
        driver.executeQuery(null, sql, { c ->
            while (c.next().value) row(c)
            QueryResult.Unit
        }, 0)
    }

    private companion object {
        const val STATUS = "SELECT entry_seq, status FROM word_status WHERE status <> 'new'"
        const val NOTES = "SELECT DISTINCT entry_seq FROM note WHERE entry_seq IS NOT NULL AND deleted_at IS NULL"
        const val DECK_ITEMS = "SELECT DISTINCT i.entry_seq FROM deck_item i JOIN deck d ON d.id = i.deck_id " +
            "WHERE i.entry_seq IS NOT NULL AND i.deleted_at IS NULL AND d.deleted_at IS NULL"
        const val MERGED = "SELECT id, merged_into_id FROM note WHERE merged_into_id IS NOT NULL"
    }
}
