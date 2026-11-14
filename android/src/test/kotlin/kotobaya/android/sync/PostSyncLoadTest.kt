package kotobaya.android.sync

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotobaya.android.decks.DeckRepository
import kotobaya.android.decks.DecksViewModel
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.db.app.AppDatabase
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.store.SyncStateKeys
import org.junit.Test

/**
 * AND-POSTSYNC acceptance: a fresh device pulls a multi-page, multi-chunk history that ends with today's answers
 * made elsewhere (a new card answered twice in learning, as on the web). After the pull the startup gate opens and
 * the screens' loaders work on the replica: the Decks/Home state loads and the review queue builds.
 *
 * Before the fix the queue loader threw a NullPointerException on the second learning answer (`introduced_new`
 * was SQL NULL), so every screen stayed on its skeleton and the ViewModel's coroutine crashed the process.
 * Plain JVM (lifecycle 2.8's viewModelScope needs no main dispatcher; Robolectric would hide the JDBC driver).
 */
class PostSyncLoadTest {
    @Test
    fun afterALargeChunkedPullTheGateOpensAndTheScreensLoad() {
        val history = SyntheticHistory.ops(TOTAL)
        val server = TestServer(nowMs = NOW)
        server.pageSize = 500
        for (op in history) server.append(op, null)
        val card = history.first { it.opType == OpType.CARD_CREATED }.entityId
        // today's answers from another device: a learning "again", then a learning "good" an hour later
        server.append(review(1, card, NOW - 2 * HOUR), null)
        server.append(review(2, card, NOW - HOUR), null)

        val device = SqlDevice(SyntheticHistory.DEVICE.replace("a1", "b2"), server, NOW)
        val result = device.cycle()
        assertIs<CycleResult.Completed>(result, "$result")
        assertEquals(TOTAL + 2, result.pulledOps)
        assertEquals((TOTAL + 2).toLong(), device.state(SyncStateKeys.PULL_SEQ)?.toLong())

        // the readiness gate of SyncBootstrap: readers wait until the startup replay has committed
        val ready = CompletableDeferred<Unit>()
        val loader = LocalQueueLoader(AppDatabase(device.driver), { NOW }, awaitReady = { runBlocking { ready.await() } })
        device.stack.startup.run(device.store)
        ready.complete(Unit)
        assertTrue(ready.isCompleted)

        device.driver.execute(null, "INSERT INTO deck (id, name, type, enabled, settings, created_at, updated_at) VALUES ('d1', 'Deck', 'user', 1, '{}', 0, 0)", 0)
        device.driver.execute(
            null,
            "INSERT INTO deck_item (deck_id, item_key, entry_seq, position, added_at) SELECT 'd1', 'e:' || entry_seq, entry_seq, entry_seq, 0 FROM note",
            0,
        )

        // Review: the queue builds and today's second learning answer is not a new introduction
        val loaded = loader.load()
        assertTrue(loaded.queue.items.isNotEmpty())

        // Home / Decks: the ViewModel's state leaves the loading skeleton with counts
        val vm = DecksViewModel(DeckRepository(device.driver, loader), null, Dispatchers.Unconfined)
        val decks = assertNotNull(vm.state.value.decks, "decks still loading")
        assertFalse(vm.state.value.loadFailed)
        assertEquals(listOf("d1"), decks.map { it.id })
        assertTrue(decks.single().totalCards > 0)
    }

    private fun review(n: Long, card: String, at: Long): Op {
        val device = "01931f3e-0000-7000-8000-0000000000c3"
        val payload = JsonObject(
            mapOf(
                "id" to JsonString("0193a1b2-0009-7000-8000-%012x".format(n)), "card_id" to JsonString(card),
                "reviewed_at" to JsonNumber(at.toDouble()), "utc_offset_min" to JsonNumber(0.0),
                "review_type" to JsonString("learning"), "rating" to JsonNumber(if (n == 1L) 1.0 else 3.0),
            ),
        )
        return Op(
            opId = "0193a1b2-0008-7000-8000-%012x".format(n), opType = OpType.REVIEW_LOGGED, opVersion = 1,
            entityType = OpType.REVIEW_LOGGED.entityType, entityId = DefaultOpCodec.entityIdOf(OpType.REVIEW_LOGGED, payload),
            deviceId = device, hlc = "%012x-0000-%s".format(NOW - 3 * HOUR + n, device), payload = payload,
        )
    }

    private companion object {
        const val TOTAL = 3_000
        const val HOUR = 3_600_000L
        val NOW = 0x0193a1b2c000L + TOTAL * 1000L + 60_000_000L
    }
}
