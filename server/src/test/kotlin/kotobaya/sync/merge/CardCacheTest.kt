package kotobaya.sync.merge

import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.PresetRow
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayEngine
import kotobaya.contracts.replay.ReplayReview
import kotobaya.contracts.sync.OpType.CARD_CREATED
import kotobaya.contracts.sync.OpType.CARD_PRESET_SET
import kotobaya.contracts.sync.OpType.FSRS_PARAMS_CREATED
import kotobaya.contracts.sync.OpType.FSRS_PARAMS_DELETED
import kotobaya.contracts.sync.OpType.FSRS_PARAMS_UPDATED
import kotobaya.contracts.sync.OpType.NOTE_CREATED
import kotobaya.contracts.sync.OpType.REVIEW_LOGGED
import kotobaya.contracts.sync.OpType.REVIEW_UNDONE
import kotobaya.fsrs.FsrsScheduler
import kotobaya.replay.DefaultReplayEngine
import kotobaya.sync.PushResult
import kotobaya.sync.SyncModule
import kotobaya.sync.fuzz.RealSyncFixture
import kotobaya.sync.fuzz.RefReplica
import kotobaya.sync.fuzz.ServerProjection
import kotobaya.sync.fuzz.SimDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [CoreCardCache] on the op path (SYNC.md 9.2, 9.3): fold vs full replay, preset changes, a refused replay set. */
class CardCacheTest : RealSyncFixture() {
    private val base = 0x0193a1b2c000L

    private fun at(ms: Long, vararg devices: SimDevice) {
        server.pt = ms
        devices.forEach { it.pt = ms }
    }

    private fun card(d: SimDevice, preset: String? = null): String {
        val note = d.newId(1)
        d.make(NOTE_CREATED, mapOf("id" to note, "note_type_id" to Reserved.NOTE_TYPE_VOCAB))
        val card = d.newId(2)
        d.make(CARD_CREATED, buildMap { put("id", card); put("note_id", note); put("kind", "meaning"); preset?.let { put("preset_id", it) } })
        return card
    }

    private fun review(d: SimDevice, card: String, at: Long, rating: Int = 3): String = d.newId(3).also {
        d.make(
            REVIEW_LOGGED,
            mapOf("id" to it, "card_id" to card, "reviewed_at" to at, "utc_offset_min" to 540, "review_type" to "scheduled", "rating" to rating),
        )
    }

    private fun serverCache(card: String) = (ServerProjection.of(db).getValue("card")[card] as Map<*, *>)["cache"]

    private fun expected(d: SimDevice, card: String, params: SchedulerParams): Map<String, Any?> {
        val c = DefaultReplayEngine.replay(d.r.replayRows(card), params)
        return RefReplica.cacheMap(c.schedule, c.lastReviewId, c.weightsVersion)
    }

    private fun preset(retention: Double, deleted: Boolean = false) = FsrsScheduler.paramsOf(
        PresetRow("p", retention, 36500, listOf(60, 600), listOf(600), true, List(7) { 1.0 }, null, null, 0, deleted),
    )

    @Test
    fun inOrderReviewsFoldAndALateEarlierReviewReplaysToTheSameCacheAsAFullReplay() {
        val a = SimDevice(A)
        val b = SimDevice(B)
        at(base, a, b)
        val c = card(a)
        a.sync(server); b.sync(server)
        val day = Reserved.DAY_MS
        at(base + 1000, a, b)
        review(a, c, base - 5 * day)
        review(a, c, base - 2 * day)
        val early = review(b, c, base - 6 * day, rating = 1) // answered first, arrives last
        a.sync(server)
        assertEquals(expected(a, c, RefReplica.DEFAULT_PARAMS), serverCache(c), "folded in order")
        b.sync(server); a.sync(server)
        assertEquals(early, a.r.replaySet(c).first())
        assertEquals(expected(a, c, RefReplica.DEFAULT_PARAMS), serverCache(c), "full replay after the late review")
        // Undoing the middle review replays again (9.2).
        at(base + 2000, a, b)
        a.make(REVIEW_UNDONE, mapOf("review_id" to a.r.replaySet(c)[1]))
        a.sync(server)
        assertEquals(expected(a, c, RefReplica.DEFAULT_PARAMS), serverCache(c), "after undo")
        b.sync(server)
        assertConverged("caches", a, b)
    }

