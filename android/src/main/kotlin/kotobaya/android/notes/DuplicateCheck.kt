package kotobaya.android.notes

import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

/** Read access to the learning tables without committing anything (`SqlDelightDeviceStore.read`). */
interface LearningReader {
    fun <R> read(block: (LearningTables) -> R): R
}

/** A live local note that already studies the word. */
data class ExistingNote(
    val noteId: String,
    val entrySeq: Long?,
    val expression: String?,
    val createdAt: Long,
)

/**
 * "Words are learned once" on this device (SYNC.md 4: a client MUST NOT knowingly create a duplicate).
 * Looks only at **local** live notes; duplicates between devices that had not synced are the server's
 * job (SYNC.md 8).
 *
 * A word is already there when a live note
 * - holds the entry the word matches (`entry_seq`), or
 * - has no entry and its `fields.expression` is the word (and, when both sides have one, the same
 *   `fields.reading`, katakana folded): a note added while the word was unmatched or the dictionary
 *   was missing.
 *
 * Several live candidates (a pre-merge local duplicate) → the oldest `(created_at, id)`, the one the
 * server would keep (8.1 "the note that already held the reference").
 *
 * Used by [NoteCreator], by T305c for AnkiDroid's duplicate query and by T311 for `canAddNotes`.
 */
class DuplicateCheck(private val reader: LearningReader, private val matcher: EntryMatcher) {
    /** Matches [word]/[reading] against the dictionary, then [find]s on the local tables. */
    fun find(word: String?, reading: String?): ExistingNote? {
        val w = EntryMatcher.clean(word)
        val r = EntryMatcher.clean(reading)
        if (w == null && r == null) return null
        val seq = (matcher.match(w, r) as? MatchResult.Matched)?.entry?.seq
        return reader.read { find(it, seq, w ?: r, r) }
    }

    /** The live note holding entry [seq], if any. */
    fun findByEntry(seq: Long): ExistingNote? = reader.read { byEntry(it, seq) }

    companion object {
        /** [find] on [tables] (inside a caller's transaction). [expression] and [reading] must be cleaned. */
        fun find(tables: LearningTables, seq: Long?, expression: String?, reading: String?): ExistingNote? =
            seq?.let { byEntry(tables, it) } ?: expression?.let { byWord(tables, it, reading) }

        fun byEntry(tables: LearningTables, seq: Long): ExistingNote? =
            oldest(tables.where(LearningTable.NOTE, "entry_seq", JsonNumber(seq.toDouble())).filter(::isLive))

        private fun byWord(tables: LearningTables, expression: String, reading: String?): ExistingNote? =
            oldest(
                tables.where(LearningTable.NOTE, "entry_seq", JsonNull).filter { row ->
                    if (!isLive(row) || row["type"] != JsonString("vocab")) return@filter false
                    val fields = (row["fields"] as? JsonObject)?.members.orEmpty()
                    val e = EntryMatcher.clean((fields["expression"] as? JsonString)?.value) ?: return@filter false
                    val r = EntryMatcher.clean((fields["reading"] as? JsonString)?.value)
                    e == expression && (reading == null || r == null || EntryMatcher.fold(r) == EntryMatcher.fold(reading))
                },
            )

        internal fun isLive(row: Row): Boolean =
            (row["deleted_at"] ?: JsonNull) == JsonNull && (row["merged_into_id"] ?: JsonNull) == JsonNull

        private fun oldest(rows: List<Row>): ExistingNote? = rows.map(::toExisting).minWithOrNull(compareBy({ it.createdAt }, { it.noteId }))

        internal fun toExisting(row: Row): ExistingNote {
            val fields = (row["fields"] as? JsonObject)?.members.orEmpty()
            return ExistingNote(
                noteId = (row.getValue("id") as JsonString).value,
                entrySeq = (row["entry_seq"] as? JsonNumber)?.value?.toLong(),
                expression = (fields["expression"] as? JsonString)?.value,
                createdAt = num(row["created_at"]) ?: 0L,
            )
        }

        private fun num(v: JsonValue?): Long? = (v as? JsonNumber)?.value?.toLong()
    }
}
