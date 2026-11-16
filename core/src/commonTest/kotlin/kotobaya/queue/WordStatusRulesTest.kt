package kotobaya.queue

import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.WordCardState
import kotobaya.contracts.queue.WordStatusRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WordStatusRulesTest {
    private val rules = DefaultWordStatusRules
    private fun c(state: CardState, interval: Int? = null) = WordCardState(state, interval)

    @Test
    fun noLiveNoteIsNull() {
        assertNull(rules.autoStatus(false, listOf(c(CardState.REVIEW, 100))))
    }

    @Test
    fun emptyOrAllNewIsNew() {
        assertEquals(WordStatus.NEW, rules.autoStatus(true, emptyList()))
        assertEquals(WordStatus.NEW, rules.autoStatus(true, listOf(c(CardState.NEW), c(CardState.NEW))))
    }

    @Test
    fun knownOnlyWhenAllStartedCardsAreReviewWithInterval21OrMore() {
        val k = WordStatusRules.KNOWN_INTERVAL_DAYS
        assertEquals(21, k)
        assertEquals(WordStatus.KNOWN, rules.autoStatus(true, listOf(c(CardState.REVIEW, 21))))
        // NEW cards are not started and don't block KNOWN.
        assertEquals(WordStatus.KNOWN, rules.autoStatus(true, listOf(c(CardState.REVIEW, 30), c(CardState.NEW))))
        assertEquals(WordStatus.LEARNING, rules.autoStatus(true, listOf(c(CardState.REVIEW, 21), c(CardState.REVIEW, 20))))
        assertEquals(WordStatus.LEARNING, rules.autoStatus(true, listOf(c(CardState.REVIEW, 30), c(CardState.LEARNING, 0))))
        assertEquals(WordStatus.LEARNING, rules.autoStatus(true, listOf(c(CardState.RELEARNING, 40))))
        assertEquals(WordStatus.LEARNING, rules.autoStatus(true, listOf(c(CardState.REVIEW, null))))
    }

    /** ADR-035: retired (vocab `reading`) cards are ignored while `cards.reading_enabled` is not true. */
    @Test
    fun retiredReadingCardsAreIgnored() {
        fun k(state: CardState, interval: Int?, kind: String) = WordCardState(state, interval, kind)
        val meaningNewReadingLearning = listOf(k(CardState.NEW, null, "meaning"), k(CardState.LEARNING, 0, "reading"))
        val meaningMatureReadingLearning = listOf(k(CardState.REVIEW, 30, "meaning"), k(CardState.LEARNING, 0, "reading"))
        val readingOnlyMature = listOf(k(CardState.REVIEW, 40, "reading"))
        // default (setting absent) = false
        assertEquals(WordStatus.NEW, rules.autoStatus(true, meaningNewReadingLearning))
        assertEquals(WordStatus.KNOWN, rules.autoStatus(true, meaningMatureReadingLearning))
        assertEquals(WordStatus.NEW, rules.autoStatus(true, readingOnlyMature, readingEnabled = false))
        assertNull(rules.autoStatus(false, readingOnlyMature))
        // enabled: the old behaviour
        assertEquals(WordStatus.LEARNING, rules.autoStatus(true, meaningNewReadingLearning, readingEnabled = true))
        assertEquals(WordStatus.LEARNING, rules.autoStatus(true, meaningMatureReadingLearning, readingEnabled = true))
        assertEquals(WordStatus.KNOWN, rules.autoStatus(true, readingOnlyMature, readingEnabled = true))
        // kind unknown (null): never treated as retired
        assertEquals(WordStatus.LEARNING, rules.autoStatus(true, listOf(c(CardState.NEW), c(CardState.LEARNING, 0))))
    }

    @Test
    fun effectiveIsOverrideThenAutoThenNew() {
        assertEquals(WordStatus.BLACKLISTED, rules.effective(WordStatus.BLACKLISTED, WordStatus.KNOWN))
        assertEquals(WordStatus.LEARNING, rules.effective(null, WordStatus.LEARNING))
        assertEquals(WordStatus.NEW, rules.effective(null, null))
    }
}
