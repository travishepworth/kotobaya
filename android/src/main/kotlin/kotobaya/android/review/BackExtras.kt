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

/** Builds [BackExtras] from the learning tables, the dictionary and the core picker. */
internal object BackExtrasBuilder {
    fun build(
        t: LearningTables,
        note: Row,
        kanji: KanjiData?,
        words: KanjiWordSource,
        dictionary: DictionaryLookup,
        picker: KanjiExamplePicker = DefaultKanjiExamplePicker,
    ): BackExtras {
        val entry = (note["entry_seq"] as? JsonNumber)?.value?.toLong()
        val literal = CardRows.str(note["kanji_literal"])
        val vocab = CardRows.str(note["type"]) == "vocab" || entry != null
        val (decks, more) = if (vocab) decksOf(t, note, entry) else emptyList<String>() to 0
        val examples = if (literal != null) examplesOf(t, literal, kanji, words, dictionary, picker) else emptyList()
        return BackExtras(examples, decks, more, entry, literal)
    }

    private fun isNull(v: Any?) = v == null || v == JsonNull

    /** C-25 "Decks": live non-smart decks with a live item for the note (`n:`, merged-in notes, `e:`), by priority then id. */
    fun decksOf(t: LearningTables, note: Row, entry: Long?): Pair<List<String>, Int> {
        val noteId = CardRows.str(note["id"]) ?: return emptyList<String>() to 0
        if (!isNull(note["deleted_at"])) return emptyList<String>() to 0
        // indexed lookups only (deck_item by note_id / entry_seq); merged-in notes are rare and found by a WHERE scan
        val noteIds = buildList {
            add(noteId)
            t.where(LearningTable.NOTE, "merged_into_id", JsonString(noteId)).forEach { m -> CardRows.str(m["id"])?.let { add(it) } }
        }
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
        t: LearningTables,
        literal: String,
        kanji: KanjiData?,
        words: KanjiWordSource,
        dictionary: DictionaryLookup,
        picker: KanjiExamplePicker,
    ): List<ExampleWord> {
        val dict = words.words(literal)
        if (dict.isEmpty()) return emptyList()
        // per-candidate point lookups on indexed columns (word_status PK, note.entry_seq, deck_item.entry_seq); never a table read
        val liveDeck = HashMap<String, Boolean>()
        fun deckLive(id: String) = liveDeck.getOrPut(id) {
            t.get(LearningTable.DECK, listOf(JsonString(id)))?.let { isNull(it["deleted_at"]) } ?: false
        }
        fun statusOf(seq: Long): WordStatus =
            t.get(LearningTable.WORD_STATUS, listOf(JsonNumber(seq.toDouble())))?.let { r -> WordStatus.entries.firstOrNull { it.wire == CardRows.str(r["status"]) } } ?: WordStatus.NEW
        fun hasNote(seq: Long) = t.where(LearningTable.NOTE, "entry_seq", JsonNumber(seq.toDouble())).any { isNull(it["deleted_at"]) }
        fun inDeck(seq: Long) = t.where(LearningTable.DECK_ITEM, "entry_seq", JsonNumber(seq.toDouble()))
            .any { isNull(it["deleted_at"]) && CardRows.str(it["deck_id"])?.let(::deckLive) == true }
        val candidates = dict.map {
            KanjiExampleCandidate(it.seq, it.headword, it.reading, it.freqRank, statusOf(it.seq), hasNote(it.seq), inDeck(it.seq))
        }
        val readings = KanjiReadings(literal, kanji?.onyomi.orEmpty(), kanji?.kunyomi.orEmpty())
        return picker.pick(readings, candidates).map { ex ->
            val gloss = dictionary.lookup(ex.candidate.seq)?.glosses.orEmpty().joinToString("; ")
            ExampleWord(ex.candidate.seq, ex.candidate.headword, ex.candidate.reading, gloss, ex.readingType)
        }
    }
}
