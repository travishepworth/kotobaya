package kotobaya.android.review

import kotobaya.contracts.WordStatus
import kotobaya.contracts.dict.KanjiExampleCandidate
import kotobaya.contracts.dict.KanjiExamplePicker
import kotobaya.contracts.dict.KanjiReadingType
import kotobaya.contracts.dict.KanjiReadings
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.dict.DefaultKanjiExamplePicker
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

/** One dictionary entry containing a kanji, as the on-device dictionary lists it (an input of the picker). */
data class DictWord(val seq: Long, val headword: String, val reading: String, val freqRank: Int?)

/** Words of the installed dictionary that contain a kanji (`entry_kanji`, not retired); empty when none is installed. */
fun interface KanjiWordSource {
    fun words(literal: String): List<DictWord>
}

/** One example word on the back of a kanji card (C-25). */
data class ExampleWord(val seq: Long, val headword: String, val reading: String, val gloss: String, val type: KanjiReadingType)

/**
 * What the back of a card shows besides the template (C-25, ADR-029), computed offline from the replica when the card
 * is loaded (prefetch, off the main thread) and kept for the whole showing. Nothing here is learning data.
 */
data class BackExtras(
    /** Kanji cards: up to 3 example words in display order (never re-ranked). */
    val examples: List<ExampleWord> = emptyList(),
    /** Vocab cards: the names of the first 3 decks holding the note, by priority. */
    val decks: List<String> = emptyList(),
    /** Decks beyond [decks] ("+N more"). */
    val moreDecks: Int = 0,
    /** The note's dictionary entry (the "Open in dictionary" target), if any. */
    val entrySeq: Long? = null,
    /** The note's kanji (the target for a kanji card without an entry), if any. */
    val kanjiLiteral: String? = null,
) {
    companion object {
        const val DECK_CHIPS = 3
    }
}

/**
 * The learning-side inputs of the example-word picker and the merged-note links of the deck chips, read once per queue
 * build (narrow columns, a handful of queries) and reused for every card until the next build, so a card's extras cost
 * O(1) learning queries whatever the number of dictionary words containing its kanji. Seq sets are sorted primitive
 * arrays. Nothing here is learning data of its own; a stale snapshot (until the next build) only affects what the back
 * shows.
 */
class BackExtrasIndex private constructor(
    /** Sorted `word_status.entry_seq` of rows whose status is not `new`, with [statuses] parallel to it. */
    private val statusSeqs: LongArray,
    private val statuses: Array<WordStatus>,
    /** Sorted `entry_seq` of live notes. */
    private val noteSeqs: LongArray,
    /** Sorted `entry_seq` of live `deck_item`s in live decks. */
    private val deckSeqs: LongArray,
    /** note id → ids of the notes merged into it. */
    private val merged: Map<String, List<String>>,
) {
    fun status(seq: Long): WordStatus = statusSeqs.binarySearch(seq).let { if (it >= 0) statuses[it] else WordStatus.NEW }

    fun hasNote(seq: Long): Boolean = noteSeqs.binarySearch(seq) >= 0

    fun inDeck(seq: Long): Boolean = deckSeqs.binarySearch(seq) >= 0

    fun mergedInto(noteId: String): List<String> = merged[noteId].orEmpty()

    /** Collects the index from the narrow rows. */
    class Builder {
        private val status = HashMap<Long, WordStatus>()
        private val notes = HashSet<Long>()
        private val decks = HashSet<Long>()
        private val merged = HashMap<String, MutableList<String>>()

        /** A `word_status` row; `new` and unknown values are left out (absent = `NEW`). */
        fun status(seq: Long, wire: String?): Builder = apply {
            WordStatus.entries.firstOrNull { it.wire == wire }?.takeIf { it != WordStatus.NEW }?.let { status[seq] = it }
        }

        fun note(seq: Long): Builder = apply { notes += seq }

        fun deckItem(seq: Long): Builder = apply { decks += seq }

        fun merged(into: String, noteId: String): Builder = apply { merged.getOrPut(into) { ArrayList() } += noteId }

        fun build(): BackExtrasIndex {
            val sorted = status.keys.toLongArray().also { it.sort() }
            return BackExtrasIndex(
                sorted, Array(sorted.size) { status.getValue(sorted[it]) },
                notes.toLongArray().also { it.sort() }, decks.toLongArray().also { it.sort() },
                merged.mapValues { it.value.sorted() },
            )
        }
    }

    companion object {
        val EMPTY: BackExtrasIndex = Builder().build()

        private fun isNull(v: Any?) = v == null || v == JsonNull
        private fun seqOf(v: JsonValue?): Long? = (v as? JsonNumber)?.value?.toLong()

        /** The index over [LearningTables] (in-memory replicas, tests); the device reads narrow columns instead ([SqlBackExtrasIndex]). */
        fun from(t: LearningTables): BackExtrasIndex {
            val b = Builder()
            t.all(LearningTable.WORD_STATUS).forEach { r -> seqOf(r["entry_seq"])?.let { b.status(it, CardRows.str(r["status"])) } }
            t.all(LearningTable.NOTE).forEach { r ->
                if (isNull(r["deleted_at"])) seqOf(r["entry_seq"])?.let(b::note)
                val into = CardRows.str(r["merged_into_id"])
                val id = CardRows.str(r["id"])
                if (into != null && id != null) b.merged(into, id)
            }
            val liveDecks = t.all(LearningTable.DECK).filter { isNull(it["deleted_at"]) }.mapNotNullTo(HashSet()) { CardRows.str(it["id"]) }
            t.all(LearningTable.DECK_ITEM).forEach { r ->
                if (isNull(r["deleted_at"]) && CardRows.str(r["deck_id"]) in liveDecks) seqOf(r["entry_seq"])?.let(b::deckItem)
            }
            return b.build()
        }
    }
}

