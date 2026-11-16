package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-035: QueueBuilder rule 0, retired cards (vocab `reading` while `cards.reading_enabled` is not true). */
class QueueRetiredCardsTest {
    private val past = NOW - 2 * HOUR

    /** Card [n] of note [note] with the real kind name (QueueTestSupport uses `k<order>`). */
    private fun QueueCandidate.kind(kind: String, base: NoteBaseType = noteBaseType): QueueCandidate =
        copy(kind = kind, noteBaseType = base)

    private fun meaning(n: Int, note: Int, state: CardState = CardState.NEW, due: Long? = null, members: List<kotobaya.contracts.queue.DeckMembership> = emptyList()) =
        card(n, note = note, kindOrder = 0, state = state, due = due, members = members).kind("meaning")

    private fun reading(n: Int, note: Int, state: CardState = CardState.NEW, due: Long? = null, members: List<kotobaya.contracts.queue.DeckMembership> = emptyList()) =
        card(n, note = note, kindOrder = 1, state = state, due = due, members = members).kind("reading")

    private fun on(i: QueueInput) = i.copy(readingEnabled = true)

    @Test
    fun defaultIsRetired() {
        assertFalse(input(emptyList()).readingEnabled)
    }

    @Test
    fun retiredCardsAreNeverServedInAnyState() {
        val c = listOf(
            reading(1, note = 1, state = CardState.REVIEW, due = past),
            reading(2, note = 2, state = CardState.LEARNING, due = past),
            reading(3, note = 3),
            meaning(4, note = 4, state = CardState.REVIEW, due = past),
        )
        val off = build(input(c))
        assertEquals("R4", off.shape())
        val onQ = build(on(input(c)))
        assertEquals(setOf(1, 2, 3, 4), onQ.cards().toSet())
    }

    @Test
    fun retiredLearningCardDoesNotSetNextLearningDueAt() {
        val later = NOW + 2 * HOUR
        val c = listOf(reading(1, note = 1, state = CardState.LEARNING, due = later))
        val off = build(input(c, limits = limits(learnAheadMs = 0)))
        assertNull(off.nextLearningDueAt)
        assertEquals("", off.shape())
        assertFalse(off.moreAvailable)
        assertEquals(later, build(on(input(c, limits = limits(learnAheadMs = 0)))).nextLearningDueAt)
    }

    @Test
    fun retiredSiblingNeverBuriesTheMeaningCardNotEvenThroughTodaysReview() {
        // Note 1: meaning M (REVIEW due today) and reading R; R answered today (e.g. an offline review synced later).
        val c = listOf(
            meaning(1, note = 1, state = CardState.REVIEW, due = past),
            reading(2, note = 1, state = CardState.REVIEW, due = TOKYO_DAY.endMs + HOUR),
        )
        val t = listOf(today(2, note = 1, type = ReviewType.SCHEDULED, countsAsReview = true))
        assertEquals("R1", build(input(c, today = t)).shape())
        assertEquals("", build(on(input(c, today = t))).shape(), "enabled: R's review buries M as before")
    }

    @Test
    fun retiredSiblingIsNotTakenSoItCannotBuryWithinTheBuild() {
        // Both NEW in one deck; reading sorts first by position (same note, kindOrder decides) - give it kindOrder -1.
        val m = member(1, 1)
        val c = listOf(
            card(2, note = 1, kindOrder = 0, members = listOf(m)).kind("reading"),
            card(1, note = 1, kindOrder = 1, members = listOf(m)).kind("meaning"),
        )
        val d = listOf(deck(1))
        assertEquals("N1", build(input(c, decks = d)).shape())
        assertEquals("N2", build(on(input(c, decks = d))).shape(), "enabled: reading first, meaning buried")
    }

