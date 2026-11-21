package kotobaya.android.shell.api

import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotobaya.android.shell.cache.CacheHooks
import kotobaya.android.shell.cache.CacheUpdate
import kotobaya.android.shell.events.ShellEvents
import kotobaya.android.shell.web.BridgeMessage
import kotobaya.android.sync.SyncOutcome
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.FailureKind
import kotobaya.sync.client.protocol.SyncStep

/** LF-A2: `cache.updated` (SHELL.md 2.6) and the response cache's hooks on the native event producers (K-4). */
class CacheEventsTest {
    private val queued = ArrayDeque<Runnable>()
    private val sent = ArrayList<BridgeMessage.Event>()
    private val events = ShellEvents(
        scope = CoroutineScope(Executor { queued.addLast(it) }.asCoroutineDispatcher()),
        send = { sent += it },
        syncChanged = { 0L to 0 },
        syncStatus = { JsonObject(emptyMap()) },
    )

    private fun drain() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private val hookLog = ArrayList<String>()
    private val hooks = object : CacheHooks {
        override fun onReplicaChanged(tables: Set<String>?) {
            hookLog += "changed"
        }

        override fun onAllStale() {
            hookLog += "all-stale"
        }

        override fun onCycleSucceeded() {
            hookLog += "warm"
        }
    }

    @Test
    fun cacheUpdatedCarriesThePayloadAndCoalescesPerKeyKeepingTheLast() {
        events.onCacheUpdated(CacheUpdate("getDeckCoverage", "/api/v1/decks/d1/coverage", "", 200))
        events.onCacheUpdated(CacheUpdate("getDeckCoverage", "/api/v1/decks/d1/coverage", "", 404))
        events.onCacheUpdated(CacheUpdate("getDeckCoverage", "/api/v1/decks/d2/coverage", "", 200))
        drain()
        val updates = sent.filter { it.type == "cache.updated" }
        assertEquals(2, updates.size)
        assertEquals(
            JsonObject(
                linkedMapOf(
                    "operation_id" to JsonString("getDeckCoverage"),
                    "path" to JsonString("/api/v1/decks/d1/coverage"),
                    "query" to JsonString(""),
                    "status" to JsonNumber(404.0),
                ),
            ),
            updates[0].data,
        )
        assertEquals(JsonString("/api/v1/decks/d2/coverage"), updates[1].data.members["path"])
    }

    @Test
    fun syncOutcomesAndLocalWritesReachTheCacheHooks() {
        events.cacheHooks = hooks
        events.onSyncOutcome(SyncOutcome.Ran(CycleResult.Completed(pushedOps = 1, pulledOps = 0, purgedRows = 0)))
        assertEquals(listOf("warm"), hookLog)
        hookLog.clear()
        events.onSyncOutcome(SyncOutcome.Ran(CycleResult.Completed(pushedOps = 0, pulledOps = 3, purgedRows = 0, pullAdvanced = true)))
        assertEquals(listOf("changed", "warm"), hookLog)
        hookLog.clear()
        events.onSyncOutcome(SyncOutcome.Ran(CycleResult.RebuildPending(pushedOps = 0)))
        events.onSyncOutcome(SyncOutcome.Ran(CycleResult.Failed(FailureKind.OFFLINE, SyncStep.PULL, "x")))
        events.onSyncOutcome(SyncOutcome.Offline)
        assertEquals(listOf("all-stale"), hookLog)
        hookLog.clear()
        events.onLocalWrite()
        assertEquals(listOf("changed"), hookLog)
    }
}
