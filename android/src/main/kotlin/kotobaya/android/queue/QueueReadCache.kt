package kotobaya.android.queue

import java.util.concurrent.ConcurrentHashMap
import kotobaya.android.sync.ReplicaVersions
import kotobaya.contracts.Reserved
import kotobaya.db.app.LocalQueueQueries
import kotobaya.sync.client.apply.LearningTable

/** One `queueCandidates` row (the loader's view of a card and its note). Property names as the generated row's. */
data class CandidateRow(
    val id: String,
    val note_id: String,
    val kind: String,
    val note_type_id: String,
    val type: String,
    val created_at: Long,
    val state: String,
    val due_at: Long?,
    val suspended: Boolean,
    val buried_until: Long?,
    val override_status: String?,
    val kanji_literal: String?,
    val entry_seq: Long?,
    val fields: String,
) {
    /**
     * The day filter of `queueCandidates` (LocalQueue.sq): unsuspended and new or due before [dayEnd], or started and of
     * a vocab or kanji note (ADR-040 / ADR-043: the builder reads "introduced" and "started" from those).
     */
    fun queueable(dayEnd: Long): Boolean =
        (!suspended && (state == "new" || (due_at != null && due_at < dayEnd))) || (state != "new" && (type == "vocab" || type == "kanji"))

    companion object {
        /** The mapper shared by `queueCandidates`, `queueCandidatesAll` and `queueCandidatesOf`. */
        @Suppress("LongParameterList")
        fun of(
            id: String, noteId: String, kind: String, noteTypeId: String, type: String, createdAt: Long, state: String, dueAt: Long?,
            suspended: Boolean, buriedUntil: Long?, overrideStatus: String?, kanjiLiteral: String?, entrySeq: Long?, fields: String,
        ): CandidateRow = CandidateRow(id, noteId, kind, noteTypeId, type, createdAt, state, dueAt, suspended, buriedUntil, overrideStatus, kanjiLiteral, entrySeq, fields)
    }
}

/**
 * PERF-LF: what the queue loader reads from the replica on every build, kept between builds (one per app database,
 * shared by every loader: native screens, the shell's `getReviewQueue` and `getStatsSummary`) and refreshed only when
 * [ReplicaVersions] says a table it came from changed:
 * - the card snapshot ([candidates]): every live card of a live note with its note's facts; after an answer only the
 *   cards the write touched are read again (one indexed `IN` read), after a note / word status change or an untracked
 *   write all of them;
 * - anything else through [memo]: one value per name, valid while the given version is unchanged.
 *
 * Every refresh reads the version BEFORE the data, so a value is never newer-labelled than what it holds (a write in
 * between only causes one more refresh). Nothing here decides a queue rule: the rows are exactly what the queries return.
 */
class QueueReadCache(private val versions: ReplicaVersions) {
    private class Memo(val version: Long, val key: Any?, val value: Any?)

    private val memos = ConcurrentHashMap<String, Memo>()

    private class Snapshot(val noteVersion: Long, val cardVersion: Long, val rows: LinkedHashMap<String, CandidateRow>)

    private var snapshot: Snapshot? = null

    /** Full reloads and partial refreshes of the card snapshot so far (tests). */
    @Volatile var fullLoads: Int = 0
        private set

    @Volatile var partialLoads: Int = 0
        private set

    /** The version of [tables] (for [memo] keys). */
    fun version(vararg tables: LearningTable): Long = versions.version(*tables)

    /** [load]'s value for [name], computed again only when [version] or [key] differs from the cached one's (one value per name). */
    fun <T> memo(name: String, version: Long, key: Any? = null, load: () -> T): T {
        memos[name]?.takeIf { it.version == version && it.key == key }?.let {
            @Suppress("UNCHECKED_CAST")
            return it.value as T
        }
        val v = load()
        memos[name] = Memo(version, key, v)
        return v
    }

    /** Every live card of a live note (see `queueCandidatesAll`), current as of this call. */
    @Synchronized
    fun candidates(q: LocalQueueQueries): Collection<CandidateRow> {
        val noteVersion = versions.version(LearningTable.NOTE, LearningTable.WORD_STATUS, LearningTable.NOTE_TYPE)
        val cardVersion = versions.cardVersion()
        val s = snapshot
        if (s != null && s.noteVersion == noteVersion) {
            if (s.cardVersion == cardVersion) return ArrayList(s.rows.values)
            val changed = versions.changedCards(s.cardVersion)
            if (changed != null && changed.size <= MAX_PARTIAL) {
                val fresh = changed.chunked(IN_CHUNK).flatMap { ids -> q.queueCandidatesOf(ids, Reserved.REPLAY_REFUSED_ID, CandidateRow::of).executeAsList() }
                    .associateBy { it.id }
                for (id in changed) {
                    val row = fresh[id]
                    if (row == null) s.rows.remove(id) else s.rows[id] = row
                }
                snapshot = Snapshot(noteVersion, cardVersion, s.rows)
                partialLoads++
                return ArrayList(s.rows.values)
            }
        }
        val rows = LinkedHashMap<String, CandidateRow>()
        for (r in q.queueCandidatesAll(Reserved.REPLAY_REFUSED_ID, CandidateRow::of).executeAsList()) rows[r.id] = r
        snapshot = Snapshot(noteVersion, cardVersion, rows)
        fullLoads++
        return ArrayList(rows.values)
    }

    private companion object {
        const val MAX_PARTIAL = 2_000
        const val IN_CHUNK = 500
    }
}
