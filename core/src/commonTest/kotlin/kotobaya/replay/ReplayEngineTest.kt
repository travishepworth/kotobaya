package kotobaya.replay

import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayDecision
import kotobaya.contracts.replay.ReplayKey
import kotobaya.fsrs.FsrsScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T203 unit tests (contracts/core README › T203; SYNC.md 9, 12.1). Runs on jvm and js. */
class ReplayEngineTest {
    private val e = DefaultReplayEngine
    private val p = P0

    private fun id(n: Int) = uuid(1, 0x1000L + n)

    // ---- order (SYNC.md 9.1) ----------------------------------------------------------------------

    @Test
    fun orderIsReviewedAtThenHlcThenId() {
        val a = ReplayKey(T0, hlc(T0, 0, DEV_B), id(2))
        val b = ReplayKey(T0, hlc(T0, 1, DEV_A), id(1))
        val c = ReplayKey(T0, hlc(T0, 1, DEV_A), id(3))
        assertTrue(e.compare(a, b) < 0, "equal reviewed_at: hlc decides")
        assertTrue(e.compare(b, c) < 0, "equal reviewed_at and hlc: id decides")
        assertEquals(0, e.compare(c, c.copy()))
        // an earlier reviewed_at sorts first even with a much later hlc (re-minted review, 10.4)
        val early = ReplayKey(T0 - 1, hlc(T0 + 10 * DAY), id(9))
        assertTrue(e.compare(early, a) < 0)
        assertTrue(e.compare(a, early) > 0)
    }

    @Test
    fun replaySortsAndFiltersItsInput() {
        val r1 = rated(id(1), T0, Rating.GOOD, ReviewType.LEARNING)
        val r2 = rated(id(2), T0 + DAY, Rating.AGAIN)
        val r3 = rated(id(3), T0 + 3 * DAY, Rating.GOOD)
        val drill = rated(id(4), T0 + 2 * DAY, Rating.AGAIN, ReviewType.DRILL)
        val undone = rated(id(5), T0 + 2 * DAY, Rating.EASY, undone = hlc(T0 + 2 * DAY + 5))
        val ordered = e.replay(listOf(r1, r2, r3), p)
        assertEquals(ordered, e.replay(listOf(r3, undone, drill, r1, r2), p))
        assertEquals(id(3), ordered.lastReviewId)
        assertEquals(3, ordered.schedule.reps)
    }

    @Test
    fun emptySetIsNewAndStampsWeightsVersion() {
        val c = e.replay(emptyList(), P1)
        assertEquals(CardCache(CardSchedule.NEW, null, 1), c)
        assertNull(c.schedule.dueAt)
    }

    @Test
    fun duplicateIdsAndMalformedRowsAreRejected() {
        val r = rated(id(1), T0, Rating.GOOD)
        assertFailsWith<IllegalArgumentException> { e.replay(listOf(r, r.copy(reviewedAt = T0 + 1)), p) }
        assertFailsWith<IllegalArgumentException> { e.replay(listOf(r.copy(rating = null)), p) }
        assertFailsWith<IllegalArgumentException> { e.replay(listOf(manual(id(2), T0, ManualAction.RESET).copy(manualAction = null)), p) }
    }

    @Test
    fun schedulerVersionIsExposed() {
        assertEquals("fsrs6-1", e.schedulerVersion)
        assertEquals(DefaultReplayEngine.SCHEDULER_VERSION, e.schedulerVersion)
    }

    // ---- manual rows (C-1b) -----------------------------------------------------------------------

    @Test
    fun resetGivesNewWithCountersCleared() {
        val rows = listOf(
            rated(id(1), T0, Rating.GOOD, ReviewType.LEARNING),
            rated(id(2), T0 + 600_000, Rating.GOOD, ReviewType.LEARNING),
            rated(id(3), T0 + 5 * DAY, Rating.AGAIN),
            manual(id(4), T0 + 6 * DAY, ManualAction.RESET),
        )
        val c = e.replay(rows, p)
        assertEquals(CardSchedule.NEW, c.schedule)
        assertEquals(id(4), c.lastReviewId)
        // the next rated row starts from scratch
        val next = rated(id(5), T0 + 7 * DAY, Rating.GOOD, ReviewType.LEARNING)
        val after = e.replay(rows + next, p)
        assertEquals(FsrsScheduler.review(CardSchedule.NEW, ratedOf(next), p), after.schedule)
        assertEquals(1, after.schedule.reps)
    }

