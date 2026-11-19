package kotobaya.decks

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.decks.DeckProgressCount
import kotobaya.contracts.decks.DeckWord
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.WordCardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR-041: the `DeckProgress` KDoc example and edge cases. */
class DeckProgressTest {
    private val p = DefaultDeckProgress

    private fun w(key: String, status: WordStatus?, vararg cards: WordCardState) =
        DeckWord(key, status, if (cards.isEmpty()) null else NoteBaseType.VOCAB, cards.toList())

    private fun c(state: CardState, kind: String = "meaning", interval: Int? = null) = WordCardState(state, interval, kind)

    @Test
    fun kdocExample() {
        val words = listOf(
            w("e:1", WordStatus.LEARNING, c(CardState.REVIEW, interval = 12)),
            w("e:2", null),
            w("e:3", WordStatus.KNOWN),
            w("e:4", WordStatus.BLACKLISTED, c(CardState.REVIEW)),
            w("n:5", null, c(CardState.LEARNING)),
        )
        val count = p.count(words)
        assertEquals(DeckProgressCount(learned = 2, total = 4), count)
        assertEquals(50, count.percent)
    }

    @Test
    fun edgeCases() {
        assertEquals(DeckProgressCount(0, 0), p.count(emptyList()))
        assertEquals(0, p.count(emptyList()).percent)
        // Marked known (or never forget) without a note: learned.
        assertTrue(p.isLearned(w("e:1", WordStatus.KNOWN)))
        assertTrue(p.isLearned(w("e:1", WordStatus.NEVER_FORGET)))
        // Only a retired reading card in REVIEW (reading cards off): not learned; with reading cards on: learned.
        val readingOnly = w("e:2", WordStatus.LEARNING, c(CardState.REVIEW, kind = "reading"), c(CardState.NEW))
        assertFalse(p.isLearned(readingOnly, readingEnabled = false))
        assertTrue(p.isLearned(readingOnly, readingEnabled = true))
        // A REVIEW and a NEW card: learned; relearning (lapsed) only: not learned; suspension is irrelevant.
        assertTrue(p.isLearned(w("e:3", null, c(CardState.REVIEW), c(CardState.NEW))))
        assertFalse(p.isLearned(w("e:4", null, c(CardState.RELEARNING))))
        assertFalse(p.isLearned(w("e:5", null, c(CardState.NEW))))
        // BLACKLISTED / IGNORED are in neither number, even when known through their cards.
        for (s in listOf(WordStatus.BLACKLISTED, WordStatus.IGNORED)) {
            assertFalse(p.counts(w("e:6", s)))
            assertFalse(p.isLearned(w("e:6", s, c(CardState.REVIEW))))
        }
        // A word with no status and no note counts (entry missing, or not studied yet).
        assertTrue(p.counts(w("e:7", null)))
        // A card of unknown kind is never retired.
        assertTrue(p.isLearned(DeckWord("e:8", null, NoteBaseType.VOCAB, listOf(WordCardState(CardState.REVIEW, 30, null)))))
        // Duplicate keys: the first counts.
        assertEquals(DeckProgressCount(1, 1), p.count(listOf(w("e:9", WordStatus.KNOWN), w("e:9", null))))
    }

    @Test
    fun percentRoundsDown() {
        assertEquals(0, DeckProgressCount(0, 0).percent)
        assertEquals(33, DeckProgressCount(1, 3).percent)
        assertEquals(66, DeckProgressCount(2, 3).percent)
        assertEquals(100, DeckProgressCount(3, 3).percent)
        assertEquals(99, DeckProgressCount(199, 200).percent)
        assertEquals(19, DeckProgressCount(412, 2143).percent)
    }

    @Test
    fun parityScenarioFirstDeckless() {
        // queue-kanji-first-deckless step 2: 先生 has a new card, うっかり has no note.
        val words = listOf(w("e:1387990", WordStatus.NEW, c(CardState.NEW)), w("e:1001010", null))
        assertEquals(DeckProgressCount(0, 2), p.count(words))
    }
}
