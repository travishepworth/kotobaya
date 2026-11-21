package kotobaya.android.sync

import java.util.ArrayDeque
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable

/**
 * PERF-LF: which learning tables changed, so readers can keep what they derived from the replica (the queue loader's
 * card snapshot and deck memberships, the stats' per-card history) until a table they read changes, instead of
 * re-reading the whole replica on every request.
 *
 * [SqlDelightDeviceStore] records every committed write transaction here AFTER its commit (a reader that reads a
 * version first and the data second can only refresh once more, never keep stale data under a newer version). Writes
 * through [SqlLearningTables] are attributed to their table and, for the [KEYED] tables, to their keys; a transaction
 * whose SQLite `total_changes()` shows rows written by anything else (raw statements, e.g. the content installer's
 * `deck_item` rows) bumps every table and forgets the keys. Writes outside the store's transactions are not seen:
 * learning data is written only inside them (CLAUDE.md rule 8).
 */
class ReplicaVersions {
    private val versions = LongArray(LearningTable.entries.size)
    private var all = 0L

    /** Per keyed table, recent commits that touched it: (its version after the commit, its keys or null = unknown). */
    private val logs = KEYED.associateWith { ArrayDeque<Pair<Long, Set<String>?>>() }

    /** One number that changes whenever any of [tables] changed (monotonic). */
    @Synchronized
    fun version(vararg tables: LearningTable): Long = all + tables.sumOf { versions[it.ordinal] }

    /** The version of [table] alone (for [changedKeys]). */
    @Synchronized
    fun tableVersion(table: LearningTable): Long = all + versions[table.ordinal]

    /** Current `card` version (for [changedCards]). */
    fun cardVersion(): Long = tableVersion(LearningTable.CARD)

    /** [changedKeys] of `card`. */
    fun changedCards(since: Long): Set<String>? = changedKeys(LearningTable.CARD, since)

    /**
     * The keys of [table] (one of [KEYED]; the first key column) written since its [tableVersion] was [since]; null when
     * that is not known exactly (an untracked write, a table clear, a big batch, or too long ago): reload everything.
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

    /** Records one committed transaction (see [TxChanges]). [untracked]: rows the store's writers did not count were written. */
    @Synchronized
    internal fun committed(changes: TxChanges, untracked: Boolean) {
        if (untracked) {
            all++
            for ((t, log) in logs) log.addLast(tableVersion(t) to null)
        } else {
            for (t in changes.tables) {
                versions[t.ordinal]++
                logs[t]?.addLast(tableVersion(t) to changes.keys(t)?.takeIf { it.size <= MAX_KEYS })
            }
        }
        for (log in logs.values) while (log.size > MAX_LOG) log.removeFirst()
    }

    companion object {
        /** The tables whose written keys are logged. */
        val KEYED: Set<LearningTable> = setOf(LearningTable.CARD, LearningTable.REVIEW_LOG)
        private const val MAX_LOG = 1_024
        private const val MAX_KEYS = 2_000
    }
}

/** What one write transaction did, collected by its [SqlLearningTables] and outbox / sync_state writers. */
internal class TxChanges {
    val tables = HashSet<LearningTable>()
    private val keys = HashMap<LearningTable, MutableSet<String>?>()

    /** Rows the store's own writers changed (compared with SQLite `total_changes()` at commit). */
    var counted = 0L

    /** [rows] rows of [table] written for [key] (null: a whole-table write). */
    fun learning(table: LearningTable, key: List<JsonValue>?, rows: Long) {
        tables += table
        counted += rows
        if (table !in ReplicaVersions.KEYED) return
        val k = (key?.firstOrNull() as? JsonString)?.value
        if (!keys.containsKey(table)) keys[table] = HashSet()
        if (k == null) keys[table] = null else keys[table]?.add(k)
    }

    fun keys(table: LearningTable): Set<String>? = if (keys.containsKey(table)) keys[table] else emptySet()
}
