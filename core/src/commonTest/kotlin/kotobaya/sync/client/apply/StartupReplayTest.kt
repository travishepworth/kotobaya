package kotobaya.sync.client.apply

import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.replay.DefaultReplayEngine
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.StoreTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A store whose transactions die just before commit (the process is killed). */
class CrashBeforeCommit<L>(private val inner: DeviceStore<L>) : DeviceStore<L> {
    class Killed : RuntimeException("killed before commit")

    override fun <R> transaction(block: (StoreTransaction<L>) -> R): R = inner.transaction { tx ->
        block(tx)
        throw Killed()
    }
}

/** SYNC.md 10.5 `core_scheduler_version` on a device. */
class StartupReplayTest {
    private val card = "0193a1b2-0002-7000-8000-000000000001"
    private val cacheColumns = listOf("state", "stability", "difficulty", "due_at", "last_review_at", "last_review_id", "reps", "lapses", "weights_version")

    /** The world whose card caches were computed by "another core": garbage values. */
    private fun staleWorld(): InMemoryLearningTables {
        val t = Examples.worldTables()
        val row = LinkedHashMap(t.get(LearningTable.CARD, listOf(JsonString(card)))!!)
        row["reps"] = JsonNumber(99.0)
        row["stability"] = JsonNumber(1234.5)
        t.put(LearningTable.CARD, row)
        return t
    }

    private fun cache(t: LearningTables): Map<String, JsonValue?> = t.get(LearningTable.CARD, listOf(JsonString(card)))!!.let { r -> cacheColumns.associateWith { r[it] } }

    @Test
    fun runsOncePerCoreVersionAndMintsNoOp() {
        val store = InMemoryDeviceStore(staleWorld()) { it.copy() }
        val startup = StartupReplay()
        assertTrue(startup.run(store))
        assertEquals(DefaultReplayEngine.schedulerVersion, store.syncState()[StartupReplay.CORE_SCHEDULER_VERSION])
        assertEquals(cache(Examples.worldTables()), cache(store.learning()), "every cache fully replayed")
        val after = dump(store.learning())
        assertFalse(startup.run(store), "same version: nothing to do")
        assertEquals(after, dump(store.learning()))
        assertEquals(emptyList(), store.rows(), "no op minted")
        assertEquals(setOf(StartupReplay.CORE_SCHEDULER_VERSION), store.syncState().keys)
        // a different stored version (upgrade or downgrade) replays again
        store.transaction { it.syncState.put(StartupReplay.CORE_SCHEDULER_VERSION, "some-other-core") }
        assertTrue(startup.run(store))
        assertEquals(after, dump(store.learning()))
    }

    @Test
    fun killedBeforeCommitLeavesNothingAndTheReRunSucceeds() {
        val store = InMemoryDeviceStore(staleWorld()) { it.copy() }
        val before = dump(store.learning())
        assertFailsWith<CrashBeforeCommit.Killed> { StartupReplay().run(CrashBeforeCommit(store)) }
        assertEquals(null, store.syncState()[StartupReplay.CORE_SCHEDULER_VERSION], "the key is unchanged")
        assertEquals(before, dump(store.learning()), "no cache was half-replayed")
        assertTrue(StartupReplay().run(store))
        assertEquals(cache(Examples.worldTables()), cache(store.learning()))
        assertEquals(DefaultReplayEngine.schedulerVersion, store.syncState()[StartupReplay.CORE_SCHEDULER_VERSION])
    }

    @Test
    fun theStartupReplayPrecedesAnyFold() {
        // a review that sorts after the last folded one would be folded into the stale cache ...
        val base = Examples.op("review.logged.json")
        val next = base.copy(
            opId = "0193a1b2-00ff-7000-8000-0000000a0001",
            entityId = "0193a1b2-0003-7000-8000-0000000000a1",
            hlc = later(base.hlc, 86_400_000),
            payload = JsonObject(
                base.payload.members + mapOf(
                    "id" to JsonString("0193a1b2-0003-7000-8000-0000000000a1"),
                    "reviewed_at" to JsonNumber(1_733_671_066_641.0),
                    "review_type" to JsonString("scheduled"),
                ),
            ),
        )
        val store = InMemoryDeviceStore(staleWorld()) { it.copy() }
        store.transaction { it.syncState.put(StartupReplay.CORE_SCHEDULER_VERSION, "old-core") }
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        store.transaction { tx -> applier.apply(tx, next, ApplyOrigin.PULLED) }
        // ... but the apply first runs the startup replay, so the result equals a clean replica's
        val clean = Examples.worldTables().also { DeviceOpApplier<InMemoryLearningTables>().applyTo(it, next) }
        assertEquals(cache(clean), cache(store.learning()))
        assertEquals(DefaultReplayEngine.schedulerVersion, store.syncState()[StartupReplay.CORE_SCHEDULER_VERSION])
    }
}