    @Test
    fun setDueOnNewLeavesItNewAndNextReviewIsFirst() {
        val sd = manual(id(1), T0, ManualAction.SET_DUE, T0 + 3 * DAY)
        val c = e.replay(listOf(sd), p)
        assertEquals(CardSchedule.NEW, c.schedule)
        assertEquals(id(1), c.lastReviewId)
        val r = rated(id(2), T0 + DAY, Rating.GOOD)
        assertEquals(FsrsScheduler.review(CardSchedule.NEW, ratedOf(r), p), e.replay(listOf(sd, r), p).schedule)
    }

    @Test
    fun setDueOnReviewMovesDueKeepsLastReviewAtAndElapsedFromRatedRow() {
        val r1 = rated(id(1), T0, Rating.EASY, ReviewType.LEARNING)
        val sd = manual(id(2), T0 + 2 * DAY, ManualAction.SET_DUE, T0 + 2 * DAY + 3_600_000)
        val r2 = rated(id(3), T0 + 2 * DAY + 3_600_000, Rating.GOOD)
        val afterR1 = e.replay(listOf(r1), p)
        assertEquals(CardState.REVIEW, afterR1.schedule.state)
        val afterSd = e.replay(listOf(r1, sd), p)
        assertEquals(T0 + 2 * DAY + 3_600_000, afterSd.schedule.dueAt)
        assertEquals(T0, afterSd.schedule.lastReviewAt)
        assertEquals(0, afterSd.schedule.intervalDays)
        assertEquals(afterR1.schedule.stability, afterSd.schedule.stability)
        // fold = replay across the manual row
        assertEquals(afterSd, e.fold(afterR1, sd, p))
        val full = e.replay(listOf(r1, sd, r2), p)
        assertEquals(full, e.fold(afterSd, r2, p))
        // elapsed is measured from r1 (2 days), not from the set_due (same day)
        assertEquals(FsrsScheduler.review(afterR1.schedule, ratedOf(r2), p), full.schedule)
        assertNotEquals(FsrsScheduler.review(afterR1.schedule.copy(lastReviewAt = sd.reviewedAt), ratedOf(r2), p).stability, full.schedule.stability)
    }

    // ---- decide: the six branches -----------------------------------------------------------------

    @Test
    fun decideBranches() {
        val r1 = rated(id(1), T0, Rating.GOOD, ReviewType.LEARNING)
        val c1 = e.replay(listOf(r1), p)
        val later = rated(id(2), T0 + DAY, Rating.GOOD)
        // 1. not schedule-feeding, or undone
        assertEquals(ReplayDecision.NONE, e.decide(c1, r1.key, later.copy(reviewType = ReviewType.DRILL), p))
        assertEquals(ReplayDecision.NONE, e.decide(c1, r1.key, later.copy(reviewType = ReviewType.CRAM), p))
        assertEquals(ReplayDecision.NONE, e.decide(c1, r1.key, later.copy(undoneHlc = hlc(T0 + DAY)), p))
        // 2. weights_version mismatch → full replay, even when sorting last
        assertEquals(ReplayDecision.FULL_REPLAY, e.decide(c1, r1.key, later, P1))
        // 3. no folded row yet
        assertEquals(ReplayDecision.FOLD, e.decide(e.replay(emptyList(), p), null, later, p))
        // 4. the last folded row is not present locally
        assertEquals(ReplayDecision.FULL_REPLAY, e.decide(c1, null, later, p))
        // 5. the row already last folded
        assertEquals(ReplayDecision.NONE, e.decide(c1, r1.key, r1, p))
        // 6. sorts after → fold; before → full replay
        assertEquals(ReplayDecision.FOLD, e.decide(c1, r1.key, later, p))
        val earlier = rated(id(3), T0 - DAY, Rating.GOOD)
        assertEquals(ReplayDecision.FULL_REPLAY, e.decide(c1, r1.key, earlier, p))
        // earlier reviewed_at but later hlc → full replay (10.4 re-mint, a late push)
        val lateHlc = rated(id(4), T0 - 1, Rating.GOOD, hlc = hlc(T0 + 30 * DAY))
        assertEquals(ReplayDecision.FULL_REPLAY, e.decide(c1, r1.key, lateHlc, p))
        // equal reviewed_at and hlc: the id decides
        val sameTimeSmallerId = rated(uuid(0, 0), T0, Rating.GOOD)
        assertEquals(ReplayDecision.FULL_REPLAY, e.decide(c1, r1.key, sameTimeSmallerId, p))
    }

