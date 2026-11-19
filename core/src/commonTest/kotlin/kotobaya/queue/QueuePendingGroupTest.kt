package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.ReviewQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C-27 / ADR-040: a pending entry is drawn as the unit its vocab card will be (QueueBuilder rule 4 "Kanji
 * first"): its existing new kanji cards plus the kanji still to mint (`missingKanji`), so the count is the
 * same before and after materializing. The old "OFF = 1 card" and "negative newKanjiCards" cases are replaced.
 */
class QueuePendingGroupTest {
    private val decks = listOf(deck(1))

    private fun kanjiCard(n: Int, literal: String) = card(n, base = NoteBaseType.KANJI, kanji = literal)

    private fun seqs(q: ReviewQueue): List<Long> = q.toMaterialize.map { it.entrySeq }

    /** Something introduced today, so no part is "the first new card of the day" (ADR-044). */
    private fun introducedToday(n: Int) = (1..n).map { today(9000 + it, type = ReviewType.LEARNING, introducedNew = true) }

    @Test
    fun anEntryWhoseUnitDoesNotFitIsNotDrawn() {
        val p = listOf(entry(1, 100, 1, exprKanji = listOf("食", "堂"), missing = mks("食", "堂")))
        // Goal 2, 1 introduced: the unit of 3 leads with 食 (claimed), the entry stays pending.
        val lead = build(input(emptyList(), decks = decks, pending = p, limits = limits(newPerDay = 2), today = introducedToday(1)))
        assertEquals(emptyList(), seqs(lead))
        assertEquals(listOf("食"), lead.kanjiToMaterialize)
        // Goal 1 met: nothing at all, but keep going would draw it.
        val none = build(input(emptyList(), decks = decks, pending = p, limits = limits(newPerDay = 1), today = introducedToday(1)))
        assertEquals(emptyList(), seqs(none))
        assertEquals(emptyList(), none.kanjiToMaterialize)
        assertTrue(none.moreAvailable, "keep going draws it (a unit may overshoot the batch)")
        val fits = build(input(emptyList(), decks = decks, pending = p, limits = limits(newPerDay = 3)))
        assertEquals(listOf(100L), seqs(fits))
        assertEquals(listOf("食", "堂"), fits.kanjiToMaterialize)
        val kg = limits(newPerDay = 1).copy(keepGoing = true, keepGoingNewBatch = 1)
        assertEquals(listOf(100L), seqs(build(input(emptyList(), decks = decks, pending = p, limits = kg))))
    }

    @Test
    fun existingNewKanjiCardsJoinTheUnitAndAreTaken() {
        // 食 has a new kanji card (deckless); 堂 would be minted.
        val c = listOf(kanjiCard(1, "食"), card(2, members = listOf(member(1, 2))))
        val p = listOf(
            entry(1, 100, 1, exprKanji = listOf("食", "堂"), missing = mks("堂")),
            entry(1, 101, 3),
        )
        val q = build(input(c, decks = decks, pending = p, limits = limits(newPerDay = 3)))
        assertEquals(listOf(100L), seqs(q), "the unit of 100 is 3: nothing else fits")
        assertEquals("", q.shape(), "the kanji card belongs to 100's unit, not shown alone")
        assertEquals(listOf("堂"), q.kanjiToMaterialize)
        val q4 = build(input(c, decks = decks, pending = p, limits = limits(newPerDay = 4)))
        assertEquals(listOf(100L), seqs(q4))
        assertEquals("N2", q4.shape())
    }

    @Test
    fun newKanjiCardsAndEveryAddKanjiModeChangeNothing() {
        val p = listOf(entry(1, 100, 1, exprKanji = listOf("食", "堂"), missing = mks("食", "堂")), entry(1, 101, 2))
        val expected = build(input(emptyList(), decks = decks, pending = p, limits = limits(newPerDay = 3)))
        assertEquals(listOf(100L), seqs(expected))
        for (mode in AddKanjiMode.entries) {
            for (n in listOf(-5, 0, 2, 7)) {
                val pm = p.map { it.copy(newKanjiCards = n) }
                val q = build(
                    input(emptyList(), decks = listOf(deck(1, addKanji = mode)), pending = pm, limits = limits(newPerDay = 3), decklessAddKanji = mode),
                )
                assertEquals(expected.items, q.items, "$mode / $n")
                assertEquals(seqs(expected), seqs(q), "$mode / $n")
                assertEquals(expected.kanjiToMaterialize, q.kanjiToMaterialize, "$mode / $n")
            }
        }
    }

    @Test
    fun theSameCountBeforeAndAfterMaterializing() {
        val l = limits(newPerDay = 3)
        val p = listOf(entry(1, 100, 1, exprKanji = listOf("食", "堂"), missing = mks("食", "堂")), entry(1, 101, 2))
        val before = DefaultReviewLoop.evaluate(input(emptyList(), decks = decks, pending = p, limits = l))
        assertEquals(listOf(100L), seqs(before.queue))
        assertEquals(listOf("食", "堂"), before.queue.kanjiToMaterialize)
        // Materialized: vocab card 10 (e:100) and the two auto_kanji cards (deckless); 101 still pending.
        val c = listOf(
            card(10, members = listOf(member(1, 1, "e:100")), exprKanji = listOf("食", "堂")),
            kanjiCard(11, "食").copy(noteCreatedAt = 5),
            kanjiCard(12, "堂").copy(noteCreatedAt = 5),
        )
        val after = DefaultReviewLoop.evaluate(input(c, decks = decks, pending = p.drop(1), limits = l))
        assertEquals("N11 N12 N10", after.queue.shape())
        assertEquals(emptyList(), seqs(after.queue))
        assertEquals(emptyList(), after.queue.kanjiToMaterialize)
        assertEquals(before.state.untilStop, after.state.untilStop)
        assertEquals(3, after.state.untilStop)
    }

    @Test
    fun twoEntriesSharingAMissingKanjiClaimItOnce() {
        // 学校 and 学生: 学 is minted once; the second unit is 生 + the word (withheld until 学 exists).
        val p = listOf(
            entry(1, 100, 1, exprKanji = listOf("学", "校"), missing = mks("学", "校")),
            entry(1, 101, 2, exprKanji = listOf("学", "生"), missing = mks("学", "生")),
        )
        val q = DefaultReviewLoop.evaluate(input(emptyList(), decks = decks, pending = p, limits = limits(newPerDay = 5)))
        assertEquals(listOf(100L, 101L), seqs(q.queue))
        assertEquals(listOf("学", "校", "生"), q.queue.kanjiToMaterialize)
        assertEquals(5, q.state.untilStop)
    }
}
