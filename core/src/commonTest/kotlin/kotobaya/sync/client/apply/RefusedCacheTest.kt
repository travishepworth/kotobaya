package kotobaya.sync.client.apply

import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayDecision
import kotobaya.contracts.replay.ReplayEngine
import kotobaya.contracts.replay.ReplayReview
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.fsrs.FsrsScheduler
import kotobaya.replay.DefaultReplayEngine
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.TestServer
import kotobaya.sync.client.store.SyncStateKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A replay engine that refuses (as `DefaultReplayEngine` does for invalid input) whenever [refuse] says so. */
class RefusingEngine(private val refuse: (List<ReplayReview>, SchedulerParams) -> Boolean) : ReplayEngine by DefaultReplayEngine {
    override fun replay(reviews: List<ReplayReview>, params: SchedulerParams): CardCache {
        if (refuse(reviews, params)) throw IllegalArgumentException("injected refusal")
        return DefaultReplayEngine.replay(reviews, params)
    }

    override fun fold(cache: CardCache, review: ReplayReview, params: SchedulerParams): CardCache {
        if (refuse(listOf(review), params)) throw IllegalArgumentException("injected refusal")
        return DefaultReplayEngine.fold(cache, review, params)
    }
}

/**
 * SYNC.md 9.4 on a device (C-8 follow-up): a refused replay writes the refused cache, never fails
 * the page, is reported by [DeviceDiagnostics], is never folded into, and heals when its input
 * changes (an undo of the offending review, a preset change).
 */
class RefusedCacheTest {
    private val card = "0193a1b2-0002-7000-8000-000000000001"
    private val badId = "0193a1b2-0003-7000-8000-0000000000bd"

    private fun cardRow(t: LearningTables): Row = t.get(LearningTable.CARD, listOf(JsonString(card)))!!

    private fun review(id: String, opId: String, reviewedAt: Long, hlcMs: Long, rating: Int = 3, type: String = "scheduled"): Op {
        val base = Examples.op("review.logged.json")
        return base.copy(
            opId = opId,
            entityId = id,
            hlc = later(base.hlc, hlcMs),
            payload = JsonObject(
                base.payload.members + mapOf(
                    "id" to JsonString(id), "reviewed_at" to JsonNumber(reviewedAt.toDouble()),
                    "rating" to JsonNumber(rating.toDouble()), "review_type" to JsonString(type),
                ),
            ),
        )
    }

    private fun assertRefused(t: LearningTables, weightsVersion: Int) {
        val c = cardRow(t)
        assertEquals(JsonString(Reserved.REPLAY_REFUSED_ID), c["last_review_id"])
        assertEquals(JsonString("new"), c["state"])
        for (col in listOf("step", "stability", "difficulty", "due_at", "interval_days", "last_review_at")) assertEquals(JsonNull, c[col], col)
        assertEquals(JsonNumber(0.0), c["reps"])
        assertEquals(JsonNumber(0.0), c["lapses"])
        assertEquals(JsonNumber(weightsVersion.toDouble()), c["weights_version"])
        assertEquals(listOf(card), DeviceDiagnostics.refusedCacheCards(t))
    }

    private fun assertHealed(t: LearningTables) {
        val c = cardRow(t)
        assertNotEquals(JsonString(Reserved.REPLAY_REFUSED_ID), c["last_review_id"])
        assertNotEquals(JsonNull, c["last_review_id"])
        assertEquals(emptyList(), DeviceDiagnostics.refusedCacheCards(t))
    }

