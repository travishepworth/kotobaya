package kotobaya.android.notes

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

/**
 * AND-TAKO-PERF2: a device-only lookup table over vocab notes (never synced, rebuilt from the `note` table at
 * will), kept fresh from the [NoteChangeLog]. Subclasses say which table it is and what a note's entries are.
 *
 * - [catchUp] (inside a store write transaction) folds in the logged changes, O(changed notes); it rebuilds from
 *   every vocab note when the cursor is missing or the [stamp] changed (first use, a new dictionary).
 * - A rebuild can be computed outside the write transaction ([prepare], on a plain read) and handed to [catchUp],
 *   so the write lock is held only to store the entries. That is safe because the log head is read BEFORE the
 *   rows: every change the rows may miss has a later sequence number and is folded in after the rebuilt entries.
 * - [lookup] (anywhere) answers in ONE statement from the index UNION the notes changed since the cursor, in
 *   note rowid order. Entries are only candidates: callers re-read each row and check it, so a pending or stale
 *   entry can never produce a wrong answer, only a row to discard. Null when the index cannot answer (never
 *   built, stamp changed, too many pending changes): catch up in a write transaction or scan.
 */
abstract class NoteIndex<E>(
    protected val driver: SqlDriver,
    private val log: NoteChangeLog,
    private val consumer: String,
) {
    /** A rebuild computed by [prepare]: entries of every vocab note as of log [head], with [stamp]. */
    class Prepared<E> internal constructor(
        internal val head: Long,
        internal val stamp: String,
        internal val rows: Int,
        internal val entries: List<E>,
    )

    /** The index table; must have a `note_id` column. */
    protected abstract val table: String

    /** `CREATE ... IF NOT EXISTS` statements of [table] and its indexes. */
    protected abstract val ddl: List<String>

    /** What the entries were computed with; a different stamp means rebuild. */
    abstract fun stamp(): String

    /** The entries of [rows] (current rows, live or not; only the ones to index yield entries). No writes. */
    protected abstract fun compute(rows: List<Row>): List<E>

    /** Stores [entries] (their notes' old entries are already deleted). */
    protected abstract fun write(entries: List<E>)

    /** Creates the log and this index's table if missing. Inside a store write transaction. */
    fun install() {
        log.install()
        for (sql in ddl) driver.execute(null, sql, 0)
    }

    /** A rebuild computed on a plain read, or null if none is due (or the log is not [install]ed). No writes. */
    fun prepare(tables: LearningTables): Prepared<E>? {
        if (!log.installed()) return null
        val stamp = stamp()
        if (log.cursor(consumer)?.stamp == stamp) return null
        val head = log.head() // before the rows: see the class comment
        val rows = tables.where(LearningTable.NOTE, "type", JsonString("vocab"))
        return Prepared(head, stamp, rows.size, compute(rows))
    }

    /**
     * Folds in the logged changes, rebuilding first when due (from [prepared] if it still applies, else by reading
     * every vocab note here). Must run inside a store write transaction. Returns the notes read.
     */
    fun catchUp(tables: LearningTables, prepared: Prepared<E>? = null): Int {
        val reinstalled = log.install()
        for (sql in ddl) driver.execute(null, sql, 0)
        val stamp = stamp()
        var cursor = log.cursor(consumer)
        var read = 0
        if (reinstalled || cursor == null || cursor.stamp != stamp) {
            val p = prepared?.takeIf { !reinstalled && it.stamp == stamp }
                ?: tables.where(LearningTable.NOTE, "type", JsonString("vocab")).let { rows ->
                    Prepared(log.head(), stamp, rows.size, compute(rows))
                }
            driver.execute(null, "DELETE FROM $table", 0)
            write(p.entries)
            read = p.rows
            log.advance(consumer, p.head, stamp)
            cursor = NoteChangeLog.Cursor(p.head, stamp)
        }
        val head = log.head()
        if (cursor.seq >= head) return read
        val ids = log.changedIds(cursor.seq, head)
        for (chunk in ids.chunked(CHUNK)) {
            driver.execute(null, "DELETE FROM $table WHERE note_id IN (${chunk.joinToString { "?" }})", chunk.size) {
                chunk.forEachIndexed { i, id -> bindString(i, id) }
            }
        }
        write(compute(ids.mapNotNull { tables.get(LearningTable.NOTE, listOf(JsonString(it))) }))
        log.advance(consumer, head, stamp)
        return read + ids.size
    }

    /** True when [lookup] can answer now (built with the current stamp, few enough pending changes). */
    fun usable(): Boolean = runCatching {
        if (!log.installed()) return false
        val cursor = log.cursor(consumer) ?: return false
        cursor.stamp == stamp() && log.countAfter(cursor.seq) <= MAX_PENDING
    }.getOrDefault(false)

    /**
     * Note ids (rowid order) whose entries match [where] (on [table], [binds] bound first), plus every note changed
     * since the cursor. Null when not [usable].
     */
    protected fun lookup(where: String, binds: Int, binder: SqlPreparedStatement.() -> Unit): List<String>? {
        if (!usable()) return null
        return runCatching {
            driver.executeQuery(
                null,
                "SELECT n.id FROM note n WHERE n.id IN (SELECT note_id FROM $table WHERE $where UNION ${log.pendingIdsSql()}) ORDER BY n.rowid",
                { c -> QueryResult.Value(buildList { while (c.next().value) c.getString(0)?.let(::add) }) },
                binds + 1,
            ) {
                binder()
                bindString(binds, consumer)
            }.value
        }.getOrNull()
    }

    companion object {
        /** More changes than this since the cursor: [lookup] declines (catch up first). */
        const val MAX_PENDING: Long = 256
        private const val CHUNK = 500

        fun isLiveVocab(row: Row): Boolean = DuplicateCheck.isLive(row) && row["type"] == JsonString("vocab")

        fun str(row: Row, col: String): String? = (row[col] as? JsonString)?.value

        fun isNull(row: Row, col: String): Boolean = (row[col] ?: JsonNull) == JsonNull
    }
}