    @Test
    fun foldRejectsStaleCacheAndNonFeedingRows() {
        val r1 = rated(id(1), T0, Rating.GOOD, ReviewType.LEARNING)
        val c1 = e.replay(listOf(r1), p)
        val r2 = rated(id(2), T0 + DAY, Rating.GOOD)
        assertFailsWith<IllegalArgumentException> { e.fold(c1, r2, P1) }
        assertFailsWith<IllegalArgumentException> { e.fold(c1, r2.copy(reviewType = ReviewType.DRILL), p) }
        assertFailsWith<IllegalArgumentException> { e.fold(c1, r2.copy(reviewType = ReviewType.CRAM), p) }
        assertFailsWith<IllegalArgumentException> { e.fold(c1, r2.copy(undoneHlc = hlc(T0 + DAY)), p) }
        val folded = e.fold(c1, r2, p)
        assertEquals(id(2), folded.lastReviewId)
        assertEquals(p.weightsVersion, folded.weightsVersion)
    }

    @Test
    fun weightsVersionMismatchForcesFullReplayWithNewWeights() {
        val rows = listOf(
            rated(id(1), T0, Rating.GOOD, ReviewType.LEARNING),
            rated(id(2), T0 + 600_000, Rating.GOOD, ReviewType.LEARNING),
            rated(id(3), T0 + 4 * DAY, Rating.HARD),
        )
        val stale = e.replay(rows, P0)
        val next = rated(id(4), T0 + 9 * DAY, Rating.GOOD)
        val replica = TableReplica(initial = P0)
        rows.forEach(replica::logged)
        replica.paramsChanged(P1, eager = false)
        replica.logged(next)
        assertEquals(1, replica.decisions[ReplayDecision.FULL_REPLAY])
        val expected = e.replay(rows + next, P1)
        assertEquals(expected, replica.read())
        assertEquals(1, expected.weightsVersion)
        // folding into the stale cache would have given a different, permanently wrong cache
        assertNotEquals(expected.schedule, FsrsScheduler.review(stale.schedule, ratedOf(next), P1))
    }

    // ---- SYNC.md 12.1: two devices review the same card offline -------------------------------------

    @Test
    fun workedExampleTwoDevicesReviewOffline() {
        val t = 1_733_584_666_624L
        val r1 = rated("0193a1b2-0003-7000-8000-000000000001", t + 10, Rating.GOOD, ReviewType.LEARNING, "0193a1b2c00a-0000-$DEV_A")
        val r2 = rated("0193a1b2-0003-7000-8000-000000000002", t + 30, Rating.GOOD, ReviewType.SCHEDULED, "0193a1b2c01e-0000-$DEV_A")
        val r3 = rated("0193a1b2-0003-7000-8000-000000000003", t + 20, Rating.AGAIN, ReviewType.SCHEDULED, "0193a1b2c014-0000-$DEV_B")
        val r4 = rated("0193a1b2-0003-7000-8000-000000000004", t + 25, Rating.GOOD, ReviewType.DRILL, "0193a1b2c019-0000-$DEV_B")
        assertEquals(listOf(r1, r3, r2), e.replaySet(listOf(r1, r2, r3, r4)))
        val expected = e.replay(listOf(r1, r3, r2), p)
        // server: A's pair then B's pair; B: own rows then pulls A's; A: own rows then pulls B's
        val orders = listOf(listOf(r1, r2, r3, r4), listOf(r3, r4, r1, r2), listOf(r1, r2, r3, r4).reversed())
        for (order in orders) {
            val replica = TableReplica(initial = p)
            order.forEach(replica::logged)
            assertEquals(expected, replica.read(), "order ${order.map { it.reviewedAt - t }}")
        }
        val server = TableReplica(initial = p)
        server.logged(r1)
        server.logged(r2)
        assertEquals(mapOf(ReplayDecision.FOLD to 2), server.decisions.toMap())
        server.logged(r3)
        assertEquals(1, server.decisions[ReplayDecision.FULL_REPLAY])
        assertEquals(r2.id, server.read().lastReviewId)
    }

