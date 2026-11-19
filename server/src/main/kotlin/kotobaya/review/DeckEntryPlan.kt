package kotobaya.review

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.cards.DefaultCardGenerator
import kotobaya.cards.ReadingSetting
import kotobaya.contracts.cards.CardGenerator
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.queue.MissingKanji
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FuriganaSegment
import kotobaya.contracts.render.KanjiData
import kotobaya.notes.NoteViews
import kotobaya.notes.toNoteTypeInfo
import kotobaya.render.DefaultCardRenderer
import kotobaya.server.json
import org.jooq.DSLContext

/** A deck entry's dictionary row, as materialization needs it. */
internal class EntryDict(val seq: Long, val headword: String, val reading: String, val glosses: List<String>) {
    fun dictionary(): DictionaryData = DictionaryData(
        expression = headword,
        reading = reading,
        furigana = listOf(FuriganaSegment(headword, reading.takeUnless { it == headword })),
        glosses = glosses,
        pitchDrops = emptyList(),
    )

    companion object {
        /** Glosses of `ref.dict_entry.senses` (`[{"gloss":[{"text":..}]}]`), in order. */
        fun glossesOf(sensesJson: String?): List<String> = sensesJson?.let { json.readTree(it) }
            ?.flatMap { s -> s["gloss"]?.mapNotNull { g -> g["text"]?.takeIf { it.isTextual }?.asText() }.orEmpty() }.orEmpty()
    }
}

/**
 * C-27: what materializing creates (contracts/core README "C-27"), computed the same way for the queue loader (to tell
 * the core builder which required kanji are missing, `missingKanji`) and for [DeckMaterializer] (to mint), so the two always agree. One instance per transaction; caches lookups.
 *
 * - Vocab card kinds: `kindsForNewNote(vocab type, deck card_kinds, cards.reading_enabled)`; if that is empty (card_kinds `[]` or no valid
 *   kind) the type's default kinds; then every kind whose front would render empty for the entry is dropped
 *   (`DefaultCardRenderer.frontIsEmpty` with the entry's dictionary data; e.g. `listening` without audio). An entry
 *   left with no kind is never drawn nor created.
 * - Kanji notes (ADR-040/042: whatever a deck's `add_kanji` says): only the core's required kanji, unless `blocked` = a
 *   live kanji note **or a user-deleted one** (`deleted_at` set, no `merged_into_id`) holds the literal: a kanji note the
 *   user deleted is never re-created. Each with the kanji type's default kinds minus empty fronts (with `ref.kanji` data).
 */
internal class DeckEntryPlan(
    private val dsl: DSLContext,
    private val cards: CardGenerator = DefaultCardGenerator,
    /** `cards.reading_enabled` (ADR-035), read once per plan; passed to every generator call. */
    private val readingEnabled: Boolean = ReadingSetting.load(dsl),
) {
    private val views = NoteViews(dsl)
    val vocabType: NoteTypeInfo = views.noteType(BuiltInNoteTypes.VOCAB.id)?.toNoteTypeInfo() ?: BuiltInNoteTypes.VOCAB.info
    val kanjiType: NoteTypeInfo? = views.noteType(BuiltInNoteTypes.KANJI.id)?.toNoteTypeInfo()
    private val blockedKanji = HashMap<String, Boolean>()
    private val kanjiKindsCache = HashMap<String, List<String>>()

    /** Card kinds of the new vocab note for [entry], drawn by a deck whose `card_kinds` is [deckCardKinds]. */
    fun vocabKinds(entry: EntryDict, deckCardKinds: List<String>?): List<String> {
        val kinds = cards.kindsForNewNote(vocabType, deckCardKinds, readingEnabled)
            .ifEmpty { cards.kindsForNewNote(vocabType, null, readingEnabled) }
        val dict = entry.dictionary()
        return kinds.filterNot { DefaultCardRenderer.frontIsEmpty(vocabType, it, emptyMap(), dictionary = dict) }
    }

    /** Loads the blocked set and the `ref.kanji` data of the union [required] once (two statements), so [missingKanji] per word is free. */
    fun prepare(required: Collection<String>) {
        val all = required.distinct()
        loadBlocked(all)
        prefetchKanji(all.filter { blockedKanji[it] != true })
    }

    /**
     * `MissingKanji` of a word whose required kanji are [required] (ADR-040/042, contracts/core README "C-27"): the literals
     * with no live kanji note and no user-deleted one that step A would give at least one card, in required order.
     * `newCards` = the kinds step A creates, at most 1 when [burySiblings].
     */
    fun missingKanji(required: List<String>, burySiblings: Boolean): List<MissingKanji> {
        if (required.isEmpty() || kanjiType == null) return emptyList()
        loadBlocked(required)
        prefetchKanji(required.filter { blockedKanji[it] != true })
        return required.mapNotNull { l ->
            if (blockedKanji[l] == true) return@mapNotNull null
            val n = kanjiKinds(l).size.let { if (burySiblings) minOf(1, it) else it }
            if (n >= 1) MissingKanji(l, n) else null
        }
    }

    /** True iff a live kanji note or a user-deleted one (`deleted_at` set, no `merged_into_id`) holds [literal]. */
    fun blocked(literal: String): Boolean {
        loadBlocked(listOf(literal))
        return blockedKanji[literal] == true
    }

    /** Card kinds of a new kanji note for [literal]. */
    fun kanjiKinds(literal: String): List<String> {
        val type = kanjiType ?: return emptyList()
        return kanjiKindsCache.getOrPut(literal) {
            val data = kanjiData(literal)
            cards.kindsForNewNote(type, null, readingEnabled).filterNot { DefaultCardRenderer.frontIsEmpty(type, it, emptyMap(), kanji = data) }
        }
    }

    /** Marks a literal as having a live kanji note (created in this transaction). */
    fun kanjiCreated(literal: String) {
        blockedKanji[literal] = true
    }

    private fun loadBlocked(literals: List<String>) {
        val missing = literals.filter { it !in blockedKanji }
        if (missing.isEmpty()) return
        missing.forEach { blockedKanji[it] = false }
        dsl.fetch(
            "select distinct kanji_literal from app.note where kanji_literal = any(array(select unnest(?::text[]))) " +
                "and (deleted_at is null or merged_into_id is null)",
            missing.toTypedArray(),
        ).forEach { blockedKanji[it.get(0, String::class.java)] = true }
    }

    private val kanjiDataCache = HashMap<String, KanjiData>()

    /** One statement for the `ref.kanji` rows of [literals] not cached yet (a literal without a row caches the bare literal). */
    private fun prefetchKanji(literals: List<String>) {
        val missing = literals.filter { it !in kanjiDataCache }.distinct()
        if (missing.isEmpty()) return
        missing.forEach { kanjiDataCache[it] = KanjiData(it, emptyList(), emptyList(), emptyList(), null) }
        dsl.fetch(
            "select literal, array_to_json(meanings)::text, array_to_json(on_readings)::text, array_to_json(kun_readings)::text, stroke_count " +
                "from ref.kanji where literal = any(array(select unnest(?::text[])))",
            missing.toTypedArray(),
        ).forEach { r ->
            fun list(i: Int) = r.get(i, String::class.java)?.let { json.readTree(it).map { n -> n.asText() } }.orEmpty()
            val literal = r.get(0, String::class.java)
            kanjiDataCache[literal] = KanjiData(literal, list(1), list(2), list(3), (r.get(4) as Number?)?.toInt())
        }
    }

    private fun kanjiData(literal: String): KanjiData {
        prefetchKanji(listOf(literal))
        return checkNotNull(kanjiDataCache[literal])
    }
}
