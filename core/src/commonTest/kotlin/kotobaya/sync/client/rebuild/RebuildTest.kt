package kotobaya.sync.client.rebuild

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.ApplyDevice
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.projection
import kotobaya.sync.client.apply.serverReplica
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.SyncEngine
import kotobaya.sync.client.protocol.SyncLimits
import kotobaya.sync.client.protocol.SyncTransport
import kotobaya.sync.client.protocol.TestServer
import kotobaya.sync.client.protocol.TransportResponse
import kotobaya.sync.client.protocol.runSync
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.SyncStateTable
import kotobaya.sync.client.store.bookkeeping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_733_584_666_000L
private const val A = "01931f3e-0000-7000-8000-0000000000a1"
private const val B = "01931f3e-0000-7000-8000-0000000000b2"
private const val E2 = "0193a1b2-00ee-7000-8000-000000000002"

/** A transport that fails every pull after the first [okPulls] (the app is killed / goes offline mid-rebuild). */
class FailingPulls(private val inner: SyncTransport, private val okPulls: Int) : SyncTransport {
    private var pulls = 0

    override suspend fun push(body: String): TransportResponse = inner.push(body)

    override suspend fun pull(since: Long): TransportResponse =
        if (pulls++ < okPulls) inner.pull(since) else TransportResponse.NetworkError("killed")

    override suspend fun limits(): SyncLimits? = inner.limits()
}

/** A store whose transaction dies (before commit) when it deletes `rebuild_pending`: killed in rebuild step 3. */
class KillWhenRebuildClears<L>(private val inner: DeviceStore<L>) : DeviceStore<L> {
    class Killed : RuntimeException("killed")

    override fun <R> transaction(block: (StoreTransaction<L>) -> R): R = inner.transaction { tx ->
        block(
            object : StoreTransaction<L> by tx {
                override val syncState: SyncStateTable = object : SyncStateTable by tx.syncState {
                    override fun remove(key: String) {
                        if (key == SyncStateKeys.REBUILD_PENDING) throw Killed()
                        tx.syncState.remove(key)
                    }
                }
            },
        )
    }
}

/** SYNC.md 6.2 log reset, 10.3 rebuild, 12.5 / 12.5.1 restores, the `unknown_reference` auto-retry. */
class RebuildTest {
    private val server = TestServer(NOW).also { s ->
        // a real server refuses an op whose target or parent it does not have (SYNC.md 7.7)
        s.verdict = { op -> DeviceOpApplier.missingReference(serverReplica(s), op)?.let { "unknown_reference" to "no $it" } }
    }
    private val note = "0193a1b2-0001-7000-8000-000000000001"
    private val card = "0193a1b2-0002-7000-8000-000000000001"

    private fun device(id: String, seed: Int): ApplyDevice = ApplyDevice(id, server, NOW, seed)

    private fun mint(d: ApplyDevice, type: OpType, vararg payload: Pair<String, JsonValue>): Op =
        assertIs<MintResult.Minted>(d.minter.mint(type, JsonObject(mapOf(*payload))), type.wire).ops.single()

    private fun mintWord(d: ApplyDevice) {
        mint(d, OpType.NOTE_CREATED, "id" to JsonString(note), "note_type_id" to JsonString(Reserved.NOTE_TYPE_VOCAB), "entry_seq" to JsonNumber(1358280.0))
        mint(d, OpType.CARD_CREATED, "id" to JsonString(card), "note_id" to JsonString(note), "kind" to JsonString("meaning"))
    }

    private fun mintReview(d: ApplyDevice, i: Int): Op {
        d.clock.now += 60_000
        val id = "0193a1b2-0003-7000-8000-0000000000" + (0x10 + i).toString(16)
        return mint(
            d, OpType.REVIEW_LOGGED,
            "id" to JsonString(id), "card_id" to JsonString(card), "reviewed_at" to JsonNumber(d.clock.now.toDouble()),
            "utc_offset_min" to JsonNumber(540.0), "review_type" to JsonString(if (i == 0) "learning" else "scheduled"),
            "rating" to JsonNumber(3.0), "button" to JsonString("good"),
        )
    }

    private fun assertConverged(vararg devices: ApplyDevice) {
        val want = projection(serverReplica(server))
        for (d in devices) {
            assertEquals(want, projection(d.tables()), "${d.deviceId} vs server")
            assertNull(d.state(SyncStateKeys.REBUILD_PENDING), "${d.deviceId} still rebuilding")
            assertEquals(server.logEpoch, d.state(SyncStateKeys.LOG_EPOCH))
            assertEquals(server.highWaterSeq.toString(), d.state(SyncStateKeys.PULL_SEQ))
        }
    }

    private fun logged(): Set<String> = server.loggedOps().map { it.opId }.toSet()

