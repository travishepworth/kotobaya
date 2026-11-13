package kotobaya.sync.client.remint

import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.client.apply.dump
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.rejected.RejectedOpsService
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.bookkeeping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Re-mint preconditions, the preview handle, the discard list and atomicity (SYNC.md 10.4). */
class RemintActionsTest {
    private fun stage(name: String) = RemintStage(RemintScenario(name))

    private fun id(n: String) = "0193a1b2-00ff-7000-8000-00a1000000$n"

    /** Everything a Re-mint could touch: outbox, sync state, learning tables. */
    private fun snapshot(s: RemintStage) = Triple(s.a.d.store.rows(), s.a.d.store.syncState(), dump(s.a.d.tables()))

    private fun assertBlocked(s: RemintStage, block: ClockActionBlock) {
        val before = snapshot(s)
        val p = s.a.service.previewRemint()
        assertEquals(block, p.blocked)
        assertEquals(RemintOutcome.Unavailable(block), s.a.service.remint(p))
        assertEquals(before, snapshot(s), "nothing changed")
    }

    @Test
    fun wrongClockOrUnansweredOpsBlockRemint() {
        val s = stage("remint-word-status.json")
        s.a.d.store.transaction { it.syncState.put(SyncStateKeys.SERVER_SKEW_MS, "-300001") }
        assertBlocked(s, ClockActionBlock.CLOCK_NOT_CONFIRMED)
        s.a.d.store.transaction { it.syncState.remove(SyncStateKeys.SERVER_SKEW_MS) }
        assertBlocked(s, ClockActionBlock.CLOCK_NOT_CONFIRMED)
        s.a.d.store.transaction { it.syncState.put(SyncStateKeys.SERVER_SKEW_MS, "300000") } // exactly 5 min is correct
        assertNull(s.a.service.previewRemint().blocked)
        // (nothing can be minted here: hlc_last is still 10 days ahead, G2) a row retried by hand is pending
        assertIs<MintResult.Refused>(s.a.d.minter.mint(OpType.SETTINGS_SET, JsonObject(mapOf("key" to JsonString("ui.theme"), "value" to JsonString("dark")))))
        s.a.service.retry(listOf(id("04")))
        assertBlocked(s, ClockActionBlock.PENDING_OPS)
    }

    @Test
    fun guardsAfterTheClockRepairBlockRemint() {
        val s = stage("remint-word-status.json")
        // an op acked while the clock was ahead but within tolerance keeps hlc_last 3 days ahead after the repair: G2
        val ahead = DefaultHlcRules.format(kotobaya.contracts.sync.HlcParts(s.sc.now + 3 * 86_400_000L, 0, s.sc.deviceA))
        s.a.d.store.transaction { it.syncState.put(SyncStateKeys.HLC_ACKED, ahead) }
        assertBlocked(s, ClockActionBlock.CLOCK_GUARD)
    }

    @Test
    fun nothingToRemintAndRebuildPendingBlock() {
        val s = stage("remint-word-status.json")
        s.a.d.store.transaction { it.bookkeeping.rebuildPending = true }
        assertBlocked(s, ClockActionBlock.REBUILD_PENDING)
        s.a.d.store.transaction { it.bookkeeping.rebuildPending = false }
        s.a.service.discard(listOf(id("03"), id("05"), id("06")))
        s.a.d.store.transaction { it.bookkeeping.rebuildPending = false }
        assertBlocked(s, ClockActionBlock.NOTHING_TO_REMINT)
    }

    @Test
    fun aStalePreviewIsRefused() {
        val s = stage("remint-preset-set-existing-card.json")
        val p = s.a.service.previewRemint()
        assertNull(p.blocked)
        // any change after the preview: here Retry of the D row
        s.a.service.retry(listOf(id("07")))
        assertEquals(RemintOutcome.StalePreview, s.a.service.remint(p))
        // pushed and rejected again: the rows look as before, but a sync happened in between (a second later)
        s.a.d.clock.now += 1_000
        s.server.nowMs += 1_000
        assertIs<kotobaya.sync.client.protocol.CycleResult.Completed>(s.a.cycle())
        val before = snapshot(s)
        assertEquals(RemintOutcome.StalePreview, s.a.service.remint(p))
        assertEquals(before, snapshot(s))
        val fresh = s.a.service.previewRemint()
        assertIs<RemintOutcome.Reminted>(s.a.service.remint(fresh))
        // a preview is single-use: after the Re-mint it is stale
        assertEquals(RemintOutcome.StalePreview, s.a.service.remint(fresh))
    }

