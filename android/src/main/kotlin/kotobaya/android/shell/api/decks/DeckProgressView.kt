package kotobaya.android.shell.api.decks

import kotobaya.android.notes.readingEnabled
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.text
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.decks.DeckWord
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.WordCardState
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.decks.DefaultDeckProgress
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

/**
 * `Deck.progress` (ADR-041): the rows of a deck's visible items as core [DeckWord]s, counted by core
 * [DefaultDeckProgress]. Reads only; the rule is the core's. Batched (one IN read per table per deck), because
 * `listDecks` computes it for every deck.
 */
internal object DeckProgressView {
    /** `DeckProgress` JSON of [items] (the deck's visible items), or `JsonNull` for a smart or tombstoned deck. */
    fun progress(t: LearningTables, deck: Row, items: List<VisibleItem>): JsonValue {
        if (deck["type"].text() == "smart" || !deck["deleted_at"].isNullish()) return JsonNull
        val count = DefaultDeckProgress.count(words(t, items), readingEnabled(t))
        return JsonObject(
            linkedMapOf(
                "learned" to JsonNumber(count.learned.toDouble()),
                "total" to JsonNumber(count.total.toDouble()),
                "percent" to JsonNumber(count.percent.toDouble()),
            ),
        )
    }

    private fun words(t: LearningTables, items: List<VisibleItem>): List<DeckWord> {
        val seqKeys = items.mapNotNull { it.entrySeq }.map { JsonNumber(it.toDouble()) }
        // the identity of an `n:` item is already the resolved (surviving) note id
        val noteIds = items.mapNotNull { it.identity.takeIf { k -> k.startsWith("n:") }?.substring(2) }
        val statuses = whereIn(t, LearningTable.WORD_STATUS, "entry_seq", seqKeys)
            .associate { (it["entry_seq"] as? JsonNumber)?.value?.toLong() to it["status"].text() }
        // the live note of each entry (the oldest, as DictViews.noteId)
        val entryNotes = whereIn(t, LearningTable.NOTE, "entry_seq", seqKeys)
            .filter(::live).groupBy { (it["entry_seq"] as? JsonNumber)?.value?.toLong() }
            .mapValues { (_, rows) -> rows.minWith(compareBy<Row>({ (it["created_at"] as? JsonNumber)?.value ?: 0.0 }, { it["id"].text().orEmpty() })) }
        val idNotes = whereIn(t, LearningTable.NOTE, "id", noteIds.map(::JsonString)).filter(::live).associateBy { it["id"].text() }
        val cardsByNote = whereIn(
            t, LearningTable.CARD, "note_id",
            (entryNotes.values.mapNotNull { it["id"].text() } + idNotes.keys.filterNotNull()).map(::JsonString),
        ).filter(::live).groupBy { it["note_id"].text() }

        fun cards(note: Row?): List<WordCardState> = note?.let { n ->
            cardsByNote[n["id"].text()].orEmpty().map { c ->
                WordCardState(
                    state = CardState.entries.firstOrNull { it.wire == c["state"].text() } ?: CardState.NEW,
                    intervalDays = (c["interval_days"] as? JsonNumber)?.value?.toInt(),
                    kind = c["kind"].text(),
                )
            }
        }.orEmpty()

        fun base(note: Row?): NoteBaseType? = note?.let { n -> NoteBaseType.entries.firstOrNull { it.wire == n["type"].text() } }

        return items.map { item ->
            val seq = item.entrySeq
            if (seq != null) {
                val note = entryNotes[seq]
                DeckWord(item.identity, WordStatus.entries.firstOrNull { it.wire == statuses[seq] }, base(note), cards(note))
            } else {
                val note = idNotes[item.identity.substring(2)]
                DeckWord(item.identity, null, base(note), cards(note))
            }
        }
    }

    private fun live(r: Row): Boolean = r["deleted_at"].isNullish() && r["merged_into_id"].isNullish()

    private fun whereIn(t: LearningTables, table: LearningTable, column: String, values: List<JsonValue>): List<Row> =
        if (values.isEmpty()) emptyList() else (t as? SqlLearningTables)?.whereIn(table, column, values) ?: values.flatMap { t.where(table, column, it) }
}
