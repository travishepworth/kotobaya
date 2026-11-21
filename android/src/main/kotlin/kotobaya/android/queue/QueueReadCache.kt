package kotobaya.android.queue

import java.util.TreeMap
import kotobaya.android.sync.ReplicaVersions
import kotobaya.contracts.Reserved
import kotobaya.db.app.DeckItemMemberships
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
        /** The mapper shared by `queueCandidates`, `queueCandidatesAll`, `queueCandidatesOf` and `queueCandidatesOfNotes`. */
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
 * - the card snapshot ([candidates]): every live card of a live note with its note's facts, in card id order (the
 *   core builder canonicalizes by card id anyway). After a write only the cards it touched, the cards of the notes it
 *   touched and the cards of the entries whose word status it touched are read again (indexed `IN` reads); a note
 *   type change or a very big batch reads them all;
 * - the deck memberships ([memberships]): after a note write only the memberships of those notes (a merge, or any
 *   deck / deck item change, reads them all);
 * - anything else through [memo]: one value per name (at most [MAX_MEMOS] names), valid while its version and key hold.
 *
 * Every refresh reads the versions BEFORE the data, so a value is never newer-labelled than what it holds (a write in
 * between only causes one more refresh). Nothing here decides a queue rule: the rows are exactly what the queries return.
 */
class QueueReadCache(private val versions: ReplicaVersions) {
    private class Memo(val version: Long, val key: Any?, val value: Any?)

    /** Least recently used first; bounded. */
    private val memos = object : LinkedHashMap<String, Memo>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Memo>?): Boolean = size > MAX_MEMOS
    }

    private class Snapshot(val typeVersion: Long, val noteVersion: Long, val statusVersion: Long, val cardVersion: Long, val rows: TreeMap<String, CandidateRow>)

    private var snapshot: Snapshot? = null

    private class Members(val deckVersion: Long, val noteVersion: Long, val byNote: HashMap<String, MutableList<DeckItemMemberships>>)

    private var members: Members? = null

    /** Full reloads and partial refreshes of the card snapshot so far (tests). */
    @Volatile var fullLoads: Int = 0
        private set

    @Volatile var partialLoads: Int = 0
        private set

    /** Full reloads and partial refreshes of the memberships so far (tests). */
    @Volatile var memberFullLoads: Int = 0
        private set

    @Volatile var memberPartialLoads: Int = 0
        private set

    /** Forgets every [memo] value (tests: compare a memoized answer with a fresh one). */
    internal fun clearMemos() = synchronized(memos) { memos.clear() }

    /** Names kept by [memo] now (tests: bounded). */
    fun memoCount(): Int = synchronized(memos) { memos.size }

    /** The version of [tables] (for [memo] keys). */
    fun version(vararg tables: LearningTable): Long = versions.version(*tables)

    /** [load]'s value for [name], computed again only when [version] or [key] differs from the cached one's (one value per name). */
    fun <T> memo(name: String, version: Long, key: Any? = null, load: () -> T): T {
        synchronized(memos) { memos[name] }?.takeIf { it.version == version && it.key == key }?.let {
            @Suppress("UNCHECKED_CAST")
            return it.value as T
        }
        val v = load()
        synchronized(memos) { memos[name] = Memo(version, key, v) }
        return v
    }

    /** Every live card of a live note (see `queueCandidatesAll`) in card id order, current as of this call. */
    @Synchronized
    fun candidates(q: LocalQueueQueries): List<CandidateRow> {
        val typeVersion = versions.version(LearningTable.NOTE_TYPE)
        val noteVersion = versions.tableVersion(LearningTable.NOTE)
        val statusVersion = versions.tableVersion(LearningTable.WORD_STATUS)
        val cardVersion = versions.cardVersion()
        val s = snapshot
        if (s != null && s.typeVersion == typeVersion) {
            if (s.noteVersion == noteVersion && s.statusVersion == statusVersion && s.cardVersion == cardVersion) return ArrayList(s.rows.values)
            val cards = versions.changedCards(s.cardVersion)
            val notes = versions.changedKeys(LearningTable.NOTE, s.noteVersion)
            val seqs = versions.changedKeys(LearningTable.WORD_STATUS, s.statusVersion)
            if (cards != null && notes != null && seqs != null && cards.size + notes.size + seqs.size <= MAX_PARTIAL) {
                val noteIds = HashSet(notes)
                seqs.mapNotNull { it.toLongOrNull() }.chunked(IN_CHUNK).forEach { noteIds += q.notesOfEntries(it).executeAsList() }
                // the cards of the touched notes: drop them all, then put back what those notes hold now
                if (noteIds.isNotEmpty()) s.rows.values.removeIf { it.note_id in noteIds }
                for (ids in noteIds.toList().chunked(IN_CHUNK)) {
                    for (r in q.queueCandidatesOfNotes(ids, Reserved.REPLAY_REFUSED_ID, CandidateRow::of).executeAsList()) s.rows[r.id] = r
                }
                val fresh = cards.chunked(IN_CHUNK).flatMap { ids -> q.queueCandidatesOf(ids, Reserved.REPLAY_REFUSED_ID, CandidateRow::of).executeAsList() }
                    .associateBy { it.id }
                for (id in cards) {
                    val row = fresh[id]
                    if (row == null) s.rows.remove(id) else s.rows[id] = row
                }
                snapshot = Snapshot(typeVersion, noteVersion, statusVersion, cardVersion, s.rows)
                partialLoads++
                return ArrayList(s.rows.values)
            }
        }
        val rows = TreeMap<String, CandidateRow>()
        for (r in q.queueCandidatesAll(Reserved.REPLAY_REFUSED_ID, CandidateRow::of).executeAsList()) rows[r.id] = r
        snapshot = Snapshot(typeVersion, noteVersion, statusVersion, cardVersion, rows)
        fullLoads++
        return ArrayList(rows.values)
    }

    /** `deckItemMemberships`, current as of this call. */
    @Synchronized
    fun memberships(q: LocalQueueQueries): List<DeckItemMemberships> {
        val deckVersion = versions.version(LearningTable.DECK, LearningTable.DECK_ITEM)
        val noteVersion = versions.tableVersion(LearningTable.NOTE)
        val m = members
        if (m != null && m.deckVersion == deckVersion) {
            if (m.noteVersion == noteVersion) return m.byNote.values.flatten()
            val notes = versions.changedKeys(LearningTable.NOTE, m.noteVersion)
            if (notes != null && notes.size <= MAX_PARTIAL) {
                val chunks = notes.toList().chunked(IN_CHUNK)
                if (chunks.all { q.mergedAmong(it).executeAsOne() == 0L }) {
                    for (id in notes) m.byNote.remove(id)
                    for (ids in chunks) {
                        for (r in q.deckItemMembershipsOfNotes(ids, ids, ids).executeAsList()) {
                            m.byNote.getOrPut(r.note_id) { ArrayList() } += DeckItemMemberships(r.deck_id, r.position, r.item_key, r.note_id)
                        }
                    }
                    members = Members(deckVersion, noteVersion, m.byNote)
                    memberPartialLoads++
                    return m.byNote.values.flatten()
                }
            }
        }
        val byNote = HashMap<String, MutableList<DeckItemMemberships>>()
        for (r in q.deckItemMemberships().executeAsList()) byNote.getOrPut(r.note_id) { ArrayList() } += r
        members = Members(deckVersion, noteVersion, byNote)
        memberFullLoads++
        return byNote.values.flatten()
    }

    private companion object {
        const val MAX_PARTIAL = 2_000
        const val IN_CHUNK = 500
        const val MAX_MEMOS = 256
    }
}