    @Test
    fun discardListHoldsDOpsUnderTheirWindowParentAndUndoneReviews() {
        val s = stage("remint-preset-set-existing-card.json")
        val p = s.a.service.previewRemint()
        val d = p.discards.single()
        assertEquals(id("07"), d.row.opId)
        assertEquals(DiscardReason.DEPENDS_ON_WINDOW_ROW, d.reason)
        assertEquals("unknown_reference", d.code)
        assertEquals(id("06"), d.parentOpId, "grouped under the window preset's creation")
        assertEquals(mapOf<String?, List<DiscardEntry>>(id("06") to listOf(d)), p.groups)
        assertEquals(1, p.discardCount)

        // undo the window review locally: it is dropped (listed), and its undo is listed too
        val review = s.a.d.store.rows().single { it.opId == id("0e") }
        val reviewId = "0193a1b2-0003-7000-8000-00a10000000d"
        assertEquals("review.logged", review.opType)
        // the undo is minted with the clock still wrong (+10 days) so it is rejected like the rest
        s.a.d.clock.now = s.sc.now - s.sc.offset
        s.a.d.store.transaction { it.syncState.remove(SyncStateKeys.CLOCK_BLOCKED) }
        assertIs<MintResult.Minted>(s.a.d.minter.mint(OpType.REVIEW_UNDONE, JsonObject(mapOf("review_id" to JsonString(reviewId)))))
        s.a.d.clock.now = s.sc.now
        assertIs<kotobaya.sync.client.protocol.CycleResult.Completed>(s.a.cycle())
        val p2 = s.a.service.previewRemint()
        assertNull(p2.blocked)
        assertEquals(
            listOf(DiscardReason.DEPENDS_ON_WINDOW_ROW, DiscardReason.UNDONE_REVIEW, DiscardReason.UNDO_OF_REMINTED_REVIEW),
            p2.discards.map { it.reason },
        )
        assertEquals(0, p2.remintedReviews)
        val out = assertIs<RemintOutcome.Reminted>(s.a.service.remint(p2))
        assertEquals(p2.discards.map { it.row.opId }, out.discarded)
        assertTrue(out.minted.none { it.opType == OpType.REVIEW_LOGGED }, "the undone review is not re-logged")
    }

    @Test
    fun aRefusedMintRollsTheWholeRemintBack() {
        // C-10 hazard: the 3.4 block is still set (a response at exactly 5 min does not lift it) while the
        // preconditions hold; the minter refuses and the discards must not commit
        val s = stage("remint-word-status.json")
        s.a.d.store.transaction {
            it.bookkeeping.recordServerSkew(25 * 3_600_000L)
            it.bookkeeping.recordServerSkew(300_000L)
        }
        assertEquals("1", s.a.d.state(SyncStateKeys.CLOCK_BLOCKED))
        val p = s.a.service.previewRemint()
        assertNull(p.blocked)
        val before = snapshot(s)
        val out = assertIs<RemintOutcome.Unavailable>(s.a.service.remint(p))
        assertEquals(ClockActionBlock.MINT_REFUSED, out.reason)
        assertIs<MintRefusal.ClockFarFromServer>(out.refusal)
        assertEquals(before, snapshot(s), "every rejected row intact")
    }

    @Test
    fun anExceptionInsideTheTransactionLeavesEverythingUnchanged() {
        val s = stage("remint-deck-items.json")
        var applied = 0
        val failing = OpApplier { tx, op, origin ->
            if (++applied == 5) throw IllegalStateException("injected")
            s.a.d.applier.apply(tx, op, origin)
        }
        val service = RejectedOpsService(s.a.d.store, OpMinter(s.a.d.store, failing, s.a.d.clock, s.a.d.ids), s.a.d.clock, s.a.d.ids)
        val before = snapshot(s)
        val p = service.previewRemint()
        assertFailsWith<IllegalStateException> { service.remint(p) }
        assertEquals(before, snapshot(s))
        assertNotNull(s.a.d.state(SyncStateKeys.REMINT_OFFSET))
    }
}