    // ---- undo -------------------------------------------------------------------------------------

    @Test
    fun undoOfFeedingRowReplaysOnceAndEarliestUndoWins() {
        val r1 = rated(id(1), T0, Rating.GOOD, ReviewType.LEARNING)
        val r2 = rated(id(2), T0 + DAY, Rating.AGAIN)
        val r3 = rated(id(3), T0 + 2 * DAY, Rating.GOOD)
        val drill = rated(id(4), T0 + DAY + 5, Rating.AGAIN, ReviewType.DRILL)
        val replica = TableReplica(initial = p)
        listOf(r1, r2, r3, drill).forEach(replica::logged)
        assertTrue(replica.undone(r2.id, hlc(T0 + DAY + 20), T0 + DAY + 20))
        assertEquals(e.replay(listOf(r1, r3), p), replica.read())
        // a second, earlier undo lowers undone_hlc but never replays again
        assertEquals(false, replica.undone(r2.id, hlc(T0 + DAY + 15), T0 + DAY + 15))
        // undo of a drill row: written, no replay; unknown id: nothing, no error
        assertEquals(false, replica.undone(drill.id, hlc(T0 + 3 * DAY), T0 + 3 * DAY))
        assertEquals(false, replica.undone(id(99), hlc(T0 + 3 * DAY), T0 + 3 * DAY))
        assertEquals(e.replay(listOf(r1, r3), p), replica.read())
    }

    @Test
    fun incrementalReplayRecomputesFromInsertionPoint() {
        val inc = IncrementalReplay(e, p)
        val r1 = rated(id(1), T0, Rating.GOOD, ReviewType.LEARNING)
        val r2 = rated(id(2), T0 + 30 * 60_000, Rating.GOOD, ReviewType.LEARNING)
        val r3 = rated(id(3), T0 + 20 * 60_000, Rating.AGAIN, ReviewType.LEARNING)
        assertEquals(0, inc.insert(r1))
        assertEquals(1, inc.insert(r2))
        assertEquals(1, inc.insert(r3))
        assertEquals(-1, inc.insert(r3.copy(rating = Rating.EASY)))
        assertEquals(-1, inc.insert(rated(id(4), T0, Rating.GOOD, ReviewType.DRILL)))
        assertEquals(listOf(r1, r3, r2), inc.replaySet)
        assertEquals(e.replay(listOf(r1, r2, r3), p), inc.cache)
        assertTrue(inc.undo(r3.id))
        assertEquals(false, inc.undo(r3.id))
        assertEquals(e.replay(listOf(r1, r2), p), inc.cache)
        inc.setParams(P1)
        assertEquals(e.replay(listOf(r1, r2), P1), inc.cache)
    }

    // ---- refused cache (SYNC.md 9.4, C-8) -------------------------------------------------------

    private fun refused(params: kotobaya.contracts.fsrs.SchedulerParams = p) =
        CardCache(CardSchedule.NEW, Reserved.REPLAY_REFUSED_ID, params.weightsVersion)

