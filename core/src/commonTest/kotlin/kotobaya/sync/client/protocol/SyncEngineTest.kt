package kotobaya.sync.client.protocol

import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.examplePayload
import kotobaya.sync.client.mint.with
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.OutboxTable
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.SyncStateKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

const val DEV_A = "01931f3e-0000-7000-8000-0000000000a1"
const val DEV_B = "01931f3e-0000-7000-8000-0000000000b2"
const val NOW = 1_733_584_666_000L

fun TestDevice.setting(key: String, value: Int): Op {
    val p = JsonObject(mapOf("key" to JsonString(key), "value" to JsonNumber(value.toDouble())))
    return assertIs<MintResult.Minted>(minter.mint(OpType.SETTINGS_SET, p)).ops.single()
}

class SyncEngineTest {
    private val server = TestServer(NOW)
    private val a = TestDevice(DEV_A, server, NOW)
    private val b = TestDevice(DEV_B, server, NOW, seed = 2)

    @Test
    fun cyclePushesInHlcOrderAcksPullsOwnOpsBackPurgesAndSetsLastSyncAt() {
        val ops = (1..3).map { a.setting("k.a$it", it) }
        val r = assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(3, r.pushedOps)
        assertEquals(3, r.pulledOps)
        assertEquals(3, r.purgedRows)
        assertEquals(ops.map { it.opId }, server.loggedOps().map { it.opId })
        // the pushed body is exactly OpCodec.encode of each op, in ascending hlc
        assertEquals(ops.joinToString(",", "{\"ops\":[", "]}") { DefaultOpCodec.encode(it) }, server.pushBodies.single())
        assertEquals(emptyList(), a.rows())
        assertEquals("3", a.state(SyncStateKeys.PULL_SEQ))
        assertEquals(server.logEpoch, a.state(SyncStateKeys.LOG_EPOCH))
        assertEquals("3", a.state(SyncStateKeys.DURABLE_SEQ))
        assertEquals(ops.last().hlc, a.state(SyncStateKeys.HLC_ACKED))
        assertEquals(NOW.toString(), a.state(SyncStateKeys.LAST_SYNC_AT))
        // own ops come back and are applied again (idempotent apply, SYNC.md 6.1)
        assertEquals(ops.map { it.opId }, a.store.learning().pulledIds())
    }

    @Test
    fun idempotencyPushingTheSameOutboxTwiceAfterALostResponseAcksEachOpOnce() {
        val ops = (1..4).map { a.setting("k.x$it", it) }
        a.transport.loseNextPushResponse = true
        val first = assertIs<CycleResult.Failed>(a.push())
        assertEquals(FailureKind.OFFLINE, first.kind)
        assertTrue(a.rows().all { it.state == PendingState.PENDING && it.attempts == 1 })
        assertEquals(4, server.log.size) // the server did apply them
        assertIs<CycleResult.Completed>(a.push())
        assertEquals(4, server.log.size) // ... once
        assertEquals(ops.map { it.opId }, server.loggedOps().map { it.opId })
        val rows = a.rows()
        assertTrue(rows.all { it.state == PendingState.ACKED })
        assertEquals(listOf(1L, 2L, 3L, 4L), rows.map { it.ackedSeq })
        assertTrue(server.pushBodies[1].contains("\"op_id\":\"${ops[0].opId}\"")) // same op ids again
    }

    @Test
    fun aReplayedPullPageAppliesNothingTwice() {
        b.setting("k.b1", 1)
        b.setting("k.b2", 2)
        assertIs<CycleResult.Completed>(b.cycle())
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(2, a.store.learning().pulledIds().size)
        // the transport hands the same (old) page again
        a.transport.replayNextPull = true
        assertIs<CycleResult.Completed>(a.pull())
        assertEquals(2, a.store.learning().pulledIds().size)
        assertEquals("2", a.state(SyncStateKeys.PULL_SEQ))
        // and a page that overlaps what was applied applies only the new ops
        b.setting("k.b3", 3)
        assertIs<CycleResult.Completed>(b.push())
        val stale = server.pull(0) as TransportResponse.Ok
        val engine = SyncEngine(a.store, FixedPullTransport(stale), a.applier, a.clock)
        assertIs<CycleResult.Completed>(runSync { engine.pullAll() })
        assertEquals(3, a.store.learning().pulledIds().size)
        assertEquals(a.store.learning().pulledIds().toSet().size, 3)
    }

