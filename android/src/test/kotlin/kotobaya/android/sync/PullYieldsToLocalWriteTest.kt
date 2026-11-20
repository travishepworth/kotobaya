package kotobaya.android.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.store.bookkeeping
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.PullProgress
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys

/**
 * LF-A1 (RC-3; SYNC.md 6.1 "Device scheduling", SHELL.md L-9): a pull applies a page in sub-pages of at most 50 ops and
 * a local write issued while a sub-page holds the write connection goes before the next sub-page; a local writer
 * waits at most the store's bound for the write connection and then fails having changed nothing.
 */
class PullYieldsToLocalWriteTest {
    private val deviceId = SyntheticHistory.DEVICE.replace("a1", "c3")

    /** Calls [onWrite] before every write statement (the learning tables and the outbox go through `execute`). */
    private class HookedDriver(private val delegate: SqlDriver, private val onWrite: () -> Unit) : SqlDriver by delegate {
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            onWrite()
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    private fun server(ops: Int): TestServer {
        val history = SyntheticHistory.ops(ops)
        return TestServer(nowMs = 0x0193a1b2c000L + ops * 1000L + 60_000).also { s ->
            s.pageSize = 500
            for (op in history) s.append(op, null)
        }
    }

    private fun localWordStatus(client: SyncClient, seq: Long): MintResult =
        client.minter.mint(OpType.WORD_STATUS_SET, JsonObject(linkedMapOf("entry_seq" to JsonNumber(seq.toDouble()), "status" to JsonString("known"))))

    @Test
    fun theSyncClientAppliesAFiveHundredOpPageInSubPagesOfAtMostFifty() {
        val server = server(500)
        val store = SqlDelightDeviceStore(DbFile().open())
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, deviceId) }
        val progress = ArrayList<PullProgress>()
        val client = SyncClient(store, TestTransport(server), TestClock(server.nowMs), onPullProgress = { progress += it })

        val r = assertIs<CycleResult.Completed>(kotlinx.coroutines.runBlocking { client.engine.pullAll() })
        assertEquals(500, r.pulledOps)
        assertEquals(SyncClient.PULL_SUB_PAGE_OPS, 50)
        assertEquals(10, progress.size, "500 ops = 10 sub-pages")
        progress.zipWithNext().forEach { (a, b) -> assertTrue(b.pulledOps - a.pulledOps <= 50) }
    }

    @Test
    fun aLocalAnswerIssuedDuringAFiveHundredOpPageCommitsBeforeThePageFinishes() {
        val server = server(500)
        val puller = AtomicReference<Thread>()
        var subPagesDone = 0
        var started = false
        val local = AtomicReference<Thread>()
        lateinit var store: SqlDelightDeviceStore
        lateinit var client: SyncClient
        val localResult = AtomicReference<MintResult>()
        val pullSeqAtLocalWrite = AtomicReference<Long>()
        // the answer arrives while sub-page 2 is being applied (the puller holds the write connection)
        val driver = HookedDriver(DbFile().open()) {
            if (Thread.currentThread() === puller.get() && subPagesDone == 1 && !started) {
                started = true
                val t = Thread {
                    store.transaction { tx ->
                        pullSeqAtLocalWrite.set(tx.bookkeeping.pullSeq)
                        localResult.set(localWordStatus(client, 2_000_001L))
                    }
                }
                local.set(t)
                t.start()
                val deadline = System.currentTimeMillis() + 5_000
                while (store.waitingWriters() == 0 && t.isAlive) {
                    check(System.currentTimeMillis() < deadline) { "the local writer never queued" }
                    Thread.sleep(1)
                }
            }
        }
        store = SqlDelightDeviceStore(driver)
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, deviceId) }
        client = SyncClient(store, TestTransport(server), TestClock(server.nowMs), onPullProgress = { subPagesDone++ })

        puller.set(Thread.currentThread())
        val r = kotlinx.coroutines.runBlocking { client.engine.pullAll() }
        puller.set(null)
        local.get().join(5_000)

        assertIs<CycleResult.Completed>(r)
        assertEquals(500, r.pulledOps)
        assertIs<MintResult.Minted>(localResult.get())
        assertEquals(100L, pullSeqAtLocalWrite.get(), "the answer went right after sub-page 2, before sub-page 3 of 10")
        // its HLC is above every op of sub-pages 1-2 (observed before it was minted)
        val minted = store.read { tx -> tx.outbox.inState(PendingState.PENDING) }.single()
        val sub2Last = server.loggedOps()[99].hlc
        assertTrue(minted.hlc > sub2Last, "${minted.hlc} > $sub2Last")
    }

    @Test
    fun aLocalWriterWaitsAtMostTheBoundThenFailsHavingChangedNothing() {
        val store = SqlDelightDeviceStore(DbFile().open(), writeWaitMs = 300)
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, deviceId) }
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            store.transaction {
                holding.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue(holding.await(5, TimeUnit.SECONDS))

        val start = System.nanoTime()
        assertFailsWith<StoreBusyException> { store.transaction { it.syncState.put("client.test_busy", "1") } }
        val waited = (System.nanoTime() - start) / 1_000_000
        assertTrue(waited >= 300, "waited $waited ms")
        release.countDown()
        holder.join(5_000)
        assertEquals(null, store.read { it.syncState.get("client.test_busy") }, "nothing written")
    }

    @Test
    fun aLocalWriterWaitingLessThanTheBoundGoesThrough() {
        val store = SqlDelightDeviceStore(DbFile().open(), writeWaitMs = 5_000)
        val holding = CountDownLatch(1)
        val holder = Thread {
            store.transaction {
                holding.countDown()
                Thread.sleep(200)
            }
        }.apply { start() }
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        store.transaction { it.syncState.put("client.test_busy", "1") }
        holder.join(5_000)
        assertNotNull(store.read { it.syncState.get("client.test_busy") })
    }
}
