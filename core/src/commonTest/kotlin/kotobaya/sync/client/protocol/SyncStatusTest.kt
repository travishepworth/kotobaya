package kotobaya.sync.client.protocol

import kotobaya.contracts.sync.ClockGuardResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncStatusTest {
    private val snap = SyncSnapshot(pending = 2, acked = 1, rejected = 3, lastSyncAt = 5L, rebuildPending = false, hlcLast = null, serverSkewMs = 0L)

    @Test
    fun statusIsAPureFunctionOfStoreAndLastResult() {
        val idle = SyncStatus.of(snap, CycleResult.Completed(1, 1, 0), running = false, nowMs = NOW)
        assertEquals(SyncPhase.Idle, idle.phase)
        assertEquals(listOf(2, 1, 3), listOf(idle.pendingCount, idle.ackedCount, idle.rejectedCount))
        assertEquals(5L, idle.lastSyncAt)
        assertFalse(idle.mintingBlocked)
        assertEquals(SyncPhase.Syncing, SyncStatus.of(snap, null, running = true, nowMs = NOW).phase)
        val offline = SyncStatus.of(snap, CycleResult.Failed(FailureKind.OFFLINE, SyncStep.PUSH, "no route"), false, NOW)
        assertEquals(SyncPhase.Offline, offline.phase)
        assertEquals("no route", offline.lastError)
        assertEquals(SyncPhase.ServerUnreachable(503), SyncStatus.of(snap, CycleResult.Failed(FailureKind.SERVER_ERROR, SyncStep.PULL, "x", 503), false, NOW).phase)
        assertEquals(SyncPhase.AuthRequired, SyncStatus.of(snap, CycleResult.Failed(FailureKind.AUTH, SyncStep.PULL, "x", 401), false, NOW).phase)
        assertEquals(SyncPhase.ClientError(400), SyncStatus.of(snap, CycleResult.Failed(FailureKind.BAD_REQUEST, SyncStep.PUSH, "x", 400), false, NOW).phase)
        val up = SyncStatus.of(snap, CycleResult.UpgradeRequired(7, "id", "x.y", 2, true, "unsupported"), false, NOW)
        assertEquals(SyncPhase.UpgradeRequired(7, "id", true, "unsupported"), up.phase)
        assertEquals(SyncPhase.Rebuilding, SyncStatus.of(snap.copy(rebuildPending = true), null, false, NOW).phase)
        assertTrue(SyncStatus.of(snap.copy(rebuildPending = true), null, false, NOW).mintingBlocked)
    }

    @Test
    fun clockWarningsAndBlocks() {
        assertFalse(SyncStatus.of(snap.copy(serverSkewMs = 300_000), null, false, NOW).clockWarning)
        assertTrue(SyncStatus.of(snap.copy(serverSkewMs = -300_001), null, false, NOW).clockWarning)
        val far = SyncStatus.of(snap.copy(serverSkewMs = 86_400_001), null, false, NOW)
        assertEquals(SyncPhase.ClockWrong(null, 86_400_001), far.phase)
        assertTrue(far.mintingBlocked)
        val g1 = SyncStatus.of(snap, null, false, nowMs = 1_000L)
        assertEquals(SyncPhase.ClockWrong(ClockGuardResult.G1_BEFORE_FLOOR, 0L), g1.phase)
    }

    @Test
    fun backoffDoublesFromFiveSecondsToFifteenMinutesWithJitterBelowTheCap() {
        assertEquals(0L, SyncBackoff.delayMs(0, 0.5))
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L), (1..4).map { SyncBackoff.delayMs(it, 0.0) })
        assertEquals(900_000L, SyncBackoff.delayMs(9, 0.0))
        assertEquals(900_000L, SyncBackoff.delayMs(1_000, 0.0))
        assertEquals(4_000L, SyncBackoff.delayMs(1, 0.999_999_999).let { it - it % 1_000 })
        for (n in 1..20) {
            val d = SyncBackoff.delayMs(n, 0.7)
            assertTrue(d in 1..SyncBackoff.CAP_MS)
        }
        assertTrue(SyncBackoff.isFailure(CycleResult.Failed(FailureKind.OFFLINE, SyncStep.PUSH, "x")))
        assertFalse(SyncBackoff.isFailure(CycleResult.Failed(FailureKind.AUTH, SyncStep.PUSH, "x")))
        assertFalse(SyncBackoff.isFailure(CycleResult.Completed(0, 0, 0)))
    }
}