    @Test
    fun killMidPush() {
        val ops = (1..3).map { a.setting("k.kill$it", it) }
        // 1. dies before the server saw anything
        a.transport.failNextPushBeforeServer = true
        assertIs<CycleResult.Failed>(a.push())
        assertEquals(0, server.log.size)
        assertTrue(a.rows().all { it.state == PendingState.PENDING })
        // 2. dies after the server applied (response lost)
        a.transport.loseNextPushResponse = true
        assertIs<CycleResult.Failed>(a.push())
        assertEquals(3, server.log.size)
        assertTrue(a.rows().all { it.state == PendingState.PENDING })
        // 3. the device crashes while recording the results (store throws on the second ack)
        val crashing = SyncEngine(CrashOnAckStore(a.store, afterAcks = 1), a.transport, a.applier, a.clock)
        runCatching { runSync { crashing.pushAll() } }
        assertEquals(ops.map { it.opId }.toSet(), a.rows().map { it.opId }.toSet()) // none missing
        assertTrue(a.rows().all { it.state == PendingState.PENDING }) // the whole record transaction rolled back
        // retry converges: every op acked exactly once, the server log unchanged
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(ops.map { it.opId }, server.loggedOps().map { it.opId })
        assertEquals(emptyList(), a.rows())
    }

    @Test
    fun ackedRowSurvivesUntilAckedSeqIsAtMostBothPullSeqAndDurableSeq() {
        server.durableSeq = 0
        a.setting("k.p1", 1)
        a.setting("k.p2", 2)
        assertIs<CycleResult.Completed>(a.push())
        assertEquals(0, a.engine.purge()) // pull_seq 0
        assertIs<CycleResult.Completed>(a.pull())
        assertEquals(0, a.engine.purge()) // pull_seq 2, durable_seq 0
        assertEquals(2, a.rows().count { it.state == PendingState.ACKED })
        server.durableSeq = 1
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(listOf(2L), a.rows().map { it.ackedSeq }) // seq 1 purged, seq 2 kept
        server.durableSeq = 2
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(emptyList(), a.rows())
    }

    @Test
    fun durableSeqIsMonotonicWithinAnEpochAndANewEpochsDurableSeqIsNotMixedWithTheOld() {
        server.durableSeq = 0
        (1..3).forEach { a.setting("k.e$it", it) }
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals("0", a.state(SyncStateKeys.DURABLE_SEQ))
        assertEquals(3, a.rows().size)
        server.durableSeq = 2
        assertIs<CycleResult.Completed>(a.pull())
        assertEquals("2", a.state(SyncStateKeys.DURABLE_SEQ))
        server.durableSeq = 1 // a misbehaving server lowering it within the epoch
        assertIs<CycleResult.Completed>(a.pull())
        assertEquals("2", a.state(SyncStateKeys.DURABLE_SEQ))

        // restore: new epoch whose durable_seq (3) would cover the old acked seqs 3 if mixed
        b.setting("k.b", 1)
        server.restore(toSeq = 2, newEpoch = "0193a1b2-00ee-7000-8000-000000000002")
        assertIs<CycleResult.Completed>(b.push()) // b had no epoch yet: stores the new one, gets seq 3
        server.durableSeq = null // new epoch reports durable_seq 3 >= every old acked seq
        val r = assertIs<CycleResult.LogChanged>(a.pull())
        assertEquals("0193a1b2-00ee-7000-8000-000000000002", r.newEpoch)
        assertEquals(SyncStep.PULL, r.step)
        // nothing of the new epoch was stored, nothing applied, nothing purged
        assertEquals("0193a1b2-00ee-7000-8000-000000000001", a.state(SyncStateKeys.LOG_EPOCH))
        assertEquals("2", a.state(SyncStateKeys.DURABLE_SEQ))
        assertEquals(3L, r.durableSeq)
        assertEquals("3", a.state(SyncStateKeys.PULL_SEQ))
        assertEquals(3, a.rows().size)
        assertEquals(3, a.store.learning().pulledIds().size)
        // mixing the new durable_seq (3) with the old acked seqs would purge all three rows
        assertIs<CycleResult.LogChanged>(a.cycle())
        assertEquals(3, a.rows().size)
    }

