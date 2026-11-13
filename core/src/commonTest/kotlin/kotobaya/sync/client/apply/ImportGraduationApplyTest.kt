package kotobaya.sync.client.apply

import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayDecision
import kotobaya.contracts.replay.ReplayEngine
import kotobaya.contracts.replay.ReplayKey
import kotobaya.contracts.replay.ReplayReview
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.replay.DefaultReplayEngine
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The core before BUG-6 (`"fsrs6-1"`): the same engine with `import_source` ignored, i.e. native
 * py-fsrs semantics for imported rows. Stands in for the old app/server build.
 */
object LegacyFsrs61Engine : ReplayEngine by DefaultReplayEngine {
    override val schedulerVersion: String = "fsrs6-1"

    private fun strip(r: ReplayReview) = r.copy(importSource = null)

    override fun replay(reviews: List<ReplayReview>, params: SchedulerParams): CardCache =
        DefaultReplayEngine.replay(reviews.map(::strip), params)

    override fun fold(cache: CardCache, review: ReplayReview, params: SchedulerParams): CardCache =
        DefaultReplayEngine.fold(cache, strip(review), params)

    override fun decide(cache: CardCache, lastFolded: ReplayKey?, review: ReplayReview, params: SchedulerParams): ReplayDecision =
        DefaultReplayEngine.decide(cache, lastFolded, strip(review), params)
}

/** Server-minted ops of a jpdb history import (OPS.md `review.logged`, `import_source` + `replay_hlc`). */
object ImportOps {
    private const val DAY = Reserved.DAY_MS
    const val T0: Long = 1_704_099_600_000L

    private fun hex(v: Long, digits: Int) = v.toString(16).padStart(digits, '0')

    /** A server hlc at [ms] with [counter]. */
    fun serverHlc(ms: Long, counter: Int): String = hex(ms, 12) + "-" + hex(counter.toLong(), 4) + "-" + Reserved.SERVER_DEVICE_ID

    /** `card.created` of card [cardId] on the world's note (the example op with another id). */
    fun card(cardId: String, n: Int): Op {
        val base = Examples.op("card.created.json")
        return base.copy(
            opId = "0193a1b2-00ff-7000-8000-0000000b0${hex(n.toLong(), 3)}",
            entityId = cardId,
            hlc = later(base.hlc, n.toLong()),
            payload = JsonObject(base.payload.members + ("id" to JsonString(cardId))),
        )
    }

    /**
     * Imported review [n] of [cardId]: answered at [reviewedAt] with [rating]; envelope hlc minted
     * at import time [mintedAtMs] (after the card), `replay_hlc` from the historical time.
     */
    fun review(cardId: String, n: Int, reviewedAt: Long, rating: Int, mintedAtMs: Long, seq: Long? = null): Op {
        val base = Examples.op("review.logged.import.json")
        val id = "0193a1b2-0b06-7000-8000-${hex(n.toLong(), 12)}"
        return base.copy(
            opId = "0193a1b2-00ff-7000-8000-0000000c${hex(n.toLong(), 4)}",
            entityId = id,
            hlc = serverHlc(mintedAtMs, n),
            seq = seq,
            causedByOpId = null,
            payload = JsonObject(
                base.payload.members + mapOf(
                    "id" to JsonString(id),
                    "card_id" to JsonString(cardId),
                    "reviewed_at" to JsonNumber(reviewedAt.toDouble()),
                    "rating" to JsonNumber(rating.toDouble()),
                    "button" to JsonNull,
                    "replay_hlc" to JsonString(serverHlc(reviewedAt, n)),
                ),
            ),
        )
    }

    /** Again, Good, Hard, Hard over 7 days: stuck in learning under fsrs6-1. */
    val learningStuck: List<Pair<Long, Int>> = listOf(T0 to 1, T0 + DAY to 3, T0 + 3 * DAY to 2, T0 + 7 * DAY to 2)

    /** Good, Good, Again, Hard, Hard: a lapse stuck in relearning under fsrs6-1. */
    val relearningStuck: List<Pair<Long, Int>> =
        listOf(T0 to 3, T0 + DAY to 3, T0 + 10 * DAY to 1, T0 + 12 * DAY to 2, T0 + 20 * DAY to 2)
}

/** BUG-6 on a device: the apply path passes `import_source`, and the scheduler-version bump re-replays. */
class ImportGraduationApplyTest {
    private val cardL = "0193a1b2-0002-7000-8000-0000000b0601"
    private val cardR = "0193a1b2-0002-7000-8000-0000000b0602"