/** Reads a [BackExtrasIndex] (once per queue build, off the main thread). */
fun interface BackExtrasIndexSource {
    fun load(): BackExtrasIndex
}

/** Builds [BackExtras] from the learning tables, the per-build [BackExtrasIndex], the dictionary and the core picker. */
internal object BackExtrasBuilder {
    fun build(
        t: LearningTables,
        note: Row,
        kanji: KanjiData?,
        words: KanjiWordSource,
        dictionary: DictionaryLookup,
        index: BackExtrasIndex,
        picker: KanjiExamplePicker = DefaultKanjiExamplePicker,
    ): BackExtras {
        val entry = (note["entry_seq"] as? JsonNumber)?.value?.toLong()
        val literal = CardRows.str(note["kanji_literal"])
        val vocab = CardRows.str(note["type"]) == "vocab" || entry != null
        val (decks, more) = if (vocab) decksOf(t, note, entry, index) else emptyList<String>() to 0
        val examples = if (literal != null) examplesOf(literal, kanji, words, dictionary, index, picker) else emptyList()
        return BackExtras(examples, decks, more, entry, literal)
    }

    private fun isNull(v: Any?) = v == null || v == JsonNull

    /** C-25 "Decks": live non-smart decks with a live item for the note (`n:`, merged-in notes, `e:`), by priority then id. */
    fun decksOf(t: LearningTables, note: Row, entry: Long?, index: BackExtrasIndex): Pair<List<String>, Int> {
        val noteId = CardRows.str(note["id"]) ?: return emptyList<String>() to 0
        if (!isNull(note["deleted_at"])) return emptyList<String>() to 0
        // indexed lookups only (deck_item by note_id / entry_seq, deck by id); merged-in notes come from the per-build index
        val noteIds = listOf(noteId) + index.mergedInto(noteId)
        val items = noteIds.flatMap { t.where(LearningTable.DECK_ITEM, "note_id", JsonString(it)) } +
            (if (entry != null) t.where(LearningTable.DECK_ITEM, "entry_seq", JsonNumber(entry.toDouble())) else emptyList())
        val deckIds = items.filter { isNull(it["deleted_at"]) }.mapNotNull { CardRows.str(it["deck_id"]) }.toSet()
        val decks = deckIds.mapNotNull { id -> t.get(LearningTable.DECK, listOf(JsonString(id))) }
            .filter { isNull(it["deleted_at"]) && CardRows.str(it["type"]) != "smart" }
            .sortedWith(compareBy<Row>({ (it["priority"] as? JsonNumber)?.value ?: 0.0 }, { CardRows.str(it["id"]).orEmpty().lowercase() }))
        val names = decks.map { CardRows.str(it["name"]).orEmpty() }
        return names.take(BackExtras.DECK_CHIPS) to maxOf(0, names.size - BackExtras.DECK_CHIPS)
    }

    private fun examplesOf(
        literal: String,
        kanji: KanjiData?,
        words: KanjiWordSource,
        dictionary: DictionaryLookup,
        index: BackExtrasIndex,
        picker: KanjiExamplePicker,
    ): List<ExampleWord> {
        val dict = words.words(literal)
        if (dict.isEmpty()) return emptyList()
        // no learning query per candidate: status, note and deck membership come from the per-build index
        val candidates = dict.map {
            KanjiExampleCandidate(it.seq, it.headword, it.reading, it.freqRank, index.status(it.seq), index.hasNote(it.seq), index.inDeck(it.seq))
        }
        val readings = KanjiReadings(literal, kanji?.onyomi.orEmpty(), kanji?.kunyomi.orEmpty())
        return picker.pick(readings, candidates).map { ex ->
            val gloss = dictionary.lookup(ex.candidate.seq)?.glosses.orEmpty().joinToString("; ")
            ExampleWord(ex.candidate.seq, ex.candidate.headword, ex.candidate.reading, gloss, ex.readingType)
        }
    }
}