    @Test
    fun logChangedOnPushStillRecordsTheResults() {
        a.setting("k.l1", 1)
        assertIs<CycleResult.Completed>(a.cycle())
        server.restore(0, "0193a1b2-00ee-7000-8000-000000000003")
        val op = a.setting("k.l2", 2)
        val r = assertIs<CycleResult.LogChanged>(a.cycle())
        assertEquals(SyncStep.PUSH, r.step)
        assertEquals(1L, r.highWaterSeq)
        assertEquals(PendingState.ACKED, a.rows().single { it.opId == op.opId }.state)
        assertEquals("0193a1b2-00ee-7000-8000-000000000001", a.state(SyncStateKeys.LOG_EPOCH))
    }

    @Test
    fun highWaterBelowPullSeqIsALogChangeEvenWithTheSameEpoch() {
        (1..3).forEach { b.setting("k.h$it", it) }
        assertIs<CycleResult.Completed>(b.cycle())
        assertIs<CycleResult.Completed>(a.cycle())
        server.restore(1, server.logEpoch) // log went backwards without an epoch change
        val r = assertIs<CycleResult.LogChanged>(a.cycle())
        assertEquals(1L, r.highWaterSeq)
        assertEquals(3L, r.storedPullSeq)
    }

    @Test
    fun unknownPulledOpStopsThePullAndSetsUpgradeRequiredWithoutSkipping() {
        b.setting("k.u1", 1)
        assertIs<CycleResult.Completed>(b.push())
        val unknown = DefaultCanonicalJson.decode(
            """{"op_id":"0193a1b2-00ff-7000-8000-0000000000f1","op_type":"thing.done","op_version":1,"entity_type":"thing",
               "entity_id":"x","device_id":"$DEV_B","hlc":"0193a1b2c001-0000-$DEV_B","payload":{"z":1},"caused_by_op_id":null}""",
        ).value as JsonObject
        val unknownSeq = server.plantRaw(unknown)
        b.setting("k.u2", 2)
        assertIs<CycleResult.Completed>(b.push())

        a.setting("k.mine", 1)
        val r = assertIs<CycleResult.UpgradeRequired>(a.cycle())
        assertEquals(unknownSeq, r.seq)
        assertEquals("0193a1b2-00ff-7000-8000-0000000000f1", r.opId)
        assertTrue(r.unsupported)
        assertEquals((unknownSeq - 1).toString(), a.state(SyncStateKeys.PULL_SEQ))
        assertEquals(1, a.store.learning().pulledIds().size) // only the op before it
        assertEquals(PendingState.ACKED, a.rows().single().state) // pushing went on
        assertNull(a.state(SyncStateKeys.LAST_SYNC_AT))
        // every later cycle retries at the same op and never skips it
        assertIs<CycleResult.UpgradeRequired>(a.cycle())
        assertEquals(listOf(0L, unknownSeq - 1), a.transport.pullSinces)
        assertEquals((unknownSeq - 1).toString(), a.state(SyncStateKeys.PULL_SEQ))
        val status = SyncStatus.of(a.store.transaction { SyncSnapshot.read(it) }, a.cycle(), running = false, nowMs = NOW)
        val phase = assertIs<SyncPhase.UpgradeRequired>(status.phase)
        assertEquals(unknownSeq, phase.seq)
    }

    @Test
    fun invalidPulledOpOfASupportedTypeStopsLikeAnUnknownOne() {
        b.setting("k.i1", 1)
        assertIs<CycleResult.Completed>(b.push())
        val tree = DefaultCanonicalJson.decode(SyncExamplesAccess.invalidInstance("note-created-tag-ideographic-space.json")).value as JsonObject
        val seq = server.plantRaw(JsonObject(tree.members + ("caused_by_op_id" to kotobaya.contracts.sync.JsonNull)))
        val r = assertIs<CycleResult.UpgradeRequired>(a.cycle())
        assertEquals(seq, r.seq)
        assertEquals(false, r.unsupported)
        assertTrue(r.error.contains("/payload/tags"), r.error)
        assertEquals((seq - 1).toString(), a.state(SyncStateKeys.PULL_SEQ))
    }

