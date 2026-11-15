package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.ReviewQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C-27: a pending entry is drawn as the add_kanji group its vocab card will have (QueueBuilder rule 4), so an entry
 * that would not fit once materialized is never drawn, and the count is the same before and after materializing.
 */
class QueuePendingGroupTest {
    private val before = listOf(deck(1, addKanji = AddKanjiMode.BEFORE))

    private fun kanjiCard(n: Int, literal: String) = card(n, base = NoteBaseType.KANJI, kanji = literal)

    private fun seqs(q: ReviewQueue): List<Long> = q.toMaterialize.map { it.entrySeq }

    @Test
    fun anEntryWhoseGroupDoesNotFitTheBudgetIsNotDrawn() {
        val p = listOf(entry(1, 100, 1, exprKanji = listOf("食", "堂"), newKanjiCards = 2))
        val one = build(input(emptyList(), decks = before, pending = p, limits = limits(newPerDay = 1)))
        assertEquals(emptyList(), seqs(one))
        assertEquals("", one.shape())
        assertTrue(one.moreAvailable, "keep going draws it (a group may overshoot the batch)")
        assertEquals(listOf(100L), seqs(build(input(emptyList(), decks = before, pending = p, limits = limits(newPerDay = 3)))))
        val kg = limits(newPerDay = 1).copy(keepGoing = true, keepGoingNewBatch = 1)
        assertEquals(listOf(100L), seqs(build(input(emptyList(), decks = before, pending = p, limits = kg))))
    }

    @Test
    fun theGroupCountsExistingKanjiCardsAndTakesThem() {
        // 食 has a new kanji card (deckless); 堂 would be created.
        val c = listOf(kanjiCard(1, "食"), card(2, members = listOf(member(1, 2))))
        val p = listOf(
            entry(1, 100, 1, exprKanji = listOf("食", "堂"), newKanjiCards = 1),
            entry(1, 101, 3),
        )
        val q = build(input(c, decks = before, pending = p, limits = limits(newPerDay = 3)))
        assertEquals(listOf(100L), seqs(q), "the group of 100 is 3: nothing else fits")
        assertEquals("", q.shape(), "the kanji card is reserved for 100's group, not shown alone")
        val q4 = build(input(c, decks = before, pending = p, limits = limits(newPerDay = 4)))
        assertEquals(listOf(100L), seqs(q4))
        assertEquals("N2", q4.shape())
    }

    @Test
    fun withAddKanjiOffAnEntryIsOneCard() {
        val p = listOf(entry(1, 100, 1, exprKanji = listOf("食", "堂"), newKanjiCards = 2), entry(1, 101, 2))
        val q = build(input(emptyList(), decks = listOf(deck(1)), pending = p, limits = limits(newPerDay = 2)))
        assertEquals(listOf(100L, 101L), seqs(q))
    }

    @Test
    fun theSameCountBeforeAndAfterMaterializing() {
        val l = limits(newPerDay = 3)
        val p = listOf(entry(1, 100, 1, exprKanji = listOf("食", "堂"), newKanjiCards = 2), entry(1, 101, 2))
        assertEquals(listOf(100L), seqs(build(input(emptyList(), decks = before, pending = p, limits = l))))
        // Materialized: vocab card 10 (e:100) and the two auto_kanji cards (deckless); 101 still pending.
        val c = listOf(
            card(10, members = listOf(member(1, 1, "e:100")), exprKanji = listOf("食", "堂")),
            kanjiCard(11, "食"),
            kanjiCard(12, "堂"),
        )
        val after = build(input(c, decks = before, pending = p.drop(1), limits = l))
        assertEquals("N11 N12 N10", after.shape())
        assertEquals(emptyList(), seqs(after))
    }

    @Test
    fun negativeNewKanjiCardsCountAsZero() {
        val p = listOf(entry(1, 100, 1, newKanjiCards = -5), entry(1, 101, 2))
        val q = build(input(emptyList(), decks = before, pending = p, limits = limits(newPerDay = 1)))
        assertEquals(listOf(100L), seqs(q))
    }
}