    @Test
    fun aServerRestoredFromBackupLosesNoOp() {
        // SYNC.md 12.5
        val a = device(A, 1)
        val b = device(B, 2)
        mintWord(a)
        assertIs<CycleResult.Completed>(a.cycle())
        server.durableSeq = 2 // verified backup of seq 1..2
        assertIs<CycleResult.Completed>(b.cycle())
        val reviews = (0 until 5).map { mintReview(a, it) }
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(7L, server.highWaterSeq)
        assertEquals(5, a.store.rows().count { it.state == PendingState.ACKED }, "acked_seq 3..7 > durable_seq 2: kept")

        server.restore(2, E2) // disk lost
        b.clock.now += 60_000
        val tag = mint(b, OpType.NOTE_UPDATED, "id" to JsonString(note), "set" to JsonObject(mapOf("tags" to JsonArray(listOf(JsonString("anime"))))))
        val suspend = mint(b, OpType.CARD_SUSPENDED, "id" to JsonString(card))
        assertIs<CycleResult.Completed>(b.cycle(), "B: new epoch, log reset, re-push (duplicate), rebuild from 0")
        assertEquals(E2, b.state(SyncStateKeys.LOG_EPOCH))

        assertIs<CycleResult.Completed>(a.cycle(), "A: new epoch on pull, log reset, re-push its 5 reviews, rebuild")
        assertIs<CycleResult.Completed>(b.cycle())
        assertEquals(9L, server.highWaterSeq)
        assertTrue(logged().containsAll(reviews.map { it.opId } + tag.opId + suspend.opId), "no op lost")
        assertEquals(logged().size, server.loggedOps().size)
        assertConverged(a, b)
        val cardRow = a.tables().get(LearningTable.CARD, listOf(JsonString(card)))!!
        assertEquals(JsonNumber(5.0), cardRow["reps"])
        assertEquals(JsonBool(true), cardRow["suspended"])
        server.durableSeq = null
        a.cycle()
        assertEquals(emptyList(), a.store.rows(), "purged once durable")
    }

    @Test
    fun restoredBelowThePullSeqTheDeviceLeavesTheLogResetAndConverges() {
        // SYNC.md 12.5.1 (C-10)
        val a = device(A, 1)
        mintWord(a)
        server.durableSeq = 2
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(emptyList(), a.store.rows(), "acked_seq <= 2: purged")
        repeat(8) { i -> // REST edits of the note, minted by the server
            server.nowMs += 1_000
            val h = (server.nowMs).toString(16).padStart(12, '0') + "-0000-" + Reserved.SERVER_DEVICE_ID
            server.append(
                Op(
                    "0193a1b2-00ff-7000-8000-0000000e00${(0x10 + i).toString(16)}", OpType.NOTE_UPDATED, 1, "note", note,
                    Reserved.SERVER_DEVICE_ID, h, JsonObject(mapOf("id" to JsonString(note), "set" to JsonObject(mapOf("mnemonic" to JsonString("edit $i"))))),
                ),
                null,
            )
        }
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals("10", a.state(SyncStateKeys.PULL_SEQ))

        server.restore(2, E2)
        a.clock.now = server.nowMs
        val review = mintReview(a, 0)
        server.nowMs = a.clock.now
        // push: accepted seq 3, epoch E2 -> log reset (pull_seq 0) -> re-push: duplicate seq 3 -> no new reset -> rebuild
        val engine = a.engine
        assertIs<CycleResult.LogChanged>(runSync { engine.pushAll() }).let { engine.resetLog(it) }
        assertEquals("0", a.state(SyncStateKeys.PULL_SEQ))
        assertIs<CycleResult.Completed>(runSync { engine.pushAll() }, "3 < 0 is false: no second reset")
        val row = a.store.rows().single()
        assertEquals(PendingState.ACKED, row.state)
        assertEquals(3L, row.ackedSeq)
        assertEquals("2", a.state(SyncStateKeys.DURABLE_SEQ), "kept while acked_seq 3 > durable_seq 2")
        assertIs<CycleResult.Completed>(a.cycle())
        assertConverged(a)
        assertEquals(listOf(review.opId), server.loggedOps().filter { it.opType == OpType.REVIEW_LOGGED }.map { it.opId }, "logged once")
        mintReview(a, 1) // reviewing works again
    }