/**
 * Live vocab notes by their cleaned `fields.expression` ([EntryMatcher.clean]): what [DuplicateCheck]'s word rule
 * compares, so a word lookup no longer reads every note.
 */
class NoteWordIndex(driver: SqlDriver, log: NoteChangeLog) : NoteIndex<Pair<String, String>>(driver, log, CONSUMER) {
    override val table: String = TABLE
    override val ddl: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS $TABLE (note_id TEXT NOT NULL PRIMARY KEY, word TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS ${TABLE}_word_idx ON $TABLE (word)",
    )

    override fun stamp(): String = STAMP

    override fun compute(rows: List<Row>): List<Pair<String, String>> = rows.mapNotNull { row ->
        if (!isLiveVocab(row)) return@mapNotNull null
        val id = str(row, "id") ?: return@mapNotNull null
        val expression = ((row["fields"] as? JsonObject)?.members?.get("expression") as? JsonString)?.value
        EntryMatcher.clean(expression)?.let { id to it }
    }

    override fun write(entries: List<Pair<String, String>>) {
        for ((id, word) in entries) {
            driver.execute(null, "INSERT OR REPLACE INTO $TABLE (note_id, word) VALUES (?, ?)", 2) {
                bindString(0, id)
                bindString(1, word)
            }
        }
    }

    /** Candidate ids of live vocab notes whose cleaned expression is [word] (re-check each row); null = cannot answer. */
    fun candidates(word: String): List<String>? = lookup("word = ?", 1) { bindString(0, word) }

    companion object {
        const val TABLE: String = "device_note_word"
        const val CONSUMER: String = "notes.word"
        private const val STAMP = "word.v1"
    }
}
