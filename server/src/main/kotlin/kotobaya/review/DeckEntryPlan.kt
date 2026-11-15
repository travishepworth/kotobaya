package kotobaya.review

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.cards.DefaultCardGenerator
import kotobaya.contracts.cards.CardGenerator
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.queue.AddKanjiMode
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
 * C-27: what materializing a deck entry creates (contracts/core README "C-27"), computed the same way for the queue
 * loader (to tell the core builder the size of the entry's group, `PendingEntry.newKanjiCards`) and for
 * [DeckMaterializer] (to mint it), so the two always agree. One instance per transaction; caches lookups.
 *
 * - Vocab card kinds: `kindsForNewNote(vocab type, deck card_kinds)`; if that is empty (card_kinds `[]` or no valid
 *   kind) the type's default kinds; then every kind whose front would render empty for the entry is dropped
 *   (`DefaultCardRenderer.frontIsEmpty` with the entry's dictionary data; e.g. `listening` without audio). An entry
 *   left with no kind is never drawn nor created.
 * - Kanji notes (only when the drawing deck's `add_kanji` is present and not `off`): `kanjiToAdd(headword, blocked,
 *   mode)`, `blocked` = the headword's kanji that have a live kanji note **or a user-deleted one** (`deleted_at` set,
 *   no `merged_into_id`): a kanji note the user deleted is never re-created. Each with the kanji type's default kinds
 *   minus empty fronts (with `ref.kanji` data).
 */
internal class DeckEntryPlan(private val dsl: DSLContext, private val cards: CardGenerator = DefaultCardGenerator) {
    private val views = NoteViews(dsl)
    val vocabType: NoteTypeInfo = views.noteType(BuiltInNoteTypes.VOCAB.id)?.toNoteTypeInfo() ?: BuiltInNoteTypes.VOCAB.info
    val kanjiType: NoteTypeInfo? = views.noteType(BuiltInNoteTypes.KANJI.id)?.toNoteTypeInfo()
    private val blockedKanji = HashMap<String, Boolean>()
    private val kanjiKindsCache = HashMap<String, List<String>>()

    /** `PendingEntry.expressionKanji`: what the loader passes as `QueueCandidate.expressionKanji` once the note exists. */
    fun kanjiOf(headword: String): List<String> = ReviewViews.kanjiOf(headword)

    /** Card kinds of the new vocab note for [entry], drawn by a deck whose `card_kinds` is [deckCardKinds]. */
    fun vocabKinds(entry: EntryDict, deckCardKinds: List<String>?): List<String> {
        val kinds = cards.kindsForNewNote(vocabType, deckCardKinds).ifEmpty { cards.kindsForNewNote(vocabType, null) }
        val dict = entry.dictionary()
        return kinds.filterNot { DefaultCardRenderer.frontIsEmpty(vocabType, it, emptyMap(), dictionary = dict) }
    }

    /** Literals of the kanji notes materializing [headword] creates under the deck's present [mode] (null = absent). */
    fun kanjiToCreate(headword: String, mode: AddKanjiMode?): List<String> {
        if (mode == null || mode == AddKanjiMode.OFF || kanjiType == null) return emptyList()
        val literals = cards.kanjiOf(headword)
        loadBlocked(literals)
        return cards.kanjiToAdd(headword, literals.filterTo(HashSet()) { blockedKanji[it] == true }, mode)
    }

    /** Card kinds of a new kanji note for [literal]. */
    fun kanjiKinds(literal: String): List<String> {
        val type = kanjiType ?: return emptyList()
        return kanjiKindsCache.getOrPut(literal) {
            val data = kanjiData(literal)
            cards.kindsForNewNote(type, null).filterNot { DefaultCardRenderer.frontIsEmpty(type, it, emptyMap(), kanji = data) }
        }
    }

    /** `PendingEntry.newKanjiCards`: the kanji cards the materialization creates, at most 1 per note when [burySiblings]. */
    fun newKanjiCards(headword: String, mode: AddKanjiMode?, burySiblings: Boolean): Int =
        kanjiToCreate(headword, mode).sumOf { l -> kanjiKinds(l).size.let { if (burySiblings) minOf(1, it) else it } }

    /** Marks a literal as having a live kanji note (created in this transaction). */
    fun kanjiCreated(literal: String) {
        blockedKanji[literal] = true
    }

    private fun loadBlocked(literals: List<String>) {
        val missing = literals.filter { it !in blockedKanji }
        if (missing.isEmpty()) return
        missing.forEach { blockedKanji[it] = false }
        dsl.fetch(
            "select distinct kanji_literal from app.note where kanji_literal = any(?::text[]) " +
                "and (deleted_at is null or merged_into_id is null)",
            missing.toTypedArray(),
        ).forEach { blockedKanji[it.get(0, String::class.java)] = true }
    }

    private fun kanjiData(literal: String): KanjiData {
        val r = dsl.fetchOptional(
            "select array_to_json(meanings)::text, array_to_json(on_readings)::text, array_to_json(kun_readings)::text, stroke_count " +
                "from ref.kanji where literal = ?",
            literal,
        ).orElse(null) ?: return KanjiData(literal, emptyList(), emptyList(), emptyList(), null)
        fun list(i: Int) = r.get(i, String::class.java)?.let { json.readTree(it).map { n -> n.asText() } }.orEmpty()
        return KanjiData(literal, list(0), list(1), list(2), (r.get(3) as Number?)?.toInt())
    }
}
