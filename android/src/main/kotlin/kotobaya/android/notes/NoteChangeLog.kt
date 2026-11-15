package kotobaya.android.notes

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * AND-TAKO-PERF2: a device-only change feed of the `note` table, so lookup indexes over notes ([NoteWordIndex],
 * the AnkiDroid checksum index) stay correct however a note is written: minted locally, applied from a sync
 * pull, replayed, rebuilt. SQLite triggers on `note` append the id of every inserted, updated or deleted row to
 * [CHANGE_TABLE]; each index (a "consumer") remembers the last change it has folded in ([CURSOR_TABLE]) and the
 * stamp it was built with. Changes every consumer has seen are pruned.
 *
 * Nothing here is learning data, nothing is synced, and the contract schema is untouched: the tables and triggers
 * are created at run time ([install], `IF NOT EXISTS`) and can be dropped at any time; a consumer whose cursor is
 * missing rebuilds from the `note` table. If the triggers ever vanish (the note table recreated), [install]
 * recreates them and drops every cursor, so no index trusts changes it could not see.
 *
 * Writes ([install], [advance]) must run inside the caller's store transaction; reads may run anywhere.
 */
class NoteChangeLog(private val driver: SqlDriver) {
    data class Cursor(val seq: Long, val stamp: String)

    /** True when the tables and all three triggers exist. One `sqlite_master` read. */
    fun installed(): Boolean = driver.executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE name IN (${OBJECTS.joinToString { "'$it'" }})",
        { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) },
        0,
    ).value == OBJECTS.size.toLong()

    /** Creates what is missing. True if anything was (re)created: every consumer then rebuilds. */
    fun install(): Boolean {
        if (installed()) return false
        for (sql in DDL) driver.execute(null, sql, 0)
        driver.execute(null, "DELETE FROM $CURSOR_TABLE", 0)
        return true
    }

    fun cursor(consumer: String): Cursor? = driver.executeQuery(
        null,
        "SELECT seq, stamp FROM $CURSOR_TABLE WHERE consumer = ?",
        { c -> QueryResult.Value(if (c.next().value) Cursor(c.getLong(0) ?: 0L, c.getString(1).orEmpty()) else null) },
        1,
    ) { bindString(0, consumer) }.value

    /** The newest change's sequence number (0 before the first change). */
    fun head(): Long = driver.executeQuery(
        null,
        "SELECT coalesce((SELECT seq FROM sqlite_sequence WHERE name = '$CHANGE_TABLE'), 0)",
        { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) },
        0,
    ).value

    /** How many changes came after [seq]. */
    fun countAfter(seq: Long): Long = driver.executeQuery(
        null,
        "SELECT count(*) FROM $CHANGE_TABLE WHERE seq > ?",
        { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) },
        1,
    ) { bindLong(0, seq) }.value

    /** Distinct ids of notes changed after [seq] and up to [upTo]. */
    fun changedIds(seq: Long, upTo: Long): Set<String> = driver.executeQuery(
        null,
        "SELECT DISTINCT note_id FROM $CHANGE_TABLE WHERE seq > ? AND seq <= ?",
        { c -> QueryResult.Value(buildSet { while (c.next().value) c.getString(0)?.let(::add) }) },
        2,
    ) {
        bindLong(0, seq)
        bindLong(1, upTo)
    }.value

    /** Records that [consumer] has folded in every change up to [seq] (built with [stamp]) and prunes what all have seen. */
    fun advance(consumer: String, seq: Long, stamp: String) {
        driver.execute(null, "INSERT OR REPLACE INTO $CURSOR_TABLE (consumer, seq, stamp) VALUES (?, ?, ?)", 3) {
            bindString(0, consumer)
            bindLong(1, seq)
            bindString(2, stamp)
        }
        driver.execute(null, "DELETE FROM $CHANGE_TABLE WHERE seq <= (SELECT min(seq) FROM $CURSOR_TABLE)", 0)
    }

    /**
     * SQL for "ids of notes changed since [consumer]'s cursor" (one bind: the consumer), to UNION into an index
     * lookup so the whole lookup reads one snapshot. No cursor = every logged change (a superset, still correct).
     */
    fun pendingIdsSql(): String =
        "SELECT note_id FROM $CHANGE_TABLE WHERE seq > coalesce((SELECT seq FROM $CURSOR_TABLE WHERE consumer = ?), -1)"

    companion object {
        const val CHANGE_TABLE: String = "device_note_change"
        const val CURSOR_TABLE: String = "device_note_change_cursor"
        private const val T_INSERT = "device_note_change_ins"
        private const val T_UPDATE = "device_note_change_upd"
        private const val T_DELETE = "device_note_change_del"

        private val OBJECTS = listOf(CHANGE_TABLE, CURSOR_TABLE, T_INSERT, T_UPDATE, T_DELETE)

        private val DDL = listOf(
            "CREATE TABLE IF NOT EXISTS $CHANGE_TABLE (seq INTEGER PRIMARY KEY AUTOINCREMENT, note_id TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS $CURSOR_TABLE (consumer TEXT NOT NULL PRIMARY KEY, seq INTEGER NOT NULL, stamp TEXT NOT NULL)",
            "CREATE TRIGGER IF NOT EXISTS $T_INSERT AFTER INSERT ON note BEGIN " +
                "INSERT INTO $CHANGE_TABLE (note_id) VALUES (new.id); END",
            "CREATE TRIGGER IF NOT EXISTS $T_UPDATE AFTER UPDATE ON note BEGIN " +
                "INSERT INTO $CHANGE_TABLE (note_id) VALUES (new.id); " +
                "INSERT INTO $CHANGE_TABLE (note_id) SELECT old.id WHERE old.id <> new.id; END",
            "CREATE TRIGGER IF NOT EXISTS $T_DELETE AFTER DELETE ON note BEGIN " +
                "INSERT INTO $CHANGE_TABLE (note_id) VALUES (old.id); END",
        )
    }
}
