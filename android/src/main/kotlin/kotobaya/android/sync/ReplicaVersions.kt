package kotobaya.android.sync

import java.util.ArrayDeque
import kotobaya.sync.client.apply.LearningTable

/**
 * PERF-LF: which learning tables changed, so readers can keep what they derived from the replica (the queue loader's
 * per-note facts and deck memberships, Home's lists) until a table they read changes, instead of re-reading the whole
 * replica on every request.
 *
 * [SqlDelightDeviceStore] records every committed write transaction here AFTER its commit (a reader that sees a
 * version saw at least the data of that version; reading the version first and the data second can only make it
 * refresh once more, never keep stale data). Writes through [SqlLearningTables] are attributed to their table (and,
 * for [LearningTable.CARD], to their keys); a transaction whose SQLite `total_changes()` shows rows written by anything
 * else (raw statements, e.g. the content installer's `deck_item` rows) bumps every table. Writes outside the store's
 * transactions are not seen: learning data is written only inside them (CLAUDE.md rule 8).
 */
class ReplicaVersions {
    private val versions = LongArray(LearningTable.entries.size)
    private var all = 0L

    /** Recent commits that touched `card`: (the card version after it, its card keys or null = unknown / too many). */
    private val cardLog = ArrayDeque<Pair<Long, Set<String>?>>()

    /** One number that changes whenever any of [tables] changed (monotonic). */
    @Synchronized
    fun version(vararg tables: LearningTable): Long = all + tables.sumOf { versions[it.ordinal] }

    /** One number for every learning table. */
    @Synchronized
    fun versionOfAll(): Long = all + versions.sum()

    /** Current `card` version (for [changedCards]). */
    @Synchronized
    fun cardVersion(): Long = all + versions[LearningTable.CARD.ordinal]

    /**
     * The card ids written since [cardVersion] was [since]; null when that is not known exactly (an untracked write, a
     * table clear, a big batch, or too long ago): the caller reloads everything.
     */
    @Synchronized
    fun changedCards(since: Long): Set<String>? {
        if (since == cardVersion()) return emptySet()
        val out = HashSet<String>()
        var covered = false
        for ((v, keys) in cardLog) {
            if (v <= since) {
                covered = true
                continue
            }
            out += keys ?: return null
        }
        // the log must reach back to `since` (its oldest entry at or before it), else commits were dropped
        if (!covered && (cardLog.isEmpty() || cardLog.first().first - 1 > since)) return null
        return out
    }

    /** Records one committed transaction. [cardKeys]: card ids written (null = unknown). [untracked]: other rows were written. */
    @Synchronized
    internal fun committed(tables: Set<LearningTable>, cardKeys: Set<String>?, untracked: Boolean) {
        if (untracked) {
            all++
            cardLog.addLast(cardVersion() to null)
        } else {
            for (t in tables) versions[t.ordinal]++
            if (LearningTable.CARD in tables) cardLog.addLast(cardVersion() to cardKeys?.takeIf { it.size <= MAX_KEYS })
        }
        while (cardLog.size > MAX_LOG) cardLog.removeFirst()
    }

    private companion object {
        const val MAX_LOG = 512
        const val MAX_KEYS = 2_000
    }
}

/** What one write transaction did, collected by its [SqlLearningTables] and outbox / sync_state writers. */
internal class TxChanges {
    val tables = HashSet<LearningTable>()
    var cardKeys: MutableSet<String>? = HashSet()

    /** Rows the store's own writers changed (compared with SQLite `total_changes()` at commit). */
    var counted = 0L

    fun learning(table: LearningTable, key: String?, rows: Long) {
        tables += table
        counted += rows
        if (table == LearningTable.CARD) {
            if (key == null) cardKeys = null else cardKeys?.add(key)
        }
    }
}
