package kotobaya.android.notes

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.cards.DefaultCardGenerator
import kotobaya.contracts.cards.CardGenerator
import kotobaya.contracts.cards.CardKinds
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonString
import kotobaya.render.DefaultCardRenderer
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

/**
 * C-27: what materializing a served build creates (contracts/core README "C-27"), computed the same way for the queue
 * loader (the card kinds of a kanji note to mint, `MissingKanji.newCards`) and for [NoteCreator.materialize] (the
 * ops), so the two always agree. The device twin of the server's `DeckEntryPlan`. One instance per transaction or
 * read; caches its lookups.
 *
 * - Vocab card kinds: `kindsForNewNote(vocab type, deck card_kinds, cards.reading_enabled)`; if that is empty
 *   (card_kinds `[]`, no valid kind, or only retired kinds — ADR-035) the type's default kinds; then every kind whose front would render empty for the entry is dropped. An entry
 *   left with no kind is never passed nor created.
 * - Kanji notes (ADR-040), whatever a deck's `add_kanji` says: [kanjiBlocked] literals (a live kanji note or a
 *   user-deleted one, never re-created) are skipped; each new one gets the kanji type's default kinds minus empty fronts
 *   (with the kanji's dictionary data, or the literal alone).
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

    /** Card kinds of a new kanji note for [literal]. */
    fun kanjiKinds(literal: String): List<String> {
        val type = kanjiType ?: return emptyList()
        return kanjiKindsCache.getOrPut(literal) {
            val data = runCatching { kanjiData(literal) }.getOrNull() ?: KanjiData(literal, emptyList(), emptyList(), emptyList())
            cards.kindsForNewNote(type, null, readingEnabled).filterNot { DefaultCardRenderer.frontIsEmpty(type, it, emptyMap(), kanji = data) }
        }
    }

    /** True iff a live kanji note or a user-deleted one (`deleted_at` set, no `merged_into_id`) holds [literal]: it is never created. */
    fun kanjiBlocked(literal: String): Boolean = isBlocked(literal)

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