    @Test
    fun presetUpdatesDeletionAndPresetSetReplayTheCardsWhoseEffectivePresetChanged() {
        val a = SimDevice(A)
        at(base, a)
        val p = a.newId(6)
        a.make(FSRS_PARAMS_CREATED, mapOf("id" to p, "name" to "High", "desired_retention" to 0.95))
        val c = card(a, preset = p)
        val d = card(a)
        review(a, c, base + 10); review(a, d, base + 10)
        a.sync(server)
        assertEquals(expected(a, c, preset(0.95)), serverCache(c))
        at(base + 100, a)
        a.make(FSRS_PARAMS_UPDATED, mapOf("id" to p, "set" to mapOf("desired_retention" to 0.8)))
        a.sync(server)
        assertEquals(expected(a, c, preset(0.8)), serverCache(c), "updated preset")
        a.make(CARD_PRESET_SET, mapOf("id" to d, "preset_id" to p))
        a.sync(server)
        assertEquals(expected(a, d, preset(0.8)), serverCache(d), "preset_set")
        a.make(FSRS_PARAMS_DELETED, mapOf("id" to p))
        a.sync(server)
        assertEquals(expected(a, c, RefReplica.DEFAULT_PARAMS), serverCache(c), "tombstoned preset → Default")
        assertEquals(expected(a, d, RefReplica.DEFAULT_PARAMS), serverCache(d))
    }

    /**
     * SYNC.md 9.4 (C-8): the engine throws on a replay set it refuses; the op still commits and the card
     * gets the refused cache (never the old one). The next review replays in full (never a fold into it),
     * and once the engine accepts the set again the card is normal.
     */
    @Test
    fun aReplaySetTheEngineRefusesWritesTheRefusedCacheWithoutFailingThePush() {
        val a = SimDevice(A)
        at(base, a)
        val bad = card(a)
        val good = card(a)
        review(a, bad, base + 1)
        a.sync(server)
        assertEquals("learning", (serverCache(bad) as Map<*, *>)["state"], "a real cache before the refusal")
        val refusing = RefusingEngine(bad)
        server.module = SyncModule(db, syncConfig().copy(cardCache = CoreCardCache(refusing)))
        review(a, bad, base + 10); review(a, good, base + 10)
        review(a, bad, base + 5) // out of order → full replay → refused
        a.push(server)
        assertTrue(a.outbox.all { it.state == "acked" }, "every op accepted")
        assertTrue(refusing.refused > 0)
        val refused = RefReplica.cacheMap(CardSchedule.NEW, Reserved.REPLAY_REFUSED_ID, RefReplica.DEFAULT_PARAMS.weightsVersion)
        assertEquals(refused, serverCache(bad), "the refused cache, not the old one")
        assertEquals(expected(a, good, RefReplica.DEFAULT_PARAMS), serverCache(good))
        assertEquals(4L, count("select count(*) from app.review_log"))
        assertEquals(PushResult.DUPLICATE, pushAsOwner(a.outbox.last().op).status)

        // The engine accepts the set again (e.g. a fixed core): the next review is a full replay, never a fold.
        val counting = CountingEngine()
        server.module = SyncModule(db, syncConfig().copy(cardCache = CoreCardCache(counting)))
        review(a, bad, base + 20)
        a.push(server)
        assertEquals(0, counting.folds, "never folded into a refused cache")
        assertEquals(expected(a, bad, RefReplica.DEFAULT_PARAMS), serverCache(bad), "normal again after a successful replay")
    }

    /** The core engine, counting folds. */
    class CountingEngine : ReplayEngine by DefaultReplayEngine {
        @Volatile var folds = 0

        override fun fold(cache: CardCache, review: ReplayReview, params: SchedulerParams): CardCache {
            folds++
            return DefaultReplayEngine.fold(cache, review, params)
        }
    }

    /** Delegates to the core engine but refuses every replay set / fold that touches [cardId]. */
    class RefusingEngine(private val cardId: String) : ReplayEngine by DefaultReplayEngine {
        @Volatile var refused = 0

        override fun replay(reviews: List<ReplayReview>, params: SchedulerParams): CardCache {
            if (reviews.any { it.cardId == cardId }) { refused++; throw IllegalArgumentException("refused for the test") }
            return DefaultReplayEngine.replay(reviews, params)
        }

        override fun fold(cache: CardCache, review: ReplayReview, params: SchedulerParams): CardCache {
            if (review.cardId == cardId) { refused++; throw IllegalArgumentException("refused for the test") }
            return DefaultReplayEngine.fold(cache, review, params)
        }
    }
}