    private fun importLog(): List<Op> {
        val base = Examples.op("card.created.json")
        val minted = DefaultHlcRules.wall(base.hlc) + 1_000
        val ops = ArrayList<Op>()
        ops += ImportOps.card(cardL, 1)
        ops += ImportOps.card(cardR, 2)
        var n = 0
        for ((at, rating) in ImportOps.learningStuck) ops += ImportOps.review(cardL, ++n, at, rating, minted + n)
        for ((at, rating) in ImportOps.relearningStuck) ops += ImportOps.review(cardR, ++n, at, rating, minted + n)
        return ops
    }

    private fun state(t: LearningTables, card: String): String? =
        (t.get(LearningTable.CARD, listOf(JsonString(card)))!!["state"] as? JsonString)?.value

    private fun cacheRow(t: LearningTables, card: String) = t.get(LearningTable.CARD, listOf(JsonString(card)))!!

    @Test
    fun schedulerVersionIsBumped() {
        assertEquals("fsrs6-2", DefaultReplayEngine.schedulerVersion)
        assertNotEquals(LegacyFsrs61Engine.schedulerVersion, DefaultReplayEngine.schedulerVersion)
    }

    @Test
    fun theApplyPathPassesImportSourceSoImportedCardsGraduate() {
        val t = Examples.worldTables()
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        for (op in importLog()) assertTrue(applier.applyTo(t, op))
        assertEquals(CardState.REVIEW.wire, state(t, cardL))
        assertEquals(CardState.REVIEW.wire, state(t, cardR))
        assertTrue(DeviceCardCache.toReplayReview(t.where(LearningTable.REVIEW_LOG, "card_id", JsonString(cardL)).first())!!.importSource == "jpdb")
    }

    /**
     * Ticket acceptance: a device that applied the import with the old core holds stuck caches; the app
     * update (new core version) runs the SYNC.md 10.5 startup replay, which ends them in REVIEW, equal
     * to a replica that applied the import with the new core from the start, and mints no op.
     */
    @Test
    fun versionBumpTriggersFullReplayAndStuckCardsEndInReview() {
        val legacy = DeviceCardCache(engine = LegacyFsrs61Engine)
        val store = InMemoryDeviceStore(Examples.worldTables()) { it.copy() }
        assertTrue(StartupReplay(legacy).run(store))
        val oldApplier = DeviceOpApplier<InMemoryLearningTables>(legacy)
        for (op in importLog()) store.transaction { tx -> oldApplier.apply(tx, op, ApplyOrigin.PULLED) }
        assertEquals("fsrs6-1", store.syncState()[StartupReplay.CORE_SCHEDULER_VERSION])
        assertEquals(CardState.LEARNING.wire, state(store.learning(), cardL), "stuck under fsrs6-1")
        assertEquals(CardState.RELEARNING.wire, state(store.learning(), cardR), "stuck under fsrs6-1")
        val logBefore = store.learning().all(LearningTable.REVIEW_LOG)

        // the app update: new core, startup replay
        assertTrue(StartupReplay().run(store))
        assertEquals("fsrs6-2", store.syncState()[StartupReplay.CORE_SCHEDULER_VERSION])
        assertEquals(CardState.REVIEW.wire, state(store.learning(), cardL))
        assertEquals(CardState.REVIEW.wire, state(store.learning(), cardR))
        assertEquals(emptyList(), store.rows(), "no op minted")
        assertEquals(logBefore, store.learning().all(LearningTable.REVIEW_LOG), "review_log untouched")

        // equal to a replica that never ran the old core
        val fresh = Examples.worldTables()
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        for (op in importLog()) applier.applyTo(fresh, op)
        assertEquals(projection(fresh), projection(store.learning()))
        assertEquals(cacheRow(fresh, cardL), cacheRow(store.learning(), cardL))
        assertEquals(cacheRow(fresh, cardR), cacheRow(store.learning(), cardR))
        assertTrue(!StartupReplay().run(store), "second start: nothing to do")
    }

    /** An old-core device folding a pulled import op after the bump would diverge; the apply path replays first (10.5). */
    @Test
    fun anOpPulledAfterTheUpdateIsAppliedOnlyAfterTheReplay() {
        val legacy = DeviceCardCache(engine = LegacyFsrs61Engine)
        val store = InMemoryDeviceStore(Examples.worldTables()) { it.copy() }
        StartupReplay(legacy).run(store)
        val log = importLog()
        val oldApplier = DeviceOpApplier<InMemoryLearningTables>(legacy)
        for (op in log.dropLast(1)) store.transaction { tx -> oldApplier.apply(tx, op, ApplyOrigin.PULLED) }
        // new core; no explicit startup call: the applier's ensure() replays before folding
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        store.transaction { tx -> applier.apply(tx, log.last(), ApplyOrigin.PULLED) }
        val fresh = Examples.worldTables().also { t -> log.forEach { DeviceOpApplier<InMemoryLearningTables>().applyTo(t, it) } }
        assertEquals(projection(fresh), projection(store.learning()))
        assertEquals(CardState.REVIEW.wire, state(store.learning(), cardR))
    }
}