    @Test
    fun aPullPageLargerThanMaxPushBytesIsAccepted() {
        server.maxPushBytes = 8_192
        server.maxOpBytes = 4_096
        repeat(6) { i ->
            val p = examplePayload("note.created.json").let { n ->
                n.with("id", JsonString("0193a1b2-0001-7000-8000-00000000010$i"))
                    .with("fields", JsonObject(mapOf("sentence" to JsonString("x".repeat(3_000)))))
            }
            assertIs<MintResult.Minted>(b.minter.mint(OpType.NOTE_CREATED, p))
        }
        assertIs<CycleResult.Completed>(b.push())
        val page = server.pull(0) as TransportResponse.Ok
        assertTrue(DefaultCanonicalJson.utf8Size(page.body) > server.maxPushBytes + server.maxOpBytes)
        val r = assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(6, r.pulledOps)
    }

    @Test
    fun opsWithDeviceAppliesFalseAreSkippedButTheCursorAdvancesAndEveryHlcIsObserved() {
        val po = DefaultOpCodec.decode(SyncExamplesAccess.op("parse_override.put.json"), kotobaya.contracts.sync.OpSource.PUSHED)
        val op = assertIs<kotobaya.contracts.sync.OpDecodeResult.Known>(po).op
        val far = op.copy(hlc = "0193a1b2ffff-0007-${op.deviceId}")
        server.append(far, null)
        val r = assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(1, r.pulledOps)
        assertEquals(emptyList(), a.store.learning().applied)
        assertEquals("1", a.state(SyncStateKeys.PULL_SEQ))
        assertEquals(far.hlc, a.state(SyncStateKeys.HLC_LAST))
        // the next mint is above the observed clock
        val mine = a.setting("k.after", 1)
        assertTrue(mine.hlc > far.hlc)
        assertEquals(DefaultHlcRules.wall(far.hlc), DefaultHlcRules.wall(mine.hlc))
    }

    @Test
    fun pageWithSeqsOutOfOrderIsAProtocolFailureAndAppliesNothing() {
        val ops = (1..2).map { b.setting("k.o$it", it) }
        assertIs<CycleResult.Completed>(b.push())
        val good = (server.pull(0) as TransportResponse.Ok).body
        val tree = DefaultCanonicalJson.decode(good).value as JsonObject
        val list = (tree.members.getValue("ops") as kotobaya.contracts.sync.JsonArray).items
        val swapped = JsonObject(tree.members + ("ops" to kotobaya.contracts.sync.JsonArray(list.reversed())))
        val engine = SyncEngine(a.store, FixedPullTransport(TransportResponse.Ok(DefaultCanonicalJson.encode(swapped))), a.applier, a.clock)
        val r = assertIs<CycleResult.Failed>(runSync { engine.pullAll() })
        assertEquals(FailureKind.PROTOCOL, r.kind)
        assertEquals(emptyList(), a.store.learning().applied)
        assertNull(a.state(SyncStateKeys.PULL_SEQ))
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(ops.map { it.opId }, a.store.learning().pulledIds())
    }

    @Test
    fun applierFailureOnAPulledOpRollsBackThePageAndRetries() {
        val ops = (1..3).map { b.setting("k.f$it", it) }
        assertIs<CycleResult.Completed>(b.push())
        a.applier.failOn = { it.opId == ops[1].opId }
        val r = assertIs<CycleResult.Failed>(a.cycle())
        assertEquals(FailureKind.LOCAL_APPLY, r.kind)
        assertEquals(emptyList(), a.store.learning().applied)
        assertNull(a.state(SyncStateKeys.PULL_SEQ))
        a.applier.failOn = { false }
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(ops.map { it.opId }, a.store.learning().pulledIds())
    }

