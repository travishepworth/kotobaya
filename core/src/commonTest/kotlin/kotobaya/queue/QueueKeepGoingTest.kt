package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C-26: QueueBuilder rule 6 (keep going), `ReviewQueue.moreAvailable` and `QueueItem.isNew`. */
class QueueKeepGoingTest {
    private val past = NOW - 2 * HOUR
    private val tomorrow = TOKYO_DAY.endMs

    private fun QueueLimits.keepGoing(batch: Int = QueueLimits.DEFAULT_KEEP_GOING_NEW_BATCH) =
        copy(keepGoing = true, keepGoingNewBatch = batch)

    /** Two new cards introduced and one review done today (notes 90..92, no siblings of the candidates). */
    private val usedUp = listOf(
        today(90, introducedNew = true, type = ReviewType.LEARNING),
        today(91, introducedNew = true, type = ReviewType.LEARNING),
        today(92, countsAsReview = true),
    )

    @Test
    fun contractExampleNormalIsEmptyWithMoreAvailableAndKeepGoingListsReviewsThenNew() {
        val c = listOf(
            card(1, members = listOf(member(1, 1))),
            card(2, members = listOf(member(1, 2))),
            card(3, members = listOf(member(1, 3))),
            review(4, past),
            review(5, tomorrow),
        )
        val l = limits(newPerDay = 2, reviewsPerDay = 1)
        val normal = build(input(c, decks = listOf(deck(1)), limits = l, today = usedUp))
        assertEquals("", normal.shape())
        assertTrue(normal.moreAvailable)
        val kg = build(input(c, decks = listOf(deck(1)), limits = l.keepGoing(), today = usedUp))
        assertEquals("R4 N1 N2 N3", kg.shape())
        assertFalse(kg.moreAvailable)
        assertEquals("R4 N1 N2", build(input(c, decks = listOf(deck(1)), limits = l.keepGoing(2), today = usedUp)).shape())
    }

    @Test
    fun keepGoingFalseIsTheDefaultAndChangesNothing() {
        val l = limits()
        assertFalse(l.keepGoing)
        assertEquals(QueueLimits.DEFAULT_KEEP_GOING_NEW_BATCH, l.keepGoingNewBatch)
        val c = listOf(review(1, past), card(2), card(3), learning(4, past))
        val explicit = build(input(c, limits = l.copy(keepGoing = false)))
        assertEquals(build(input(c, limits = l)), explicit)
        assertFalse(explicit.moreAvailable, "non-empty normal queue")
        // ADR-037: the batch bounds the normal build too (before it, only keep going).
        assertEquals("L4 R1 N2", build(input(c, limits = l.copy(keepGoingNewBatch = 1))).shape())
    }

    @Test
    fun newPerDayNewPerSessionAndDeckNewLimitsAreIgnoredButDeckPriorityHolds() {
        val decks = listOf(deck(1, priority = 0, newPerDay = 1), deck(2, priority = 1, newPerDay = 0))
        val c = listOf(
            card(1, members = listOf(member(1, 1))),
            card(2, members = listOf(member(1, 2))),
            card(3, members = listOf(member(2, 1))),
            card(4, members = listOf(member(2, 2))),
        )
        val t = listOf(today(90, introducedNew = true, type = ReviewType.LEARNING, decks = listOf(1)))
        val l = limits(newPerDay = 1, newPerSession = 1, newShown = 1)
        val normal = build(input(c, decks = decks, limits = l, today = t))
        assertEquals("", normal.shape())
        assertTrue(normal.moreAvailable)
        assertEquals("N1 N2 N3 N4", build(input(c, decks = decks, limits = l.keepGoing(), today = t)).shape())
    }

    @Test
    fun reviewCapsAreLiftedGloballyAndPerDeck() {
        val decks = listOf(deck(1, reviewsPerDay = 1))
        val c = listOf(review(1, past, listOf(member(1, 1))), review(2, past + 1, listOf(member(1, 2))), review(3, past + 2))
        val l = limits(reviewsPerDay = 2)
        assertEquals("R1 R3", build(input(c, decks = decks, limits = l)).shape())
        assertEquals("R1 R2 R3", build(input(c, decks = decks, limits = l.keepGoing())).shape())
    }

    @Test
    fun siblingsStayBuriedAndExclusionsHold() {
        val c = listOf(
            card(1, note = 7),
            card(2, note = 7, kindOrder = 1),
            card(3, note = 9),
            card(4, suspended = true),
            card(5, override = kotobaya.contracts.WordStatus.KNOWN),
            card(6, override = kotobaya.contracts.WordStatus.BLACKLISTED),
            card(8, buriedUntil = NOW + 1),
            card(11, override = kotobaya.contracts.WordStatus.NEVER_FORGET),
            card(12, override = kotobaya.contracts.WordStatus.IGNORED),
            review(13, past).copy(wordOverride = kotobaya.contracts.WordStatus.NEVER_FORGET),
            learning(14, past).copy(wordOverride = kotobaya.contracts.WordStatus.IGNORED),
        )
        val t = listOf(today(10, note = 9, type = ReviewType.LEARNING), today(90, introducedNew = true, type = ReviewType.LEARNING))
        val q = build(input(c, limits = limits(newPerDay = 1).keepGoing(), today = t))
        assertEquals("N1", q.shape())
    }

