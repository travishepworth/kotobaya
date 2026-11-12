package kotobaya.sync.restore

import kotlinx.coroutines.runBlocking
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.OpType
import kotobaya.replay.DefaultReplayEngine
import kotobaya.server.SchedulerReplay
import kotobaya.server.StartupCheck
import kotobaya.server.StartupSequence
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncModule
import kotobaya.sync.fuzz.RefReplica
import kotobaya.sync.fuzz.ServerProjection
import kotobaya.sync.fuzz.SimDevice
import kotobaya.sync.merge.CardCacheTest
import kotobaya.sync.merge.CoreCardCache
import kotobaya.sync.merge.DuplicateMerge
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * SYNC.md 10.5 on the server: the startup replay with the core's real scheduler version (FOLLOWUPS
 * "T104 → T203": no more `PLACEHOLDER_SCHEDULER_VERSION`), run in the startup transaction, once per
 * version, behind readiness; and a card the engine refuses does not block startup (T203 review).
 */
class StartupReplayTest {
    private val start = 0x0193a1b2c000L

    /** Cards with reviews whose caches were never computed (the server ran without a cache engine). */
    private fun staleCaches(s: StoryServer): Pair<SimDevice, List<String>> {
        s.sim.module = SyncModule(s.db, SyncConfig(clock = { s.now.get() }, merge = DuplicateMerge()))
        val a = SimDevice(A).also { it.pt = start }
        val cards = (0 until 3).map {
            val note = a.newId(1)
            val card = a.newId(2)
            a.make(OpType.NOTE_CREATED, mapOf("id" to note, "note_type_id" to Reserved.NOTE_TYPE_VOCAB))
            a.make(OpType.CARD_CREATED, mapOf("id" to card, "note_id" to note, "kind" to "meaning"))
            listOf(start - 3 * Reserved.DAY_MS, start - Reserved.DAY_MS).forEach { t ->
                a.make(
                    OpType.REVIEW_LOGGED,
                    mapOf("id" to a.newId(3), "card_id" to card, "reviewed_at" to t, "utc_offset_min" to 540, "review_type" to "scheduled", "rating" to 3),
                )
            }
            card
        }
        a.sync(s.sim)
        return a to cards
    }

    private fun cacheOf(s: StoryServer, card: String) = (ServerProjection.of(s.db).getValue("card")[card] as Map<*, *>)["cache"] as Map<*, *>

    @Test
    fun startupReplaysEveryCacheOnceWithTheCoreSchedulerVersion() = StoryServer(start).use { s ->
        val (a, cards) = staleCaches(s)
        assertEquals("new", cacheOf(s, cards[0])["state"])
        assertEquals("unset", s.serverState("core_scheduler_version"), "the placeholder of earlier builds")

        val calls = AtomicInteger()
        val real = CoreSchedulerReplay()
        val counting = SchedulerReplay { tx -> calls.incrementAndGet(); real.replayAll(tx) }
        StartupSequence(s.db, schedulerVersion = real.schedulerVersion, replay = counting).runOnce()
        assertEquals(DefaultReplayEngine.SCHEDULER_VERSION, s.serverState("core_scheduler_version"))
        assertEquals(1, calls.get())
        assertEquals(emptyList(), ServerProjection.diff(ServerProjection.of(s.db), a.r.projection()), "every cache equals a full replay")

        // Same version on the next start (or a second instance): no replay.
        StartupSequence(s.db, schedulerVersion = real.schedulerVersion, replay = counting).runOnce()
        assertEquals(1, calls.get())
        assertEquals(real.schedulerVersion, SyncWiring.startup(s.db).let { it.runOnce(); s.serverState("core_scheduler_version") })
    }

    @Test
    fun aCardTheEngineRefusesIsSkippedAndStartupStillCommits() = StoryServer(start).use { s ->
        val (a, cards) = staleCaches(s)
        val replay = CoreSchedulerReplay(CoreCardCache(CardCacheTest.RefusingEngine(cards[1])))
        StartupSequence(s.db, schedulerVersion = replay.schedulerVersion, replay = replay).runOnce()
        assertEquals(replay.schedulerVersion, s.serverState("core_scheduler_version"), "the version row is written")
        assertEquals("new", cacheOf(s, cards[1])["state"], "the refused card keeps its cache")
        val expected = a.r.projection().getValue("card")
        assertEquals((expected[cards[0]] as Map<*, *>)["cache"], cacheOf(s, cards[0]))
        assertEquals((expected[cards[2]] as Map<*, *>)["cache"], cacheOf(s, cards[2]))
    }

    /** C-1 → T104/T111: the instance is not ready until the startup replay has committed. */
    @Test
    fun readinessWaitsForTheStartupReplay() = StoryServer(start).use { s ->
        staleCaches(s)
        val gate = java.util.concurrent.CountDownLatch(1)
        val real = CoreSchedulerReplay()
        val slow = SchedulerReplay { tx -> gate.await(); real.replayAll(tx) }
        val seq = StartupSequence(s.db, schedulerVersion = real.schedulerVersion, replay = slow)
        val check = StartupCheck(seq.state)
        val job = Thread { runBlocking { seq.runUntilDone() } }.also { it.start() }
        assertNotNull(runBlocking { check.check() }, "not ready while the replay runs")
        gate.countDown()
        job.join(30_000)
        assertNull(runBlocking { check.check() }, "ready once it committed")
        assertEquals(RefReplica.ENGINE.schedulerVersion, s.serverState("core_scheduler_version"))
    }

    companion object {
        const val A = "01931f3e-0000-7000-8000-0000000000a1"
    }
}
