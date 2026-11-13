package kotobaya.sync.client.rejected

import kotobaya.sync.client.apply.diffRows
import kotobaya.sync.client.apply.projection
import kotobaya.sync.client.apply.serverReplica
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.remint.ClockActionBlock
import kotobaya.sync.client.remint.RemintScenario
import kotobaya.sync.client.remint.RemintStage
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SYNC.md 10.1–10.2 and the Discard half of 10.4: the list, Retry, Discard, clock repair. */
class RejectedOpsServiceTest {
    private fun stage(name: String) = RemintStage(RemintScenario(name))

    private fun id(n: String) = "0193a1b2-00ff-7000-8000-00a1000000$n"

    @Test
    fun listShowsWhatWhenWhyGroupedUnderTheRejectedParent() {
        val s = stage("remint-preset-set-existing-card.json")
        val list = s.a.service.list()
        assertEquals(6, s.a.service.count())
        val byId = list.associateBy { it.row.opId }
        // preset P (06) is the root of the existing card's preset_set (07); the new note (0a) of its card, the card's preset_set and review
        assertNull(byId.getValue(id("06")).parentOpId)
        assertEquals(id("06"), byId.getValue(id("07")).parentOpId)
        assertNull(byId.getValue(id("0a")).parentOpId)
        assertEquals(id("0a"), byId.getValue(id("0b")).parentOpId)
        assertEquals(id("0a"), byId.getValue(id("0c")).parentOpId)
        assertEquals(id("0a"), byId.getValue(id("0e")).parentOpId)
        assertEquals("clock_ahead", byId.getValue(id("06")).code)
        assertEquals(false, byId.getValue(id("06")).retryable)
        assertEquals(true, byId.getValue(id("07")).retryable)
        assertTrue(list.all { it.row.lastError != null && it.row.createdAt > 0 })
    }

    @Test
    fun retryMakesTheRowPendingWithTheSameHlcAndIsNotOfferedForClockCodes() {
        val s = stage("remint-preset-set-existing-card.json")
        val before = s.a.d.store.rows()
        assertEquals(RejectedActionResult.Unavailable(RejectedActionResult.NOT_RETRYABLE), s.a.service.retry(listOf(id("06"), id("07"))))
        assertEquals(before, s.a.d.store.rows(), "nothing changed")
        assertEquals(RejectedActionResult.Done(1), s.a.service.retry(listOf(id("07"))))
        val row = s.a.d.store.rows().single { it.opId == id("07") }
        assertEquals(PendingState.PENDING, row.state)
        assertEquals(before.single { it.opId == id("07") }.hlc, row.hlc)
        // pushed again in the next cycle: still unknown_reference (P never reached the server)
        assertIs<CycleResult.Completed>(s.a.cycle())
        assertEquals(PendingState.REJECTED, s.a.d.store.rows().single { it.opId == id("07") }.state)
    }

    @Test
    fun discardDeletesSetsRebuildPendingAndRebuildRollsTheLocalEffectBack() {
        val s = stage("remint-preset-set-existing-card.json")
        val hlcLast = s.a.d.state(SyncStateKeys.HLC_LAST)
        assertEquals(RejectedActionResult.Done(1), s.a.service.discard(listOf(id("07"))))
        assertEquals("1", s.a.d.state(SyncStateKeys.REBUILD_PENDING))
        assertEquals(hlcLast, s.a.d.state(SyncStateKeys.HLC_LAST), "no clock_ahead row discarded: no repair")
        assertEquals("-864000000", s.a.d.state(SyncStateKeys.REMINT_OFFSET), "clock-rejected rows remain")
        // discard all: the clock is repaired and remint_offset deleted
        assertEquals(RejectedActionResult.Done(5), s.a.service.discardAll())
        assertNull(s.a.d.state(SyncStateKeys.REMINT_OFFSET))
        assertEquals(s.a.d.state(SyncStateKeys.HLC_ACKED), s.a.d.state(SyncStateKeys.HLC_LAST))
        assertIs<CycleResult.Completed>(s.a.cycle())
        assertNull(s.a.d.state(SyncStateKeys.REBUILD_PENDING))
        assertEquals(emptyList(), diffRows(projection(serverReplica(s.server)), projection(s.a.d.tables())))
    }

    @Test
    fun clockRepairSetsHlcLastToTheGreatestOfHlcAckedAndEveryKeptRow() {
        val s = stage("remint-word-status.json")
        assertNull(s.a.d.state(SyncStateKeys.HLC_ACKED), "A has no acked op in this scenario")
        // discard the three clock_ahead rows; card.created (04) and the review (08) stay rejected
        assertEquals(RejectedActionResult.Done(3), s.a.service.discard(listOf(id("03"), id("05"), id("06"))))
        assertEquals("0193da908ce0-0000-01931f3e-0000-7000-8000-0000000000a1", s.a.d.state(SyncStateKeys.HLC_LAST), "the kept review's hlc")
        assertNull(s.a.d.state(SyncStateKeys.REMINT_OFFSET), "no clock-rejected row remains")
        // discarding rows that are not clock_ahead repairs nothing
        s.a.service.discardAll()
        assertEquals("0193da908ce0-0000-01931f3e-0000-7000-8000-0000000000a1", s.a.d.state(SyncStateKeys.HLC_LAST))
    }

    @Test
    fun discardOfClockAheadRowsNeedsAnAnsweredOutboxAndACorrectClock() {
        val s = stage("remint-word-status.json")
        val before = s.a.d.store.rows()
        s.a.d.store.transaction { it.syncState.put(SyncStateKeys.SERVER_SKEW_MS, "300001") }
        val r = s.a.service.discard(listOf(id("03")))
        assertEquals(ClockActionBlock.CLOCK_NOT_CONFIRMED, assertIs<RejectedActionResult.Unavailable>(r).block)
        // a row that is not clock_ahead can still be discarded
        assertIs<RejectedActionResult.Done>(s.a.service.discard(listOf(id("04"))))
        s.a.d.store.transaction { it.syncState.put(SyncStateKeys.SERVER_SKEW_MS, "-300000") }
        s.a.d.store.transaction { it.outbox.markPending(id("08"), 0) }
        assertEquals(ClockActionBlock.PENDING_OPS, assertIs<RejectedActionResult.Unavailable>(s.a.service.discard(listOf(id("03")))).block)
        assertEquals(before.filter { it.opId != id("04") }.map { it.opId }, s.a.d.store.rows().map { it.opId })
        assertEquals(RejectedActionResult.Unavailable(RejectedActionResult.NOT_REJECTED), s.a.service.discard(listOf(id("08"))))
    }
}
