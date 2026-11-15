package kotobaya.android.ankiapi.store

import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.ankiapi.provider.AnkiContract
import kotobaya.android.notes.NoteChangeLog
import kotobaya.android.notes.NoteIndex
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.Row

/**
 * AND-TAKO-PERF2: live vocab notes by the AnkiDroid `csum` of their expression and of their reading (the first
 * field of every model the duplicate query can answer from here; Takoboto Vocab's is the expression), so the query
 * reads only the notes it may answer with. App-private, never synced, rebuilt at will ([NoteIndex]).
 *
 * A note's value is `fields[key]`, else (entry-linked) the dictionary headword/reading ([EntryLookup.forms]), else
 * "" — exactly what [TakobotoAnkiStore] checksums. Because of the dictionary part the [stamp] carries the installed
 * dictionary's identity ([EntryLookup.identity]): a new dictionary rebuilds the index.
 */
class ChecksumIndex(
    driver: SqlDriver,
    log: NoteChangeLog,
    private val entries: EntryLookup,
) : NoteIndex<ChecksumIndex.Entry>(driver, log, CONSUMER) {
    data class Entry(val noteId: String, val expression: Long, val reading: Long)

    override val table: String = TABLE
    override val ddl: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS $TABLE (note_id TEXT NOT NULL PRIMARY KEY, expression INTEGER NOT NULL, reading INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS ${TABLE}_expression_idx ON $TABLE (expression)",
        "CREATE INDEX IF NOT EXISTS ${TABLE}_reading_idx ON $TABLE (reading)",
    )

    override fun stamp(): String = "$STAMP|${entries.identity()}"

    override fun compute(rows: List<Row>): List<Entry> {
        val live = rows.filter(::isLiveVocab)
        val forms = entries.forms(
            live.mapNotNull { row ->
                val f = fields(row)
                seqOf(row)?.takeIf { f["expression"] == null || f["reading"] == null }
            },
        )
        return live.mapNotNull { row ->
            val id = str(row, "id") ?: return@mapNotNull null
            val f = fields(row)
            val hw = seqOf(row)?.let { forms[it] }
            Entry(id, AnkiContract.fieldChecksum(f["expression"] ?: hw?.word ?: ""), AnkiContract.fieldChecksum(f["reading"] ?: hw?.reading ?: ""))
        }
    }

    override fun write(entries: List<Entry>) {
        for (e in entries) {
            driver.execute(null, "INSERT OR REPLACE INTO $TABLE (note_id, expression, reading) VALUES (?, ?, ?)", 3) {
                bindString(0, e.noteId)
                bindLong(1, e.expression)
                bindLong(2, e.reading)
            }
        }
    }

    /**
     * Candidate note ids (rowid order) whose checksum of any of [keys] (`expression`, `reading`) is in [sums];
     * re-check each. Null = cannot answer now, or a key is not indexed.
     */
    fun candidates(keys: Set<String>, sums: Set<Long>): List<String>? {
        if (keys.isEmpty() || !KEYS.containsAll(keys)) return null
        if (sums.isEmpty()) return emptyList()
        // checksums are numbers parsed by the provider: inlined as literals (nothing to escape, no bind limit)
        val list = sums.joinToString(",")
        return lookup(keys.joinToString(" OR ") { "$it IN ($list)" }, 0) {}
    }

    companion object {
        const val TABLE: String = "ankiapi_note_csum"
        const val CONSUMER: String = "ankiapi.csum"
        private const val STAMP = "csum.v1"

        /** The `note.fields` keys this index answers for. */
        val KEYS: Set<String> = setOf("expression", "reading")

        private fun seqOf(row: Row): Long? = (row["entry_seq"] as? JsonNumber)?.value?.toLong()

        private fun fields(row: Row): Map<String, String> =
            (row["fields"] as? JsonObject)?.members.orEmpty().mapNotNull { (k, v) -> (v as? JsonString)?.value?.let { k to it } }.toMap()
    }
}
