package kotobaya.android.notes

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.cards.DefaultCardGenerator
import kotobaya.contracts.cards.CardGenerator
import kotobaya.contracts.cards.CardKinds
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonString
import kotobaya.render.DefaultCardRenderer
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

/**
 * C-27: what materializing a deck entry creates (contracts/core README "C-27"), computed the same way for the queue
 * loader (the size of the entry's add_kanji group, `PendingEntry.newKanjiCards`) and for [NoteCreator.materialize] (the
 * ops), so the two always agree. The device twin of the server's `DeckEntryPlan`. One instance per transaction or
 * read; caches its lookups.
 *
 * - Vocab card kinds: `kindsForNewNote(vocab type, deck card_kinds, cards.reading_enabled)`; if that is empty
 *   (card_kinds `[]`, no valid kind, or only retired kinds — ADR-035) the type's default kinds; then every kind whose front would render empty for the entry is dropped. An entry
 *   left with no kind is never passed nor created.
 * - Kanji notes, only when the drawing deck's `add_kanji` is present and not `off`: `kanjiToAdd(headword, blocked,
 *   mode)`, `blocked` = the headword's kanji with a live kanji note or a user-deleted one (`deleted_at` set, no
 *   `merged_into_id`): a kanji note the user deleted is never re-created. Each with the kanji type's default kinds
 *   minus empty fronts (with the kanji's dictionary data, or the literal alone).
 */
class DeckEntryPlan(
    private val tables: LearningTables,
    private val dictionary: (Long) -> DictionaryData?,
    private val kanjiData: (String) -> KanjiData?,
    private val cards: CardGenerator = DefaultCardGenerator,
) {
    val vocabType: NoteTypeInfo = liveType(BuiltInNoteTypes.VOCAB.id) ?: BuiltInNoteTypes.VOCAB.info
    val kanjiType: NoteTypeInfo? = liveType(BuiltInNoteTypes.KANJI.id)
    private val dicts = HashMap<Long, DictionaryData?>()
    private val decks = HashMap<String, Row?>()
    private val blocked = HashMap<String, Boolean>()
    private val kanjiKindsCache = HashMap<String, List<String>>()

    /** `cards.reading_enabled` (ADR-035, SCHEMA.md 6.4) as the replica stands now: false = no vocab reading card. */
    val readingEnabled: Boolean by lazy { readingEnabled(tables) }

    /** The entry's dictionary data; null = not installed on this device (the entry is skipped until it is). */
    fun dict(seq: Long): DictionaryData? = dicts.getOrPut(seq) { runCatching { dictionary(seq) }.getOrNull() }

    /** The live deck row, or null. */
    fun deck(id: String): Row? = decks.getOrPut(id) {
        tables.get(LearningTable.DECK, listOf(JsonString(id)))?.takeIf { (it["deleted_at"] ?: JsonNull) == JsonNull }
    }

    /** Card kinds of the new vocab note for [seq] drawn by [deck]; empty = the entry is never materialized. */
    fun vocabKinds(seq: Long, deck: Row?): List<String> {
        val dict = dict(seq) ?: return emptyList()
        val kinds = cards.kindsForNewNote(vocabType, deck?.let(NoteCreator.Companion::cardKinds), readingEnabled)
            .ifEmpty { cards.kindsForNewNote(vocabType, null, readingEnabled) }
        return kinds.filterNot { DefaultCardRenderer.frontIsEmpty(vocabType, it, emptyMap(), dictionary = dict) }
    }

    /** Literals of the kanji notes materializing [headword] for [deck] creates (none unless its add_kanji is present and not off). */
    fun kanjiToCreate(headword: String, deck: Row?): List<String> {
        val mode = deck?.let(NoteCreator.Companion::addKanjiMode) ?: AddKanjiMode.OFF
        if (mode == AddKanjiMode.OFF || kanjiType == null) return emptyList()
        val literals = cards.kanjiOf(headword)
        return cards.kanjiToAdd(headword, literals.filterTo(HashSet()) { isBlocked(it) }, mode)
    }

    /** Card kinds of a new kanji note for [literal]. */
    fun kanjiKinds(literal: String): List<String> {
        val type = kanjiType ?: return emptyList()
        return kanjiKindsCache.getOrPut(literal) {
            val data = runCatching { kanjiData(literal) }.getOrNull() ?: KanjiData(literal, emptyList(), emptyList(), emptyList())
            cards.kindsForNewNote(type, null, readingEnabled).filterNot { DefaultCardRenderer.frontIsEmpty(type, it, emptyMap(), kanji = data) }
        }
    }

    /** `PendingEntry.newKanjiCards`: the kanji cards the materialization creates, at most 1 per note when [burySiblings]. */
    fun newKanjiCards(headword: String, deck: Row?, burySiblings: Boolean): Int =
        kanjiToCreate(headword, deck).sumOf { l -> kanjiKinds(l).size.let { if (burySiblings) minOf(1, it) else it } }

    /** A kanji note for [literal] was created in this transaction: it now blocks the literal. */
    fun kanjiCreated(literal: String) {
        blocked[literal] = true
    }

    private fun isBlocked(literal: String): Boolean = blocked.getOrPut(literal) {
        tables.where(LearningTable.NOTE, "kanji_literal", JsonString(literal)).any { n ->
            (n["deleted_at"] ?: JsonNull) == JsonNull || (n["merged_into_id"] ?: JsonNull) == JsonNull
        }
    }

    private fun liveType(id: String): NoteTypeInfo? =
        tables.get(LearningTable.NOTE_TYPE, listOf(JsonString(id)))?.takeIf { (it["deleted_at"] ?: JsonNull) == JsonNull }
            ?.let(NoteCreator.Companion::noteTypeInfo)

    companion object {
        private val HTML_TAG = Regex("<[^>]*>")

        /** `PendingEntry.expressionKanji`: what the queue loader passes as the vocab card's `expressionKanji` (its headword's). */
        fun expressionKanji(headword: String): List<String> = DefaultCardGenerator.kanjiOf(headword.replace(HTML_TAG, ""))
    }
}

/** The typed setting `cards.reading_enabled` from the replica's `settings` row (ADR-035; no row or not JSON true = false). */
internal fun readingEnabled(tables: LearningTables): Boolean =
    CardKinds.readingEnabled(tables.get(LearningTable.SETTINGS, listOf(JsonString(CardKinds.SETTING_READING_ENABLED)))?.get("value"))
