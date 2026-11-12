package kotobaya.sync.client.protocol

import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.examplePayload
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.bookkeeping
import kotobaya.sync.client.store.toOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResetClockAndSeamsTest {
    private val server = TestServer(NOW)
    private val a = TestDevice(DEV_A, server, NOW)
    private val b = TestDevice(DEV_B, server, NOW, seed = 2)
    private val hour = 3_600_000L

    private fun status(d: TestDevice, last: CycleResult? = null) =
        SyncStatus.of(d.store.transaction { SyncSnapshot.read(it) }, last, running = false, nowMs = d.clock.now)

    private fun mintBlocked(d: TestDevice): Boolean =
        d.minter.mint(OpType.SETTINGS_SET, examplePayload("settings.set.json")).let {
            it is MintResult.Refused && it.reason is MintRefusal.ClockFarFromServer
        }

    @Test
    fun clockBlockLiftsOnlyWhenAResponseShowsUnderFiveMinutes_25h_3h_4min() {
        a.setting("k.before", 1)
        // the device date is 25 h ahead
        a.clock.now = NOW + 25 * hour
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals("1", a.state(SyncStateKeys.CLOCK_BLOCKED))
        assertTrue(mintBlocked(a))
        assertIs<SyncPhase.ClockWrong>(status(a).phase)
        // the user fixes the date but not the time zone: still 3 h ahead
        a.clock.now = NOW + 3 * hour
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals((-3 * hour).toString(), a.state(SyncStateKeys.SERVER_SKEW_MS))
        assertEquals("1", a.state(SyncStateKeys.CLOCK_BLOCKED))
        assertTrue(mintBlocked(a)) // edits would carry HLCs 3 h in the future
        assertTrue(status(a).mintingBlocked)
        assertIs<SyncPhase.ClockWrong>(status(a).phase)
        // the clock is now 4 minutes off: the block lifts, a warning would not show
        a.clock.now = NOW + 4 * 60_000L
        assertIs<CycleResult.Completed>(a.cycle())
        assertNull(a.state(SyncStateKeys.CLOCK_BLOCKED))
        assertFalse(mintBlocked(a))
        val s = status(a)
        assertFalse(s.mintingBlocked)
        assertFalse(s.clockWarning)
        assertEquals(SyncPhase.Idle, s.phase)
    }

    @Test
    fun exactlyFiveMinutesDoesNotLiftTheBlockAndExactly24HoursDoesNotSetIt() {
        a.store.transaction { it.bookkeeping.recordServerSkew(86_400_000L) }
        assertNull(a.state(SyncStateKeys.CLOCK_BLOCKED))
        a.store.transaction { it.bookkeeping.recordServerSkew(-86_400_001L) }
        assertEquals("1", a.state(SyncStateKeys.CLOCK_BLOCKED))
        a.store.transaction { it.bookkeeping.recordServerSkew(300_000L) }
        assertEquals("1", a.state(SyncStateKeys.CLOCK_BLOCKED))
        a.store.transaction { it.bookkeeping.recordServerSkew(299_999L) }
        assertNull(a.state(SyncStateKeys.CLOCK_BLOCKED))
    }

    @Test
    fun logResetSetsPullSeqToZeroSoTheRebuildPushDoesNotSeeTheChangeAgain() {
        (1..10).forEach { b.setting("k.b$it", it) }
        assertIs<CycleResult.Completed>(b.cycle())
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals("10", a.state(SyncStateKeys.PULL_SEQ))
        val mine = a.setting("k.mine", 1)
        server.restore(toSeq = 2, newEpoch = "0193a1b2-00ee-7000-8000-0000000000e2")
        val changed = assertIs<CycleResult.LogChanged>(a.cycle())
        assertEquals(SyncStep.PUSH, changed.step)
        assertEquals(3L, a.rows().single { it.opId == mine.opId }.ackedSeq) // recorded in the new log
        a.engine.resetLog(changed)
        assertEquals("0", a.state(SyncStateKeys.PULL_SEQ))
        assertEquals("1", a.state(SyncStateKeys.REBUILD_PENDING))
        assertEquals("0193a1b2-00ee-7000-8000-0000000000e2", a.state(SyncStateKeys.LOG_EPOCH))
        assertEquals("0193a1b2-00ee-7000-8000-0000000000e2", a.state(SyncStateKeys.LOG_RESET_EPOCH))
        assertEquals("2", a.state(SyncStateKeys.DURABLE_SEQ))
        assertTrue(a.rows().all { it.state == PendingState.PENDING && it.ackedSeq == null && it.attempts == 0 })
        // rebuild step 0: the re-push gets seq 3 again (duplicate) and no second LogChanged
        assertEquals(CycleResult.RebuildPending(1), a.cycle())
        assertEquals(CycleResult.RebuildPending(0), a.cycle())
        assertEquals(3L, a.rows().single { it.opId == mine.opId }.ackedSeq)
        // the rebuild proceeds: pull from 0, clear the flag, sync as usual
        val pulled = assertIs<CycleResult.Completed>(a.pull())
        assertEquals(3, pulled.pulledOps)
        a.store.transaction { it.bookkeeping.rebuildPending = false }
        server.durableSeq = null // a verified backup now covers seq 3
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(emptyList(), a.rows())
        assertEquals("3", a.state(SyncStateKeys.PULL_SEQ))
    }

    @Test
    fun logResetZeroesAttemptsOfUnknownReferenceRejectionsOnly() {
        val x = a.setting("k.x", 1)
        val y = a.setting("k.y", 2)
        server.verdict = { op ->
            when (op.opId) {
                x.opId -> "unknown_reference" to "gone"
                y.opId -> "immutable" to "no"
                else -> null
            }
        }
        assertIs<CycleResult.Completed>(a.cycle())
        a.store.transaction { tx -> listOf(x, y).forEach { tx.outbox.setAttempts(it.opId, 4) } }
        a.engine.resetLog(CycleResult.LogChanged(SyncStep.PULL, server.logEpoch, 0, "0193a1b2-00ee-7000-8000-0000000000e3", 0, 0))
        val rows = a.rows().associateBy { it.opId }
        assertEquals(0, rows.getValue(x.opId).attempts)
        assertEquals(4, rows.getValue(y.opId).attempts)
        assertTrue(rows.values.all { it.state == PendingState.REJECTED })
    }

    @Test
    fun mintAtIsForRemintAndGivesTheRequestedTimeAboveHlcLastInsideAnOuterTransaction() {
        val first = a.setting("k.first", 1)
        val past = NOW - 2 * hour
        val future = NOW + 10_000
        // joins an outer transaction: rolled back with it
        assertFailsWith<IllegalStateException> {
            a.store.transaction {
                assertIs<MintResult.Minted>(a.minter.mintAll(listOf(MintRequest(OpType.SETTINGS_SET, examplePayload("settings.set.json"))), at = future))
                error("re-mint aborted")
            }
        }
        assertEquals(listOf(first.opId), a.rows().map { it.opId })
        val r = a.store.transaction {
            assertIs<MintResult.Minted>(a.minter.mintAll(listOf(MintRequest(OpType.SETTINGS_SET, examplePayload("settings.set.json"))), at = future))
        }
        assertEquals(future, DefaultHlcRules.wall(r.ops.single().hlc))
        // below hlc_last: just above it, as mint_at defines
        val low = assertIs<MintResult.Minted>(a.minter.mintAll(listOf(MintRequest(OpType.SETTINGS_SET, examplePayload("settings.set.json"))), at = past)).ops.single()
        assertEquals(future, DefaultHlcRules.wall(low.hlc))
        assertEquals(1, DefaultHlcRules.parse(low.hlc)?.counter)
        // same validation path
        val bad = MintRequest(OpType.SETTINGS_SET, JsonObject(mapOf("key" to kotobaya.contracts.sync.JsonString("Bad"), "value" to kotobaya.contracts.sync.JsonNull)))
        assertIs<MintRefusal.Invalid>(assertIs<MintResult.Refused>(a.minter.mintAll(listOf(bad), at = future)).reason)
    }

    @Test
    fun the413CapIsResetAfterABatchGetsThrough() {
        val ops = (0..9).map { a.setting("k.c$it", it) }
        a.transport.meta = { null }
        val element = Wire.element(a.rows().first())
        val per = DefaultCanonicalJson.utf8Size(element)
        server.maxPushBytes = 10L + 4 * per + 3 // exactly four ops fit
        assertIs<CycleResult.Completed>(a.push())
        val sizes = a.transport.pushedBodies.map { body -> Regex("\"op_id\"").findAll(body).count() }
        assertEquals(listOf(10, 5, 3, 7, 4, 3), sizes)
        assertEquals(ops.map { it.opId }, server.loggedOps().map { it.opId })
    }

    @Test
    fun pendingRowToOpIsTheMintedOp() {
        val op = a.setting("k.row", 7)
        assertEquals(op, a.rows().single().toOp())
        assertEquals(DefaultOpCodec.encode(op), Wire.element(a.rows().single()))
    }

    @Test
    fun completedReportsWhetherThePullAdvanced() {
        assertFalse(assertIs<CycleResult.Completed>(a.cycle()).pullAdvanced)
        b.setting("k.adv", 1)
        assertIs<CycleResult.Completed>(b.push())
        assertTrue(assertIs<CycleResult.Completed>(a.cycle()).pullAdvanced)
        assertFalse(assertIs<CycleResult.Completed>(a.cycle()).pullAdvanced)
    }

    @Test
    fun upgradeRequiredIsPersistedAcrossRestartsAndClearedOnceThePullGetsPast() {
        val unknown = DefaultCanonicalJson.decode(
            """{"op_id":"0193a1b2-00ff-7000-8000-0000000000f2","op_type":"thing.done","op_version":1,"entity_type":"thing",
               "entity_id":"x","device_id":"$DEV_B","hlc":"0193a1b2c001-0000-$DEV_B","payload":{},"caused_by_op_id":null}""",
        ).value as JsonObject
        val seq = server.plantRaw(unknown)
        assertIs<CycleResult.UpgradeRequired>(a.cycle())
        // after a restart there is no last result: the status still says upgrade required
        val phase = assertIs<SyncPhase.UpgradeRequired>(status(a, last = null).phase)
        assertEquals(seq, phase.seq)
        assertEquals("0193a1b2-00ff-7000-8000-0000000000f2", phase.opId)
        assertTrue(phase.unsupported)
        // the server is fixed (or the app updated): the op at that seq is now one the client knows
        val fixed = b.setting("k.fixed", 1)
        assertIs<CycleResult.Completed>(b.push())
        server.log[0] = JsonObject(DefaultOpCodec.encodeTree(fixed.copy(seq = seq)).members)
        server.log.removeAt(1)
        assertIs<CycleResult.Completed>(a.cycle())
        assertNull(a.state(SyncStateKeys.UPGRADE_REQUIRED))
        assertEquals(SyncPhase.Idle, status(a).phase)
    }

    @Test
    fun refusedCacheCountComesFromTheLearningLayer() {
        val snap = a.store.transaction { tx -> SyncSnapshot.read(tx) { it.applied.size + 2 } }
        assertEquals(2, snap.refusedCacheCards)
        assertEquals(2, SyncStatus.of(snap, null, false, NOW).refusedCacheCards)
    }
}
