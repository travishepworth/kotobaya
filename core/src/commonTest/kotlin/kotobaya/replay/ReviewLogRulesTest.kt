package kotobaya.replay

import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.replay.UndoOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SYNC.md 7.9 device rules (the tests the ReviewLogRules KDoc requires). Runs on jvm and js. */
class ReviewLogRulesTest {
    private val rules = DefaultReviewLogRules
    private val h15 = hlc(T0 + 15)
    private val h20 = hlc(T0 + 20)

    @Test
    fun duplicateInsertWithDifferentContentKeepsTheFirst() {
        assertTrue(rules.shouldInsert(existing = false))
        assertFalse(rules.shouldInsert(existing = true))
        val first = rated(uuid(1, 1), T0, Rating.GOOD)
        val replica = TableReplica(initial = P0)
        replica.logged(first)
        replica.logged(first.copy(rating = Rating.AGAIN, reviewedAt = T0 + 5))
        assertEquals(listOf(first), replica.rows())
        assertEquals(DefaultReplayEngine.replay(listOf(first), P0), replica.read())
    }

    @Test
    fun earliestUndoWinsInEitherOrder() {
        // A (h=20) then B (h=15) → 15
        val a = rules.undo(null, null, h20, T0 + 20, rowFeedsSchedule = true)
        assertEquals(UndoOutcome(true, h20, T0 + 20, true), a)
        val ab = rules.undo(a.undoneHlc, a.undoneAt, h15, T0 + 15, rowFeedsSchedule = true)
        assertEquals(UndoOutcome(true, h15, T0 + 15, false), ab, "lowering never replays")
        // B then A → 15
        val b = rules.undo(null, null, h15, T0 + 15, rowFeedsSchedule = true)
        val ba = rules.undo(b.undoneHlc, b.undoneAt, h20, T0 + 20, rowFeedsSchedule = true)
        assertEquals(UndoOutcome(false, h15, T0 + 15, false), ba, "never raised")
    }

    @Test
    fun sameUndoTwiceIsUnchanged() {
        val first = rules.undo(null, null, h20, T0 + 20, rowFeedsSchedule = true)
        val again = rules.undo(first.undoneHlc, first.undoneAt, h20, T0 + 20, rowFeedsSchedule = true)
        assertEquals(UndoOutcome(false, h20, T0 + 20, false), again)
    }

    @Test
    fun undoOfNonFeedingRowIsWrittenButNeverReplays() {
        assertEquals(UndoOutcome(true, h20, T0 + 20, false), rules.undo(null, null, h20, T0 + 20, rowFeedsSchedule = false))
    }

    @Test
    fun undoOfUnknownReviewIdWritesNothing() {
        val replica = TableReplica(initial = P0)
        assertFalse(replica.undone(uuid(1, 7), h20, T0 + 20))
        assertEquals(emptyList(), replica.rows())
    }
}