    @Test
    fun http413HalvesTheBatchAndASingleOversizeOpIsRejectedLocallyAsOpTooLarge() {
        server.maxPushBytes = 1_500 // the device never read /meta: it batches for 8 MiB
        a.transport.meta = { null }
        val small = (1..6).map { a.setting("k.s$it", it) }
        val big = examplePayload("note.created.json").with("fields", JsonObject(mapOf("sentence" to JsonString("y".repeat(1_600)))))
        val bigOp = assertIs<MintResult.Minted>(a.minter.mint(OpType.NOTE_CREATED, big)).ops.single()
        val after = a.setting("k.after", 9)
        val r = assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(8, r.pushedOps)
        val rows = a.store.rows().associateBy { it.opId }
        val bigRow = rows.getValue(bigOp.opId)
        assertEquals(PendingState.REJECTED, bigRow.state)
        assertTrue(bigRow.lastError!!.startsWith("op_too_large: "))
        assertEquals(0, bigRow.attempts) // 413 never counts towards the backoff
        assertEquals((small + after).map { it.opId }, server.loggedOps().map { it.opId })
        assertEquals(1, rows.size) // the others were acked and purged
    }

    @Test
    fun batchesStayWithinStoredMaxPushBytes() {
        server.maxPushBytes = 1_200
        (1..10).forEach { a.setting("k.batch$it", it) }
        assertIs<CycleResult.Completed>(a.cycle())
        assertTrue(a.transport.pushedBodies.size > 1)
        assertTrue(a.transport.pushedBodies.all { DefaultCanonicalJson.utf8Size(it) <= 1_200 })
        assertEquals(10, server.log.size)
    }

    @Test
    fun rejectedRowsKeepCodeAndMessageAreNeverPurgedAndTheFirstClockCodeSetsRemintOffset() {
        val x = a.setting("k.r1", 1)
        val y = a.setting("k.r2", 2)
        val z = a.setting("k.r3", 3)
        server.verdict = { op ->
            when (op.opId) {
                x.opId -> "clock_ahead" to "hlc ahead"
                y.opId -> "some_future_code" to "who knows"
                else -> null
            }
        }
        server.nowMs = NOW + 7_000
        a.clock.now = NOW + 2_000
        assertIs<CycleResult.Completed>(a.cycle())
        val rows = a.rows().associateBy { it.opId }
        assertEquals("clock_ahead: hlc ahead", rows.getValue(x.opId).lastError)
        assertEquals(PendingState.REJECTED, rows.getValue(y.opId).state)
        assertEquals("some_future_code: who knows", rows.getValue(y.opId).lastError)
        assertTrue(z.opId !in rows)
        assertEquals("5000", a.state(SyncStateKeys.REMINT_OFFSET))
        // a later clock rejection does not overwrite it; rejected rows survive every purge
        val w = a.setting("k.r4", 4)
        server.verdict = { if (it.opId == w.opId) "clock_behind" to "old" else null }
        server.nowMs = NOW + 100_000
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals("5000", a.state(SyncStateKeys.REMINT_OFFSET))
        assertEquals(3, a.rows().count { it.state == PendingState.REJECTED })
    }

    @Test
    fun transientFailuresKeepRowsPendingAndBumpAttemptsButAuthDoesNot() {
        a.setting("k.t1", 1)
        a.transport.offline = true
        assertEquals(FailureKind.OFFLINE, assertIs<CycleResult.Failed>(a.cycle()).kind)
        a.transport.offline = false
        val e500 = SyncEngine(a.store, StatusTransport(503), a.applier, a.clock)
        assertEquals(FailureKind.SERVER_ERROR, assertIs<CycleResult.Failed>(runSync { e500.cycle() }).kind)
        val e400 = SyncEngine(a.store, StatusTransport(400), a.applier, a.clock)
        assertEquals(FailureKind.BAD_REQUEST, assertIs<CycleResult.Failed>(runSync { e400.cycle() }).kind)
        val e401 = SyncEngine(a.store, StatusTransport(401), a.applier, a.clock)
        assertEquals(FailureKind.AUTH, assertIs<CycleResult.Failed>(runSync { e401.cycle() }).kind)
        val row = a.rows().single()
        assertEquals(PendingState.PENDING, row.state)
        assertEquals(3, row.attempts)
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(emptyList(), a.rows())
    }

