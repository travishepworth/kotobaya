package kotobaya.android.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import java.util.ArrayDeque
import kotobaya.sync.client.apply.LearningTable

/**
 * PERF-LF: which learning tables changed, so readers can keep what they derived from the replica (the queue loader's
 * card snapshot and deck memberships, the stats' per-card history, Home's lists) until a table they read changes,
 * instead of re-reading the whole replica on every request.
 *
 * [SqlDelightDeviceStore] records every committed write transaction here AFTER its commit (a reader that reads a
 * version first and the data second can only refresh once more, never keep stale data under a newer version). What a
 * transaction changed comes from [TouchLog]: SQL triggers on every learning table, so every row write counts exactly,
 * whoever issued it (the learning tables, the content installer's raw `deck_item` statements, anything else inside a
 * store transaction), and nothing else does (the device's own indexes and change logs, outbox, sync state).
 * Writes outside the store's transactions are not seen: learning data is written only inside them (CLAUDE.md rule 8).
 */
class ReplicaVersions {
    private val versions = LongArray(LearningTable.entries.size)

    /** Per keyed table, recent commits that touched it: (its version after the commit, its keys or null = too many). */
    private val logs = KEYED.associateWith { ArrayDeque<Pair<Long, Set<String>?>>() }

    /** One number that changes whenever any of [tables] changed (monotonic). */
    @Synchronized
    fun version(vararg tables: LearningTable): Long = tables.sumOf { versions[it.ordinal] }

    /** The version of [table] alone (for [changedKeys]). */
    @Synchronized
    fun tableVersion(table: LearningTable): Long = versions[table.ordinal]

    /** Current `card` version (for [changedCards]). */
    fun cardVersion(): Long = tableVersion(LearningTable.CARD)

    /** [changedKeys] of `card`. */
    fun changedCards(since: Long): Set<String>? = changedKeys(LearningTable.CARD, since)

    /**
     * The keys of [table] (one of [KEYED]; its first key column) written since its [tableVersion] was [since]; null when
     * that is not known exactly (a big batch, or too long ago): the caller reloads everything.
     */
    @Synchronized
    fun changedKeys(table: LearningTable, since: Long): Set<String>? {
        val log = logs[table] ?: return null
        val now = tableVersion(table)
        if (since == now) return emptySet()
        if (since > now) return null
        // every increment of the table's version has exactly one log entry with that version: the log must reach back
        // to since + 1, else commits were dropped from it
        if (log.isEmpty() || log.first().first > since + 1) return null
        val out = HashSet<String>()
        for ((v, keys) in log) if (v > since) out += keys ?: return null
        return out
    }

    /** Records one committed transaction. */
    @Synchronized
    internal fun committed(t: Touched) {
        for (table in t.tables) {
            versions[table.ordinal]++
            logs[table]?.let { log ->
                log.addLast(versions[table.ordinal] to t.keys[table])
                while (log.size > MAX_LOG) log.removeFirst()
            }
        }
    }

    companion object {
        /** The tables whose written keys are logged. */
        val KEYED: Set<LearningTable> = setOf(LearningTable.CARD, LearningTable.REVIEW_LOG, LearningTable.NOTE, LearningTable.WORD_STATUS)
        internal const val MAX_KEYS = 2_000
        private const val MAX_LOG = 1_024
    }
}

/** What one write transaction changed: the learning tables, and the keys of the [ReplicaVersions.KEYED] ones (null = too many). */
internal class Touched(val tables: Set<LearningTable>, val keys: Map<LearningTable, Set<String>?>)

/**
 * The row writes of the current write transaction, recorded by TEMP triggers on every learning table into a TEMP
 * table of the write connection (created there on first use; a temp table's rows roll back with the transaction).
 * [prepare] at the start of each outermost store transaction, [drain] at its end (before the commit).
 */
internal class TouchLog(private val driver: SqlDriver) {
    /** False when the log cannot be set up on this connection (then the transaction reports [UNKNOWN]). */
    fun prepare(): Boolean = try {
        setUp()
        true
    } catch (e: RuntimeException) {
        false
    }

    private fun setUp() {
        val exists = driver.executeQuery(null, "SELECT count(*) FROM sqlite_temp_master WHERE type = 'table' AND name = '$TABLE'", { c: SqlCursor ->
            c.next()
            QueryResult.Value((c.getLong(0) ?: 0L) > 0L)
        }, 0).value
        if (exists) return
        driver.execute(null, "CREATE TEMP TABLE IF NOT EXISTS $TABLE (tbl TEXT NOT NULL, k TEXT)", 0)
        for (t in LearningTable.entries) {
            val key = t.keyColumns.first()
            // only the keyed tables need the key; the others record the table alone
            val newKey = if (t in ReplicaVersions.KEYED) "new.$key" else "NULL"
            val oldKey = if (t in ReplicaVersions.KEYED) "old.$key" else "NULL"
            val name = t.sqlName
            driver.execute(null, "CREATE TEMP TRIGGER IF NOT EXISTS ${TABLE}_${name}_i AFTER INSERT ON main.$name BEGIN INSERT INTO $TABLE VALUES ('$name', $newKey); END", 0)
            driver.execute(null, "CREATE TEMP TRIGGER IF NOT EXISTS ${TABLE}_${name}_u AFTER UPDATE ON main.$name BEGIN INSERT INTO $TABLE VALUES ('$name', $newKey); END", 0)
            driver.execute(null, "CREATE TEMP TRIGGER IF NOT EXISTS ${TABLE}_${name}_d AFTER DELETE ON main.$name BEGIN INSERT INTO $TABLE VALUES ('$name', $oldKey); END", 0)
        }
    }

    /**
     * What the transaction wrote. If the log cannot be read (a driver whose "transaction" spans several connections, as
     * a test control does), every learning table counts as changed with unknown keys: readers reload, never go stale.
     */
    fun drain(): Touched = try {
        read()
    } catch (e: RuntimeException) {
        UNKNOWN
    }

    private fun read(): Touched {
        val counts = driver.executeQuery(null, "SELECT tbl, count(DISTINCT k) FROM $TABLE GROUP BY tbl", { c: SqlCursor ->
            val out = HashMap<String, Long>()
            while (c.next().value) out[checkNotNull(c.getString(0))] = c.getLong(1) ?: 0L
            QueryResult.Value(out)
        }, 0).value
        if (counts.isEmpty()) return Touched(emptySet(), emptyMap())
        val byName = LearningTable.entries.associateBy { it.sqlName }
        val tables = counts.keys.mapNotNullTo(HashSet()) { byName[it] }
        val keys = HashMap<LearningTable, Set<String>?>()
        for (t in ReplicaVersions.KEYED) {
            val n = counts[t.sqlName] ?: continue
            keys[t] = if (n > ReplicaVersions.MAX_KEYS) null else driver.executeQuery(null, "SELECT DISTINCT k FROM $TABLE WHERE tbl = ? AND k IS NOT NULL", { c: SqlCursor ->
                val out = HashSet<String>()
                while (c.next().value) c.getString(0)?.let(out::add)
                QueryResult.Value(out)
            }, 1) { bindString(0, t.sqlName) }.value
        }
        driver.execute(null, "DELETE FROM $TABLE", 0)
        return Touched(tables, keys)
    }

    companion object {
        private const val TABLE = "perf_replica_touch"

        /** Every learning table changed, keys unknown: readers reload. */
        val UNKNOWN: Touched = Touched(LearningTable.entries.toSet(), ReplicaVersions.KEYED.associateWith { null })
    }
}
