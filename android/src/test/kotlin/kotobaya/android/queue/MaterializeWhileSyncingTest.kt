package kotobaya.android.queue

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.AnswerTestSupport.answer
import kotobaya.android.shell.api.review.AnswerTestSupport.remoteReview
import kotobaya.android.shell.api.review.QueueTestSupport.at
import kotobaya.android.shell.api.review.QueueTestSupport.cardsOf
import kotobaya.android.shell.api.review.QueueTestSupport.device
import kotobaya.android.shell.api.review.QueueTestSupport.items
import kotobaya.android.shell.api.review.QueueTestSupport.json
import kotobaya.android.shell.api.review.QueueTestSupport.liveNotesOf
import kotobaya.android.shell.api.review.QueueTestSupport.long
import kotobaya.android.shell.api.review.QueueTestSupport.notes
import kotobaya.android.shell.api.review.QueueTestSupport.obj
import kotobaya.android.shell.api.review.QueueTestSupport.op
import kotobaya.android.shell.api.review.QueueTestSupport.queue
import kotobaya.android.shell.api.review.QueueTestSupport.str
import kotobaya.android.sync.SyncCoordinator
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.store.bookkeeping
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A1 (RC-1 of the local-first design; SYNC.md 8.1 "When a device materializes", SHELL.md 4.3 / L-9): a paired
 * device materializes deck entries iff its first complete pull is done and no rebuild is pending, whatever the sync
 * coordinator is doing. Seed `queue-materialize-count`: a user deck with entries 1001010, 1001150, 1001580, no notes,
 * `new_per_day` 2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class MaterializeWhileSyncingTest {
    // AND-FLAKY-DUPCHECK: the parity device opens an in-memory JDBC database; register this loader's driver first
    @BeforeTest
    fun jdbc() = kotobaya.android.sync.ensureSqliteJdbc()

    /** The coordinator's own flag, as a cycle stuck on a bad link leaves it (the parity coordinator never runs one). */
    private fun SyncCoordinator.forceRunning(value: Boolean) {
        val f = SyncCoordinator::class.java.getDeclaredField("runningState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(this) as MutableStateFlow<Boolean>).value = value
    }

    @Test
    fun aRunningCycleAfterThreeFailuresDoesNotStopMaterialization() {
        val d = device("queue-materialize-count")
        d.coordinator.forceRunning(true)
        d.failures.failures = 3
        assertTrue(d.coordinator.running.value)
        assertEquals(3, d.coordinator.consecutiveFailures)

        val body = d.queue().json()
        assertEquals(2, body.items().size, "the drawn deck entries are materialized and served")
        assertEquals(0L, body.long("/to_materialize"))
        assertEquals(2L, body.long("/counts/new"))
        assertTrue(d.minted().isNotEmpty())
    }

    @Test
    fun beforeTheFirstCompletePullNothingIsMaterializedAndTheEntriesAreCounted() {
        val d = device("queue-materialize-count")
        d.store.transaction { it.bookkeeping.lastSyncAt = null }

        val body = d.queue().json()
        assertEquals(0, body.items().size)
        assertEquals(2L, body.long("/counts/new"))
        assertEquals(2L, body.long("/to_materialize"))
        assertEquals(0, d.minted().size, "nothing minted before the first complete pull")

        // the first cycle completes: the next fetch materializes
        d.store.transaction { it.bookkeeping.lastSyncAt = d.clock.now }
        val after = d.queue(at = d.clock.now + 1_000).json()
        assertEquals(2, after.items().size)
        assertEquals(0L, after.long("/to_materialize"))
    }

    @Test
    fun materializeAllowedDependsOnlyOnTheFirstPullAndTheRebuild() {
        assertTrue(materializeAllowed(paired = false, lastSyncAt = null, rebuildPending = true), "unpaired: always")
        assertFalse(materializeAllowed(paired = true, lastSyncAt = null, rebuildPending = false), "no complete pull yet")
        assertFalse(materializeAllowed(paired = true, lastSyncAt = 1L, rebuildPending = true), "rebuild pending")
        assertTrue(materializeAllowed(paired = true, lastSyncAt = 1L, rebuildPending = false))
    }

    /**
     * SYNC.md 8.1 "server first": the web materialized entry 1001010 (note N0, card C0) and reviewed C0; the phone,
     * offline, materialized the same entry (N1) and reviewed its card. After the phone's push the server's log holds
     * both histories and the merge it emitted; the phone pulls it (its own ops come back, 6.1). One live note, both
     * reviews kept, both in the survivor's replay set.
     */
    @Test
    fun deviceAndServerMaterializeTheSameEntryConvergeToOneNoteKeepingEveryReview() {
        val entry = 1001010L
        val serverNote = "0193b000-00aa-7000-8000-000000000001"
        val serverCard = "0193b000-00aa-7000-8000-000000000002"
        val vocabType = "00000000-0000-7000-8000-000000000101"
        val serverReview = "0193b000-0003-7000-8000-0000000000a1"
        val deviceReview = "0193b000-0003-7000-8000-0000000000d1"

        val d = device("queue-materialize-count")
        d.coordinator.forceRunning(true) // a cycle in flight on a bad link changes nothing
        val first = d.queue().json()
        val deviceNote = str(d.liveNotesOf(entry).single()["id"])
        val deviceMeaning = first.items().map { str(it.at("/card/id")) }.single { id -> d.cardsOf(deviceNote).any { str(it["id"]) == id } }
        assertEquals(200, d.answer(deviceReview, deviceMeaning, rating = 3, at = d.clock.now + 5_000).status)
        val deviceOps = d.minted()
        val deviceCards = d.cardsOf(deviceNote)

        // the server's log: web materialization and review first, then the phone's push, then the merge it caused
        val t0 = d.clock.now - 3_600_000
        var wall = d.clock.now + 60_000
        var n = 0
        fun opId() = "0193b000-00ff-7000-8000-%012x".format(++n)
        val ops = ArrayList<JsonObject>()
        ops += op(
            opId(), "note.created", "note", serverNote, t0,
            obj(
                "id" to JsonString(serverNote), "note_type_id" to JsonString(vocabType), "entry_seq" to JsonNumber(entry.toDouble()),
                "fields" to JsonObject(emptyMap()), "origin" to JsonString("deck"),
            ),
        )
        ops += op(opId(), "card.created", "card", serverCard, t0 + 1, obj("id" to JsonString(serverCard), "note_id" to JsonString(serverNote), "kind" to JsonString("meaning")))
        ops += remoteReview(serverReview, serverCard, reviewedAt = t0 + 10_000, hlcWall = t0 + 10_000)
        ops += deviceOps
        ops += op(opId(), "note.merged", "note", deviceNote, wall++, obj("loser_id" to JsonString(deviceNote), "survivor_id" to JsonString(serverNote)))
        var extra = 0
        for (c in deviceCards.sortedWith(compareBy({ str(it["kind"]) }, { str(it["id"]) }))) {
            val kind = str(c["kind"])
            val survivor = if (kind == "meaning") {
                serverCard
            } else {
                "0193b000-00aa-7000-8000-%012x".format(100 + ++extra).also { id ->
                    ops += op(opId(), "card.created", "card", id, wall++, obj("id" to JsonString(id), "note_id" to JsonString(serverNote), "kind" to JsonString(kind)))
                }
            }
            ops += op(opId(), "card.merged", "card", str(c["id"]), wall++, obj("loser_id" to c["id"]!!, "survivor_id" to JsonString(survivor)))
        }
        d.clock.now = wall
        d.pull(ops, "server log after the phone's push")

        assertEquals(listOf(serverNote), d.liveNotesOf(entry).map { str(it["id"]) }, "one live note for the entry")
        assertEquals(JsonString(serverNote), d.notes().single { str(it["id"]) == deviceNote }["merged_into_id"])
        val reviews = d.store.read { it.learning.all(LearningTable.REVIEW_LOG) }.map { str(it["id"]) }.toSet()
        assertEquals(setOf(serverReview, deviceReview), reviews, "every review kept")
        val survivor = d.store.read { it.learning.get(LearningTable.CARD, listOf(JsonString(serverCard))) }!!
        assertEquals(2L, (survivor["reps"] as JsonNumber).value.toLong(), "both reviews replayed on the survivor")

        val mintedBefore = d.minted().size
        d.queue(at = wall + 1_000).json()
        val again = d.minted().drop(mintedBefore).filter { str(it.members["op_type"]) == "note.created" }
            .map { ((it.members["payload"] as JsonObject).members["entry_seq"] as? JsonNumber)?.value?.toLong() }
        assertTrue(entry !in again, "the merged entry is not materialized again: $again")
        assertEquals(listOf(serverNote), d.liveNotesOf(entry).map { str(it["id"]) })
    }
}