    @Test
    fun rebuildPendingPushesButDoesNotPull() {
        b.setting("k.rb", 1)
        assertIs<CycleResult.Completed>(b.push())
        a.setting("k.mine", 1)
        a.store.transaction { it.syncState.put(SyncStateKeys.REBUILD_PENDING, "1") }
        assertEquals(CycleResult.RebuildPending(1), a.cycle())
        assertEquals(PendingState.ACKED, a.rows().single().state)
        assertEquals(emptyList(), a.transport.pullSinces)
    }

    @Test
    fun responsesUpdateTheServerSkewUsedForClockWarnings() {
        a.setting("k.c", 1)
        server.nowMs = NOW + 600_000
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals("600000", a.state(SyncStateKeys.SERVER_SKEW_MS))
        val status = SyncStatus.of(a.store.transaction { SyncSnapshot.read(it) }, null, running = false, nowMs = NOW)
        assertTrue(status.clockWarning)
        assertEquals(SyncPhase.Idle, status.phase)
        server.nowMs = NOW + 90_000_000
        assertIs<CycleResult.Completed>(a.cycle())
        val blocked = SyncStatus.of(a.store.transaction { SyncSnapshot.read(it) }, null, running = false, nowMs = NOW)
        assertIs<SyncPhase.ClockWrong>(blocked.phase)
        assertTrue(blocked.mintingBlocked)
        assertIs<MintResult.Refused>(a.minter.mint(OpType.SETTINGS_SET, examplePayload("settings.set.json")))
        server.nowMs = NOW + 1_000
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<MintResult.Minted>(a.minter.mint(OpType.SETTINGS_SET, examplePayload("settings.set.json")))
    }

    @Test
    fun ownOpsComeBackAsPulledApplies() {
        val op = a.setting("k.own", 1)
        a.cycle()
        val applied = a.store.learning().applied
        assertEquals(listOf(ApplyOrigin.LOCAL, ApplyOrigin.PULLED), applied.filter { it.opId == op.opId }.map { it.origin })
    }
}

/** Access to the embedded contract examples. */
object SyncExamplesAccess {
    fun op(name: String): String = kotobaya.sync.SyncExamples.ops.getValue(name)

    fun invalidInstance(name: String): String {
        val doc = DefaultCanonicalJson.decode(kotobaya.sync.SyncExamples.invalid.getValue(name)).value as JsonObject
        return DefaultCanonicalJson.encode(doc.members.getValue("instance"))
    }
}

/** Always answers a pull with [page]; pushes are not expected. */
class FixedPullTransport(private val page: TransportResponse) : SyncTransport {
    override suspend fun push(body: String): TransportResponse = error("no push expected")

    override suspend fun pull(since: Long): TransportResponse = page

    override suspend fun limits(): SyncLimits? = null
}

/** Answers every call with HTTP [status]. */
class StatusTransport(private val status: Int) : SyncTransport {
    override suspend fun push(body: String): TransportResponse = TransportResponse.HttpError(status, "HTTP $status")

    override suspend fun pull(since: Long): TransportResponse = TransportResponse.HttpError(status, "HTTP $status")

    override suspend fun limits(): SyncLimits? = null
}

/** A store whose outbox throws on the ack after [afterAcks] successful ones (a crash while recording). */
class CrashOnAckStore<L>(private val inner: DeviceStore<L>, private val afterAcks: Int) : DeviceStore<L> {
    private var acks = 0

    override fun <R> transaction(block: (StoreTransaction<L>) -> R): R = inner.transaction { tx ->
        block(
            object : StoreTransaction<L> by tx {
                override val outbox: OutboxTable = object : OutboxTable by tx.outbox {
                    override fun markAcked(opId: String, seq: Long) {
                        if (acks++ >= afterAcks) throw IllegalStateException("process killed")
                        tx.outbox.markAcked(opId, seq)
                    }
                }
            },
        )
    }
}