    @Test
    fun retiredCardsDoNotUseNewOrReviewLimitsAndTodaysRetiredReviewsDoNotCount() {
        val d = listOf(deck(1, newPerDay = 2))
        val c = (1..4).map { meaning(it, note = it, members = listOf(member(1, it.toLong()))) } +
            (11..14).map { reading(it, note = it - 10, members = listOf(member(1, it.toLong()))) } +
            (21..22).map { meaning(it, note = it, state = CardState.REVIEW, due = past + it) }
        // Two reading cards introduced and one reviewed today (before the setting was applied).
        val t = listOf(
            today(91, note = 91, type = ReviewType.LEARNING, introducedNew = true, decks = listOf(1)),
            today(92, note = 92, type = ReviewType.LEARNING, introducedNew = true, decks = listOf(1)),
            today(93, note = 93, countsAsReview = true),
        )
        val retiredToday = listOf(
            reading(91, note = 91, state = CardState.LEARNING, due = NOW + 5 * HOUR, members = listOf(member(1, 91))),
            reading(92, note = 92, state = CardState.LEARNING, due = NOW + 5 * HOUR, members = listOf(member(1, 92))),
            reading(93, note = 93, state = CardState.REVIEW, due = TOKYO_DAY.endMs + 10 * HOUR),
        )
        val l = limits(newPerDay = 20, reviewsPerDay = 2, learnAheadMs = 0)
        val off = build(input(c + retiredToday, decks = d, limits = l, today = t))
        assertEquals(listOf(1, 2), off.cards(kotobaya.contracts.queue.QueueItemKind.NEW), "deck limit 2 untouched by retired intros")
        assertEquals(listOf(21, 22), off.cards(kotobaya.contracts.queue.QueueItemKind.REVIEW), "review limit 2 untouched by the retired review")
        val onQ = build(on(input(c + retiredToday, decks = d, limits = l, today = t)))
        assertEquals(emptyList(), onQ.cards(kotobaya.contracts.queue.QueueItemKind.NEW), "enabled: 2 intros today use the deck limit")
        assertEquals(listOf(21), onQ.cards(kotobaya.contracts.queue.QueueItemKind.REVIEW))
    }

    @Test
    fun onlyVocabReadingIsRetired() {
        val c = listOf(
            card(1, note = 1).kind("reading", NoteBaseType.PHRASE),
            card(2, note = 2).kind("reading", NoteBaseType.CUSTOM),
            card(3, note = 3).kind("listening"),
            card(4, note = 4).kind("reading"),
        )
        assertEquals(listOf(1, 2, 3), build(input(c)).cards())
    }

    @Test
    fun countsAreTheSameWhetherOrNotTheLoaderPassesRetiredCards() {
        val all = listOf(
            meaning(1, note = 1, state = CardState.REVIEW, due = past),
            reading(2, note = 1, state = CardState.REVIEW, due = past),
            meaning(3, note = 3),
            reading(4, note = 3),
            reading(5, note = 5, state = CardState.LEARNING, due = past),
        )
        val visible = all.filter { it.kind != "reading" }
        assertEquals(build(input(visible)), build(input(all)))
    }

    @Test
    fun pendingEntryIsDrawnAsBeforeAndRetiredKanjiGroupUnaffected() {
        // A materialized entry creates only meaning (CardGenerator); the builder still draws the entry as 1.
        val d = listOf(deck(1, newPerDay = 1, addKanji = AddKanjiMode.OFF))
        val q = build(input(listOf(reading(9, note = 9, members = listOf(member(1, 0)))), decks = d, pending = listOf(entry(1, 100L, 1))))
        assertEquals(listOf(100L), q.toMaterialize.map { it.entrySeq })
        assertEquals("", q.shape())
    }

    @Test
    fun moreAvailableIgnoresRetiredCards() {
        val c = listOf(reading(1, note = 1))
        val q = build(input(c, limits = limits(newPerDay = 0)))
        assertFalse(q.moreAvailable)
        assertTrue(build(on(input(c, limits = limits(newPerDay = 0)))).moreAvailable)
    }
}
