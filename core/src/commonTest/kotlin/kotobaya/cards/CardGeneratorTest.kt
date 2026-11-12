package kotobaya.cards

import kotobaya.contracts.queue.AddKanjiMode
import kotlin.test.Test
import kotlin.test.assertEquals

class CardGeneratorTest {
    private val gen = DefaultCardGenerator

    @Test
    fun defaultCardsPerNoteType() {
        val expected = listOf(
            BuiltInNoteTypes.VOCAB to listOf("meaning", "reading"),
            BuiltInNoteTypes.KANJI to listOf("keyword"),
            BuiltInNoteTypes.PHRASE to listOf("meaning"),
            BuiltInNoteTypes.GRAMMAR to listOf("cloze"),
            BuiltInNoteTypes.SENTENCE to listOf("comprehension"),
            BuiltInNoteTypes.CUSTOM to listOf("basic"),
        )
        for ((type, kinds) in expected) assertEquals(kinds, gen.kindsForNewNote(type.info, null), type.name)
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
