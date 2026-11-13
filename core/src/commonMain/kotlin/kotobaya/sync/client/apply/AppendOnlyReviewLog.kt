package kotobaya.sync.client.apply

import kotobaya.contracts.HlcString
import kotobaya.contracts.Uuid
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.replay.ReviewLogRules
import kotobaya.contracts.replay.UndoOutcome
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.replay.DefaultReviewLogRules
import kotobaya.sync.DefaultHlcRules

/** A write to `review_log` that SYNC.md 7.9 forbids on a device (Postgres refuses it with a trigger; the device in code). */
public class AppendOnlyViolation(message: String) : IllegalStateException(message)

/**
 * The only writer of `review_log` on a device (SYNC.md 7.9; Postgres enforces the same with a
 * trigger, the device's SQLite has none). It issues exactly the statements the contract allows:
 * - [insert]: `INSERT` iff no row with that id exists (rule 1; the first content is kept);
 * - [undo]: the one `UPDATE … SET undone_hlc, undone_at` of rules 3–4, **earliest undo wins**
 *   ([ReviewLogRules.undo]);
 * - the rebuild wipe (rule 5) goes through the raw tables, never through this class.
 *
 * [guard] wraps a [LearningTables] so that every other write to `review_log` (a whole-row `put`, a
 * `delete`, a `clear`) throws [AppendOnlyViolation]; the device applier only ever sees the guarded view.
 */
public class AppendOnlyReviewLog(
    private val raw: LearningTables,
    private val rules: ReviewLogRules = DefaultReviewLogRules,
) {
    /** The row with [id], or null. */
    public fun get(id: Uuid): Row? = raw.get(LearningTable.REVIEW_LOG, listOf(JsonString(id)))

    /** Rule 1: inserts [row] iff its id is new; returns whether it was inserted. Never changes an existing row. */
    public fun insert(row: Row): Boolean {
        val id = row["id"] ?: throw AppendOnlyViolation("review_log row without id")
        if (!rules.shouldInsert(raw.get(LearningTable.REVIEW_LOG, listOf(id)) != null)) return false
        if (row["undone_hlc"].let { it != null && it != JsonNull } || row["undone_at"].let { it != null && it != JsonNull }) {
            throw AppendOnlyViolation("a review is inserted not undone (SYNC.md 7.9)")
        }
        raw.put(LearningTable.REVIEW_LOG, row)
        return true
    }

    /**
     * Rules 3–4 for `review.undone` with clock [opHlc] on review [id]: writes `undone_hlc = opHlc`,
     * `undone_at = wall(opHlc)` iff the row is not undone or [opHlc] is smaller. Returns the outcome,
     * or null when the device has no such review (a no-op, OPS.md `review.undone`).
     */
    public fun undo(id: Uuid, opHlc: HlcString): UndoOutcome? {
        val row = get(id) ?: return null
        val stored = (row["undone_hlc"] as? JsonString)?.value
        val storedAt = (row["undone_at"] as? JsonNumber)?.value?.toLong()
        val type = (row["review_type"] as? JsonString)?.value
        val feeds = ReviewType.entries.firstOrNull { it.wire == type }?.feedsSchedule ?: false
        val outcome = rules.undo(stored, storedAt, opHlc, DefaultHlcRules.wall(opHlc), feeds)
        if (outcome.apply) {
            val updated = LinkedHashMap(row)
            updated["undone_hlc"] = outcome.undoneHlc?.let(::JsonString) ?: JsonNull
            updated["undone_at"] = outcome.undoneAt?.let { JsonNumber(it.toDouble()) } ?: JsonNull
            raw.put(LearningTable.REVIEW_LOG, updated)
        }
        return outcome
    }

    /** Statics. */
    public companion object {
        /** [tables] with every `review_log` write except through [AppendOnlyReviewLog] refused. */
        public fun guard(tables: LearningTables): LearningTables = if (tables is Guarded) tables else Guarded(tables)

        /** The unguarded tables behind [tables] (the wipe of a rebuild, SYNC.md 7.9 rule 5). */
        internal fun unguarded(tables: LearningTables): LearningTables = if (tables is Guarded) tables.raw else tables
    }

    private class Guarded(val raw: LearningTables) : LearningTables by raw {
        override fun put(table: LearningTable, row: Row) {
            if (table == LearningTable.REVIEW_LOG) {
                throw AppendOnlyViolation("review_log is append-only: use AppendOnlyReviewLog.insert / undo (SYNC.md 7.9 rules 1-3)")
            }
            raw.put(table, row)
        }

        override fun delete(table: LearningTable, key: List<JsonValue>) {
            if (table == LearningTable.REVIEW_LOG) throw AppendOnlyViolation("review_log rows are never deleted (SYNC.md 7.9 rule 5)")
            raw.delete(table, key)
        }

        override fun clear(table: LearningTable) {
            if (table == LearningTable.REVIEW_LOG) throw AppendOnlyViolation("only the rebuild wipe clears review_log (SYNC.md 7.9 rule 5)")
            raw.clear(table)
        }
    }
}