    @Test
    fun decideStep1bRefusedCacheAlwaysFullReplays() {
        val r1 = rated(id(1), T0, Rating.GOOD, ReviewType.LEARNING)
        val later = rated(id(2), T0 + DAY, Rating.GOOD)
        // even with the current weights_version and a row that sorts after everything
        assertEquals(ReplayDecision.FULL_REPLAY, e.decide(refused(), null, later, p))
        assertEquals(ReplayDecision.FULL_REPLAY, e.decide(refused(), r1.key, later, p))
        // before any lookup: not even a row whose id equals the marker is NONE or FOLD
        val markerRow = later.copy(id = Reserved.REPLAY_REFUSED_ID)
        assertEquals(ReplayDecision.FULL_REPLAY, e.decide(refused(), markerRow.key, markerRow, p))
        // step 1 still comes first
        assertEquals(ReplayDecision.NONE, e.decide(refused(), null, later.copy(reviewType = ReviewType.DRILL), p))
        assertEquals(ReplayDecision.NONE, e.decide(refused(), null, later.copy(undoneHlc = hlc(T0 + DAY)), p))
    }

    @Test
    fun incrementalReplayHoldsRefusedCacheAndNeverThrows() {
        val inc = IncrementalReplay(e, p)
        val r1 = rated(id(1), T0, Rating.GOOD, ReviewType.LEARNING)
        val bad = rated(id(2), T0 + DAY, Rating.GOOD).copy(rating = null)
        val r3 = rated(id(3), T0 + 2 * DAY, Rating.GOOD)
        assertEquals(0, inc.insert(r1))
        assertNull(inc.refusal)
        assertEquals(1, inc.insert(bad)) // does not throw
        assertEquals(refused(), inc.cache)
        assertTrue(inc.refusal != null)
        assertFailsWith<IllegalArgumentException> { e.replay(inc.replaySet, p) } // same verdict as a full replay
        // rows after the refused one: still refused, list stays consistent
        assertEquals(2, inc.insert(r3))
        assertEquals(listOf(r1, bad, r3), inc.replaySet)
        assertEquals(refused(), inc.cache)
        // a row before the refused one: still refused
        val r0 = rated(id(4), T0 - DAY, Rating.AGAIN, ReviewType.LEARNING)
        assertEquals(0, inc.insert(r0))
        assertEquals(refused(), inc.cache)
        // new params: the refused cache carries the new weights_version
        inc.setParams(P1)
        assertEquals(refused(P1), inc.cache)
        // undo of an unrelated row keeps it refused; undo of the offending row heals it
        assertTrue(inc.undo(r3.id))
        assertEquals(refused(P1), inc.cache)
        assertTrue(inc.undo(bad.id))
        assertNull(inc.refusal)
        assertEquals(e.replay(listOf(r0, r1), P1), inc.cache)
        assertEquals(listOf(r0, r1), inc.replaySet)
        // healed checkpoints still fold correctly
        val r5 = rated(id(5), T0 + 3 * DAY, Rating.GOOD)
        assertEquals(2, inc.insert(r5))
        assertEquals(e.replay(listOf(r0, r1, r5), P1), inc.cache)
    }

    @Test
    fun incrementalReplayRefusesMalformedManualRowsLikeReplay() {
        val r1 = rated(id(1), T0, Rating.GOOD, ReviewType.LEARNING)
        for (bad in listOf(
            manual(id(2), T0 + DAY, ManualAction.RESET).copy(manualAction = null),
            manual(id(2), T0 + DAY, ManualAction.SET_DUE, dueAt = null),
        )) {
            val inc = IncrementalReplay(e, p)
            inc.insert(r1)
            inc.insert(bad)
            assertEquals(refused(), inc.cache)
            assertFailsWith<IllegalArgumentException> { e.replay(listOf(r1, bad), p) }
            assertTrue(inc.undo(bad.id))
            assertEquals(e.replay(listOf(r1), p), inc.cache)
        }
    }

    private fun ratedOf(r: kotobaya.contracts.replay.ReplayReview) =
        kotobaya.contracts.fsrs.RatedReview(r.id, r.reviewedAt, r.rating!!, r.utcOffsetMin)
}
