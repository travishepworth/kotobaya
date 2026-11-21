package kotobaya.android.shell.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotobaya.android.sync.StoreBusyException
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source

/**
 * LF-A1b (SHELL.md L-9, L-3; ADR-050 "a grade tap never errors"): a local write that finds the writer lock busy
 * ([StoreBusyException], the 5 s bound) is retried once; still busy, it is `503 shell_rebuilding` + `Retry-After: 2`
 * (the SPA retries it), never `500`, and nothing is written.
 */
class StoreBusyTest {
    private val device = "01931f3e-0000-7000-8000-0000000000d4"
    private val clock = Clock { 1_733_584_666_000L }
    private val inner = InMemoryDeviceStore(mutableListOf<String>()) { it.toMutableList() }

    /** [inner], but the next [busyTimes] outermost transactions throw [StoreBusyException] before running. */
    private class BusyStore<L>(private val inner: DeviceStore<L>, var busyTimes: Int) : DeviceStore<L> {
        var attempts = 0
        private var depth = 0

        override fun <R> transaction(block: (StoreTransaction<L>) -> R): R {
            if (depth == 0) {
                attempts++
                if (busyTimes > 0) {
                    busyTimes--
                    throw StoreBusyException(5_000)
                }
            }
            depth++
            try {
                return inner.transaction(block)
            } finally {
                depth--
            }
        }
    }

    private val store = BusyStore(inner, 0)
    private val applier = OpApplier<MutableList<String>> { tx, op, _ -> tx.learning.add(op.opId) }
    private val minter = OpMinter(store, applier, clock, UuidV7Source(kotlin.random.Random(7)))
    private var triggers = 0
    private val writes = LocalWrites(store, minter) { triggers++ }

    init {
        inner.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, device) }
    }

    private fun setting() = MintRequest(
        OpType.SETTINGS_SET,
        JsonObject(mapOf("key" to JsonString("scheduler.new_per_day"), "value" to JsonNumber(15.0))),
    )

    private fun grade(): String = writes.write { WritePlan.Mint(listOf(setting())) { _, ops -> ops.single().opId } }

    @Test
    fun aWriteThatFindsTheWriterBusyOnceIsRetriedAndCommits() {
        store.busyTimes = 1
        val opId = grade()
        assertEquals(2, store.attempts, "one busy attempt, one retry")
        assertEquals(listOf(opId), inner.rows().map { it.opId })
        assertEquals(1, triggers)
    }

    @Test
    fun aWriteStillBusyAfterItsRetryIs503ShellRebuildingWithRetryAfterAndChangesNothing() {
        store.busyTimes = Int.MAX_VALUE
        val e = assertFailsWith<ProblemException> { grade() }
        assertEquals(2, store.attempts, "retried exactly once")
        assertEquals(503, e.problem.status)
        assertEquals("shell_rebuilding", e.problem.code)
        assertEquals("2", e.problem.headers[ShellHeaders.RETRY_AFTER])
        assertEquals(emptyList(), inner.rows())
        assertEquals(emptyList(), inner.learning())
        assertEquals(0, triggers)
    }

    @Test
    fun storeBusyFromAnyHandlerIsAnswered503WithRetryAfterNever500() {
        val routes = listOf(
            LocalRoute("updateSettings", "PATCH", "/api/v1/settings") { throw StoreBusyException(5_000) },
            LocalRoute("getNote", "GET", "/api/v1/notes/{note_id}") { throw IllegalStateException("wrapped", StoreBusyException(5_000)) },
        )
        val api = LocalApi(routes, AlwaysOpenGate(), newRequestId = { "rid" })
        val patch = runBlocking {
            api.handle(ShellRequest.fromRaw("PATCH", "/api/v1/settings", "", mapOf("content-type" to "application/json"), "{}"))
        }
        val get = runBlocking { api.handle(ShellRequest.fromRaw("GET", "/api/v1/notes/n1", "", emptyMap(), null)) }
        for (r in listOf(patch, get)) {
            assertEquals(503, r.status, r.body)
            assertEquals("2", r.headers[ShellHeaders.RETRY_AFTER])
            assertEquals(true, r.body?.contains("\"shell_rebuilding\""), r.body)
        }
    }
}
