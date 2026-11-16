package kotobaya.cards

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.CardKinds
import kotobaya.contracts.cards.TemplateInfo
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals

class CardGeneratorTest {
    private val gen = DefaultCardGenerator

    @Test
    fun defaultCardsPerNoteType() {
        val expected = listOf(
            BuiltInNoteTypes.VOCAB to listOf("meaning"),
            BuiltInNoteTypes.KANJI to listOf("keyword"),
            BuiltInNoteTypes.PHRASE to listOf("meaning"),
            BuiltInNoteTypes.GRAMMAR to listOf("cloze"),
            BuiltInNoteTypes.SENTENCE to listOf("comprehension"),
            BuiltInNoteTypes.CUSTOM to listOf("basic"),
        )
        for ((type, kinds) in expected) assertEquals(kinds, gen.kindsForNewNote(type.info, null), type.name)
        // ADR-035: with `cards.reading_enabled` true the template defaults come back unchanged.
        assertEquals(listOf("meaning", "reading"), gen.kindsForNewNote(BuiltInNoteTypes.VOCAB.info, null, readingEnabled = true))
        for ((type, kinds) in expected.drop(1)) assertEquals(kinds, gen.kindsForNewNote(type.info, null, readingEnabled = true), type.name)
    }

    // ---- ADR-035: reading cards retired unless `cards.reading_enabled` ----

    @Test
    fun deckCardKindsReadingOnlyGivesNothingAndMaterializationFallsBackToMeaning() {
        val vocab = BuiltInNoteTypes.VOCAB.info
        val off = gen.kindsForNewNote(vocab, listOf("reading"))
        assertEquals(emptyList(), off)
        // contracts/core README C-27 step 2: empty -> the type's default kinds (same flag) -> meaning only.
        assertEquals(listOf("meaning"), off.ifEmpty { gen.kindsForNewNote(vocab, null, readingEnabled = false) })
        assertEquals(listOf("reading"), gen.kindsForNewNote(vocab, listOf("reading"), readingEnabled = true))
        assertEquals(listOf("meaning", "listening"), gen.kindsForNewNote(vocab, listOf("reading", "meaning", "listening", "reading")))
        assertEquals(listOf("reading", "meaning"), gen.kindsForNewNote(vocab, listOf("reading", "meaning"), readingEnabled = true))
    }

    @Test
    fun missingKindsNeverReturnsRetiredReading() {
        val vocab = BuiltInNoteTypes.VOCAB.info
        assertEquals(listOf("pitch"), gen.missingKinds(vocab, setOf("meaning"), listOf("reading", "pitch")))
        assertEquals(listOf("reading", "pitch"), gen.missingKinds(vocab, setOf("meaning"), listOf("reading", "pitch"), readingEnabled = true))
    }

    @Test
    fun onlyVocabBaseTypeIsRetiredIncludingCustomVocabTypes() {
        val customVocab = BuiltInNoteTypes.VOCAB.info.copy(css = ".x{}") // any type whose base_type is vocab
        assertEquals(listOf("meaning"), gen.kindsForNewNote(customVocab, null))
        // A phrase type with a kind named "reading" keeps it.
        val phraseWithReading = BuiltInNoteTypes.PHRASE.info.let {
            it.copy(templates = it.templates + TemplateInfo("reading", true, "{{reading}}", null))
        }
        assertEquals(listOf("meaning", "reading"), gen.kindsForNewNote(phraseWithReading, null))
        assertEquals(listOf("reading"), gen.missingKinds(phraseWithReading, setOf("meaning"), listOf("reading")))
    }

    @Test
    fun cardKindsPredicateAndSettingBoolTest() {
        assertEquals("cards.reading_enabled", CardKinds.SETTING_READING_ENABLED)
        assertEquals(false, CardKinds.READING_ENABLED_DEFAULT)
        assertEquals(true, CardKinds.readingEnabled(JsonBool(true)))
        for (v in listOf(null, JsonNull, JsonBool(false), JsonNumber(1.0), JsonString("true"))) {
            assertEquals(false, CardKinds.readingEnabled(v), "$v")
        }
        assertEquals(true, CardKinds.isRetired(NoteBaseType.VOCAB, "reading", false))
        assertEquals(false, CardKinds.isRetired(NoteBaseType.VOCAB, "reading", true))
        assertEquals(false, CardKinds.isRetired(NoteBaseType.VOCAB, "meaning", false))
        assertEquals(false, CardKinds.isRetired(NoteBaseType.VOCAB, "Reading", false))
        for (b in NoteBaseType.entries.filter { it != NoteBaseType.VOCAB }) {
            assertEquals(false, CardKinds.isRetired(b, "reading", false), b.name)
        }
    }

    @Test
    fun deckCardKindsFilterAndDedupe() {
        val vocab = BuiltInNoteTypes.VOCAB.info
        assertEquals(listOf("listening", "meaning"), gen.kindsForNewNote(vocab, listOf("listening", "foo", "meaning", "listening")))
        assertEquals(listOf("meaning"), gen.kindsForNewNote(vocab, listOf("meaning")))
        assertEquals(emptyList(), gen.kindsForNewNote(vocab, emptyList()))
    }

    @Test
    fun enablingOptionalKindCreatesForExistingNotes() {
        val vocab = BuiltInNoteTypes.VOCAB.info
        val live = setOf("meaning", "reading")
        assertEquals(listOf("listening", "pitch"), gen.missingKinds(vocab, live, listOf("listening", "pitch", "meaning", "nope")))
    }

    @Test
    fun disablingOptionalKindCreatesNothing() {
        val vocab = BuiltInNoteTypes.VOCAB.info
        val live = setOf("meaning", "reading", "listening")
        // the request no longer names listening: nothing to create, and nothing is ever deleted
        assertEquals(emptyList(), gen.missingKinds(vocab, live, listOf("meaning", "reading")))
        assertEquals(emptyList(), gen.missingKinds(vocab, live, emptyList()))
    }

    @Test
    fun kanjiExtraction() {
        assertEquals(listOf("食", "物"), gen.kanjiOf("食べ物々"))
        assertEquals(listOf("𠮷", "野"), gen.kanjiOf("𠮷野𠮷"))
        assertEquals(listOf("物"), gen.kanjiToAdd("食べ物", setOf("食"), AddKanjiMode.BEFORE))
        assertEquals(emptyList(), gen.kanjiToAdd("食べ物", emptySet(), AddKanjiMode.OFF))
    }
}