    @Test
    fun aRebuildKilledMidwayResumesAndRollsBackTheDiscardedOp() {
        val a = device(A, 1)
        val b = device(B, 2)
        mintWord(a)
        repeat(4) { mintReview(a, it) }
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<CycleResult.Completed>(b.cycle())
        // A's flag op is refused by the server; A also has a kept op made offline
        val flagged = mint(a, OpType.CARD_FLAG_SET, "id" to JsonString(card), "flags" to JsonNumber(2.0))
        server.verdict = { op -> if (op.opId == flagged.opId) "invalid_value" to "scripted" else null }
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(PendingState.REJECTED, a.store.rows().single { it.opId == flagged.opId }.state)
        a.transport.offline = true
        val kept = mint(a, OpType.CARD_SUSPENDED, "id" to JsonString(card))
        assertEquals(JsonNumber(2.0), a.tables().get(LearningTable.CARD, listOf(JsonString(card)))!!["flags"], "local effect applied")
        a.transport.offline = false
        // Discard (T303d): delete the row and set rebuild_pending in one transaction
        a.store.transaction { tx ->
            tx.outbox.delete(flagged.opId)
            tx.bookkeeping.rebuildPending = true
        }
        assertIs<MintResult.Refused>(a.minter.mint(OpType.CARD_UNSUSPENDED, JsonObject(mapOf("id" to JsonString(card))))).let {
            assertEquals(MintRefusal.RebuildPending, it.reason)
        }
        server.pageSize = 2
        // killed during step 2 (after one page)
        val dying = Rebuild(a.store, SyncEngine(a.store, FailingPulls(a.transport, 1), a.applier, a.clock), a.applier)
        assertIs<CycleResult.Failed>(runSync { dying.run() })
        assertNotNull(a.state(SyncStateKeys.REBUILD_PENDING))
        // killed in the step-3 transaction
        val killedStore = KillWhenRebuildClears(a.store)
        val crashing = Rebuild(killedStore, SyncEngine(killedStore, a.transport, a.applier, a.clock), a.applier)
        assertFailsWith<KillWhenRebuildClears.Killed> { runSync { crashing.run() } }
        assertNotNull(a.state(SyncStateKeys.REBUILD_PENDING), "step 3 did not commit")
        // the next cycle starts again from step 0 and finishes
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<CycleResult.Completed>(b.cycle())
        assertConverged(a, b)
        val c = a.tables().get(LearningTable.CARD, listOf(JsonString(card)))!!
        assertEquals(JsonNumber(0.0), c["flags"], "the discarded op's effect is rolled back")
        assertEquals(JsonBool(true), c["suspended"], "the kept op's effect survives")
        assertTrue(kept.opId in logged())
        assertEquals(JsonNumber(4.0), c["reps"], "caches rebuilt by the running core")
        assertNotNull(a.state("core_scheduler_version"), "kept by the rebuild")
    }

    @Test
    fun unknownReferenceRowsAreAutoRetriedAfterALogResetWhenThePullAdvances() {
        val a = device(A, 1)
        val b = device(B, 2)
        mintWord(a)
        server.durableSeq = 0 // nothing verified: devices keep everything
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<CycleResult.Completed>(b.cycle())
        val review = mintReview(b, 0)
        assertIs<CycleResult.Completed>(b.cycle())
        server.restore(0, E2)
        // B notices first: its re-pushed review depends on A's lost card
        b.cycle()
        val row = b.store.rows().single { it.opId == review.opId }
        assertEquals(PendingState.REJECTED, row.state)
        assertTrue(row.lastError!!.startsWith("unknown_reference:"))
        // A re-pushes the note and the card
        assertIs<CycleResult.Completed>(a.cycle())
        // B's next pull advances: the review goes back to pending and gets through
        assertIs<CycleResult.Completed>(b.cycle())
        assertEquals(PendingState.PENDING, b.store.rows().single { it.opId == review.opId }.state)
        assertEquals(1, b.store.rows().single { it.opId == review.opId }.attempts)
        assertIs<CycleResult.Completed>(b.cycle())
        assertIs<CycleResult.Completed>(a.cycle())
        assertTrue(review.opId in logged())
        assertConverged(a, b)
    }

    @Test
    fun theAutoRetryStopsAfterTenAttempts() {
        val a = device(A, 1)
        val b = device(B, 2)
        mintWord(a)
        server.durableSeq = 0
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<CycleResult.Completed>(b.cycle())
        val review = mintReview(b, 0)
        assertIs<CycleResult.Completed>(b.cycle())
        server.restore(0, E2) // A never comes back
        b.cycle()
        repeat(15) { i ->
            server.nowMs += 1_000
            b.clock.now = server.nowMs
            val h = server.nowMs.toString(16).padStart(12, '0') + "-0000-" + Reserved.SERVER_DEVICE_ID
            server.append(
                Op(
                    "0193a1b2-00ff-7000-8000-0000000e01${(0x10 + i).toString(16)}", OpType.SETTINGS_SET, 1, "settings", "k$i",
                    Reserved.SERVER_DEVICE_ID, h, JsonObject(mapOf("key" to JsonString("k$i"), "value" to JsonNumber(i.toDouble()))),
                ),
                null,
            )
            b.cycle()
        }
        val row = b.store.rows().single { it.opId == review.opId }
        assertEquals(PendingState.REJECTED, row.state)
        assertEquals(DeviceSyncCycle.MAX_AUTO_RETRIES, row.attempts)
    }
}