    @Test
    fun anInvalidReplaySetGivesTheRefusedCacheThePageCommitsAndUndoingTheOffendingReviewHealsIt() {
        val server = TestServer(1_733_600_000_000L)
        Examples.world.forEach { server.append(Examples.op(it).copy(seq = null), null) }
        val dev = ApplyDevice("01931f3e-0000-7000-8000-0000000000a1", server, server.nowMs)
        assertIs<CycleResult.Completed>(dev.cycle())
        // an apply bug left a review_log row that violates the CHECKs (scheduled, no rating)
        dev.store.transaction { tx ->
            val row = LinkedHashMap(tx.learning.get(LearningTable.REVIEW_LOG, listOf(JsonString("0193a1b2-0003-7000-8000-000000000001")))!!)
            row["id"] = JsonString(badId)
            row["review_type"] = JsonString("scheduled")
            row["rating"] = JsonNull
            row["reviewed_at"] = JsonNumber(1_733_584_666_000.0)
            row["hlc"] = JsonString(later(Examples.op("review.logged.json").hlc, -1_000))
            tx.learning.put(LearningTable.REVIEW_LOG, row)
        }
        // a review answered before the last folded one arrives: full replay, which the engine refuses
        server.append(review("0193a1b2-0003-7000-8000-0000000000c1", "0193a1b2-00ff-7000-8000-0000000c0001", 1_733_584_666_500, 2_000), null)
        assertIs<CycleResult.Completed>(dev.cycle(), "the page still commits")
        assertEquals(server.highWaterSeq.toString(), dev.state(SyncStateKeys.PULL_SEQ))
        assertRefused(dev.tables(), 0)
        assertEquals(listOf(card), dev.diagnostics.refusals.map { it.cardId })
        assertTrue(dev.diagnostics.refusals.single().error.isNotEmpty())

        // decide never folds into a refused cache (core contract step 1b) ...
        val cache = DeviceCardCache.cacheOf(cardRow(dev.tables()))
        val params = FsrsScheduler.paramsOf(DeviceCardCache.presetRowOf(dev.tables().get(LearningTable.FSRS_PARAMS, listOf(JsonString(Reserved.DEFAULT_PRESET_ID)))!!))
        val next = review("0193a1b2-0003-7000-8000-0000000000c2", "0193a1b2-00ff-7000-8000-0000000c0002", 1_733_690_000_000, 100_000_000)
        val nextRow = DeviceCardCache.toReplayReview(
            next.payload.members.mapValues { it.value } + mapOf("card_id" to JsonString(card), "hlc" to JsonString(next.hlc), "undone_hlc" to JsonNull),
        )!!
        assertEquals(ReplayDecision.FULL_REPLAY, DefaultReplayEngine.decide(cache, null, nextRow, params))
        // ... so a later review is replayed with the whole set again and refused again
        server.append(next, null)
        assertIs<CycleResult.Completed>(dev.cycle())
        assertRefused(dev.tables(), 0)
        assertEquals(2, dev.diagnostics.refusals.size)

        // undoing the offending review removes it from the replay set: the next replay succeeds
        val undo = Examples.op("review.undone.json").let {
            it.copy(
                opId = "0193a1b2-00ff-7000-8000-0000000c0003", entityId = badId, hlc = later(next.hlc, 1_000),
                payload = JsonObject(mapOf("review_id" to JsonString(badId))),
            )
        }
        server.append(undo, null)
        assertIs<CycleResult.Completed>(dev.cycle())
        assertHealed(dev.tables())
        assertEquals(JsonString("0193a1b2-0003-7000-8000-0000000000c2"), cardRow(dev.tables())["last_review_id"])
        assertEquals(2, dev.diagnostics.refusals.size)
    }

    private fun presetRefusing(): DeviceOpApplier<InMemoryLearningTables> =
        DeviceOpApplier(DeviceCardCache(engine = RefusingEngine { _, p -> p.desiredRetention == 0.93 }))

    /** The world with card [card] on the 0.93 preset, whose replays the engine refuses. */
    private fun refusedWorld(applier: DeviceOpApplier<InMemoryLearningTables>): InMemoryLearningTables {
        val t = Examples.worldTables(applier)
        assertHealed(t)
        applier.applyTo(t, Examples.op("card.preset_set.json"))
        assertRefused(t, 0)
        assertEquals(1, applier.cache.diagnostics.refusals.size)
        return t
    }

    @Test
    fun aPresetUpdateHealsTheRefusedCache() {
        val applier = presetRefusing()
        val t = refusedWorld(applier)
        applier.applyTo(t, Examples.op("fsrs_params.updated.json")) // desired_retention 0.95: replay of the preset's cards
        assertHealed(t)
    }

    @Test
    fun movingTheCardToAnotherPresetHealsTheRefusedCache() {
        val applier = presetRefusing()
        val t = refusedWorld(applier)
        val back = Examples.op("card.preset_set.json").let {
            it.copy(
                opId = "0193a1b2-00ff-7000-8000-0000000c0010", hlc = later(it.hlc, 1_000),
                payload = JsonObject(mapOf("id" to JsonString(card), "preset_id" to JsonString(Reserved.DEFAULT_PRESET_ID))),
            )
        }
        applier.applyTo(t, back)
        assertHealed(t)
    }

    @Test
    fun deletingThePresetHealsTheRefusedCache() {
        val applier = presetRefusing()
        val t = refusedWorld(applier)
        applier.applyTo(t, Examples.op("fsrs_params.deleted.json").let { it.copy(hlc = later(it.hlc, 10_000_000)) })
        assertHealed(t)
    }

    @Test
    fun theStartupReplayStoresTheVersionEvenWhenACardIsRefused() {
        val applier = presetRefusing()
        val t = refusedWorld(applier)
        val store = kotobaya.sync.client.store.InMemoryDeviceStore(t) { it.copy() }
        assertTrue(StartupReplay(applier.cache).run(store))
        assertEquals(DefaultReplayEngine.schedulerVersion, store.syncState()[StartupReplay.CORE_SCHEDULER_VERSION])
        assertRefused(store.learning(), 0)
    }
}