    @Test
    fun nothingNotYetDueIsReturnedAndAnEmptyKeepGoingMeansTrulyOut() {
        val c = listOf(review(1, tomorrow), learning(2, NOW + 2 * HOUR), card(3, suspended = true))
        val normal = build(input(c, limits = limits(newPerDay = 0, reviewsPerDay = 0)))
        assertEquals("", normal.shape())
        assertFalse(normal.moreAvailable)
        assertEquals(NOW + 2 * HOUR, normal.nextLearningDueAt)
        val kg = build(input(c, limits = limits(newPerDay = 0, reviewsPerDay = 0).keepGoing()))
        assertEquals("", kg.shape())
        assertEquals(NOW + 2 * HOUR, kg.nextLearningDueAt)
    }

    @Test
    fun disabledDeckStillSuppliesNoNewCards() {
        val c = listOf(card(1, members = listOf(member(1, 1))))
        val q = build(input(c, decks = listOf(deck(1, enabled = false)), limits = limits(newPerDay = 0)))
        assertFalse(q.moreAvailable)
        assertEquals("", build(input(c, decks = listOf(deck(1, enabled = false)), limits = limits(newPerDay = 0).keepGoing())).shape())
    }

    @Test
    fun learnAheadItemMakesTheNormalQueueNonEmpty() {
        val c = listOf(learning(1, NOW + 60_000), card(2))
        val q = build(input(c, limits = limits(newPerDay = 0)))
        assertEquals("L1", q.shape())
        assertFalse(q.moreAvailable)
        // keep going: new card first, learn-ahead still last
        assertEquals("N2 L1", build(input(c, limits = limits(newPerDay = 0).keepGoing())).shape())
    }

    @Test
    fun dueLearningStillLeadsAndReviewsPrecedeNewUnderKeepGoing() {
        val c = listOf(card(1), card(2), card(3), review(4, past), learning(5, past))
        // normal (ADR-037 mix): a fresh day starts with a review, then the new cards
        assertEquals("L5 R4 N1 N2 N3", build(input(c)).shape())
        assertEquals("L5 R4 N1 N2 N3", build(input(c, limits = limits().keepGoing())).shape())
    }

    @Test
    fun addKanjiGroupMayOvershootTheBatchSoItNeverStarves() {
        val decks = listOf(deck(1, addKanji = AddKanjiMode.BEFORE), deck(2, priority = 1))
        val c = listOf(
            card(1, members = listOf(member(1, 1)), exprKanji = listOf("食", "物")),
            card(2, note = 20, base = NoteBaseType.KANJI, kanji = "食", members = listOf(member(2, 1))),
            card(3, note = 30, base = NoteBaseType.KANJI, kanji = "物", members = listOf(member(2, 2))),
            card(4, members = listOf(member(1, 2))),
        )
        val q = build(input(c, decks = decks, limits = limits(newPerDay = 0).keepGoing(1)))
        assertEquals("N2 N3 N1", q.shape())
        val normal = build(input(c, decks = decks, limits = limits(newPerDay = 1)))
        // normal mode: the group of 3 does not fit a budget of 1; deck order moves on to card 4
        assertEquals("N4", normal.shape())
    }

    @Test
    fun pendingEntriesAreBoundedByTheBatchAndCountForMoreAvailable() {
        val pending = (1..5).map { entry(1, 1000L + it, it.toLong()) }
        val normal = build(input(emptyList(), decks = listOf(deck(1)), limits = limits(newPerDay = 0), pending = pending))
        assertTrue(normal.toMaterialize.isEmpty())
        assertTrue(normal.moreAvailable)
        val kg = build(input(emptyList(), decks = listOf(deck(1)), limits = limits(newPerDay = 0).keepGoing(3), pending = pending))
        assertEquals(listOf(1001L, 1002L, 1003L), kg.toMaterialize.map { it.entrySeq })
    }

    @Test
    fun batchBelowOneCountsAsOne() {
        val c = listOf(card(1), card(2))
        assertEquals("N1", build(input(c, limits = limits(newPerDay = 0).keepGoing(0))).shape())
        assertEquals("N1", build(input(c, limits = limits(newPerDay = 0).keepGoing(-5))).shape())
    }

    @Test
    fun isNewIsTrueExactlyForNewCards() {
        val c = listOf(card(1), review(2, past), learning(3, past), card(4, state = CardState.RELEARNING, due = past))
        val q = build(input(c))
        for (item in q.items) assertEquals(item.kind == QueueItemKind.NEW, item.isNew, item.cardId)
        assertEquals(listOf(1), q.items.filter { it.isNew }.map { it.cardId.takeLast(12).toInt() })
        assertEquals(4, q.items.size)
    }
}
