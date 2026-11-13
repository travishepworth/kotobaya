package kotobaya.sync.merge

import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayDecision
import kotobaya.contracts.replay.ReplayEngine
import kotobaya.contracts.replay.ReplayKey
import kotobaya.contracts.replay.ReplayReview
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType
import kotobaya.replay.DefaultReplayEngine
import kotobaya.server.ServerStateKeys
import kotobaya.server.StartupSequence
import kotobaya.server.json
import kotobaya.sync.CardCacheHook
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.PresetChange
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.fuzz.ServerProjection
import kotobaya.sync.restore.CoreSchedulerReplay
import org.jooq.DSLContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * BUG-6 on the server (SYNC.md 9.5, 10.5): [CoreCardCache] reads `review_log.import_source`, so the
 * server's replay of imported jpdb history equals the core device replay ([DeviceOpApplier] on the
 * pulled log), and the startup replay after the `fsrs6-1` → `fsrs6-2` bump fixes caches computed by
 * the old core.
 */
class ImportReplayServerTest : SyncFixture() {
    /** The core before BUG-6: the same engine with `import_source` ignored (stands in for the old image). */
    private object LegacyFsrs61Engine : ReplayEngine by DefaultReplayEngine {
        override val schedulerVersion: String = "fsrs6-1"
        private fun strip(r: ReplayReview) = r.copy(importSource = null)
        override fun replay(reviews: List<ReplayReview>, params: SchedulerParams): CardCache =
            DefaultReplayEngine.replay(reviews.map(::strip), params)
        override fun fold(cache: CardCache, review: ReplayReview, params: SchedulerParams): CardCache =
            DefaultReplayEngine.fold(cache, strip(review), params)
        override fun decide(cache: CardCache, lastFolded: ReplayKey?, review: ReplayReview, params: SchedulerParams): ReplayDecision =
            DefaultReplayEngine.decide(cache, lastFolded, strip(review), params)
    }

    /** The op path's cache hook, swappable so one database can run the old core and then the new one. */
    private class SwappableCache(var target: CardCacheHook) : CardCacheHook {
        override fun reviewLogged(tx: DSLContext, reviewId: UUID, targetCardId: UUID) = target.reviewLogged(tx, reviewId, targetCardId)
        override fun reviewUndone(tx: DSLContext, reviewId: UUID, targetCardId: UUID) = target.reviewUndone(tx, reviewId, targetCardId)
        override fun replayCards(tx: DSLContext, cardIds: Set<UUID>) = target.replayCards(tx, cardIds)
        override fun presetChanged(tx: DSLContext, presetId: UUID, change: PresetChange) = target.presetChanged(tx, presetId, change)
    }

    private val hook = SwappableCache(CoreCardCache())
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = hook)

    private val day = Reserved.DAY_MS
    private val t0 = 1_704_099_600_000L
    private var n = 0
    private fun id(kind: Int) = "0193a1b2-000$kind-7000-8000-%012d".format(++n)

    /** Again, Good, Hard, Hard over 7 days: stuck in learning under fsrs6-1 (the ticket case). */
    private val learningStuck = listOf(t0 to 1, t0 + day to 3, t0 + 3 * day to 2, t0 + 7 * day to 2)

    /** Good, Good, Again, Hard, Hard: a lapse stuck in relearning under fsrs6-1. */
    private val relearningStuck = listOf(t0 to 3, t0 + day to 3, t0 + 10 * day to 1, t0 + 12 * day to 2, t0 + 20 * day to 2)

    /**
     * What `JpdbHistoryApply` emits for one card: `card.created` and its `review.logged` rows
     * (`import_source = jpdb`, `utc_offset_min` null), in [order] (the server folds or replays as 9.2 says).
     */
    private fun importCard(history: List<Pair<Long, Int>>, order: (List<Pair<Long, Int>>) -> List<Pair<Long, Int>> = { it }): String {
        val note = id(1)
        val card = id(2)
        emit(OpType.NOTE_CREATED, """{"id":"$note","note_type_id":"${Reserved.NOTE_TYPE_VOCAB}"}""")
        emit(OpType.CARD_CREATED, """{"id":"$card","note_id":"$note","kind":"meaning"}""")
        for ((at, rating) in order(history)) {
            emit(
                OpType.REVIEW_LOGGED,
                """{"id":"${id(3)}","card_id":"$card","reviewed_at":$at,"utc_offset_min":null,"review_type":"scheduled",""" +
                    """"rating":$rating,"button":null,"duration_ms":null,"import_source":"jpdb"}""",
            )
        }
        return card
    }

    private fun serverCache(card: String) = (ServerProjection.of(db).getValue("card")[card] as Map<*, *>)["cache"] as Map<*, *>

    /** A fresh device replica that pulled the whole server log and applied it with the new core. */
    private fun deviceReplica(): InMemoryLearningTables {
        val device = InMemoryLearningTables.seeded()
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        val page = json.readTree(sync.pull.pull(0, 500, null).text)
        for (o in page["ops"]) {
            val tree = DefaultCanonicalJson.decode(json.writeValueAsString(o)).value as JsonObject
            val op = assertIs<OpDecodeResult.Known>(DefaultOpCodec.decodeTree(tree, OpSource.PULLED)).op
            applier.applyTo(device, op)
        }
        return device
    }

    /** The device's card cache in [RefReplica.cacheMap] form (the server projection's `cache`). */
    private fun deviceCache(device: InMemoryLearningTables, card: String): Map<String, Any?> {
        val row = checkNotNull(device.get(LearningTable.CARD, listOf(JsonString(card))))
        fun long(k: String): Long? = (row[k] as? JsonNumber)?.value?.toLong()
        fun dbl(k: String): Double? = (row[k] as? JsonNumber)?.value
        fun str(k: String): String? = (row[k] as? JsonString)?.value
        val keys = mapOf<String, (String) -> Any?>(
            "state" to ::str, "step" to ::long, "stability" to ::dbl, "difficulty" to ::dbl, "due_at" to ::long,
            "interval_days" to ::long, "last_review_at" to ::long, "last_review_id" to ::str, "reps" to ::long,
            "lapses" to ::long, "weights_version" to ::long,
        )
        return sortedMapOf<String, Any?>().also { m -> keys.forEach { (k, f) -> m[k] = f(k) } }
    }

    private fun storedVersion(): String? = db.txBlocking { dsl ->
        dsl.fetchOptional("select value from app.server_state where key = ?", ServerStateKeys.CORE_SCHEDULER_VERSION)
            .map { it.get(0, String::class.java) }.orElse(null)
    }

    private fun assertServerEqualsDevice(vararg cards: String) {
        val device = deviceReplica()
        for (c in cards) assertEquals(deviceCache(device, c), serverCache(c).entries.associate { (k, v) -> k as String to v }.toSortedMap(), "card $c")
    }

    @Test
    fun serverReplayOfImportedHistoryEqualsDeviceReplayAndGraduates() {
        sync // migrated, startup with the new core
        val learning = importCard(learningStuck)
        val relearning = importCard(relearningStuck)
        val outOfOrder = importCard(learningStuck) { it.reversed() } // every later row is a full replay
        for (c in listOf(learning, relearning, outOfOrder)) assertEquals("review", serverCache(c)["state"], "card $c graduates")
        assertServerEqualsDevice(learning, relearning, outOfOrder)
        // Fold vs out-of-order full replay: the same memory state (due_at / interval_days carry the per-card fuzz).
        val unfuzzed = setOf("last_review_id", "due_at", "interval_days")
        assertEquals(serverCache(learning) - unfuzzed, serverCache(outOfOrder) - unfuzzed, "fold = full replay")

        // The same grades as native reviews keep py-fsrs semantics: still in learning / relearning.
        val native = id(2).also { card ->
            val note = id(1)
            emit(OpType.NOTE_CREATED, """{"id":"$note","note_type_id":"${Reserved.NOTE_TYPE_VOCAB}"}""")
            emit(OpType.CARD_CREATED, """{"id":"$card","note_id":"$note","kind":"meaning"}""")
            for ((at, rating) in learningStuck) {
                emit(OpType.REVIEW_LOGGED, """{"id":"${id(3)}","card_id":"$card","reviewed_at":$at,"utc_offset_min":0,"review_type":"scheduled","rating":$rating}""")
            }
        }
        assertEquals("learning", serverCache(native)["state"])
        assertServerEqualsDevice(native)
    }

    /**
     * The deploy: the old image (fsrs6-1) imported the history and stored stuck caches; the new image's
     * startup sees `core_scheduler_version` changed, replays every card in full, and the stuck cards end
     * in review, equal to the device replay. The review log is untouched and no op is minted.
     */
    @Test
    fun startupReplayAfterTheVersionBumpFixesStuckCaches() {
        sync // migrated (startup with the new core, nothing to replay yet)
        val legacy = CoreCardCache(LegacyFsrs61Engine)
        StartupSequence(db, schedulerVersion = legacy.schedulerVersion, replay = CoreSchedulerReplay(legacy)).runOnce()
        assertEquals("fsrs6-1", storedVersion())
        hook.target = legacy
        val learning = importCard(learningStuck)
        val relearning = importCard(relearningStuck)
        assertEquals("learning", serverCache(learning)["state"], "stuck under fsrs6-1")
        assertEquals("relearning", serverCache(relearning)["state"], "stuck under fsrs6-1")
        val logBefore = count("select count(*) from app.change")
        val reviewsBefore = db.txBlocking { it.fetch("select to_jsonb(r)::text from app.review_log r order by id").map { r -> r.get(0, String::class.java) } }

        // The new image: production wiring of StartupSequence (core version + CoreSchedulerReplay).
        hook.target = CoreCardCache()
        val real = CoreSchedulerReplay()
        assertNotEquals("fsrs6-1", real.schedulerVersion)
        StartupSequence(db, schedulerVersion = real.schedulerVersion, replay = real).runOnce()
        assertEquals(DefaultReplayEngine.SCHEDULER_VERSION, storedVersion())
        assertEquals("review", serverCache(learning)["state"])
        assertEquals("review", serverCache(relearning)["state"])
        assertServerEqualsDevice(learning, relearning)
        assertEquals(logBefore, count("select count(*) from app.change"), "no op minted")
        assertEquals(reviewsBefore, db.txBlocking { it.fetch("select to_jsonb(r)::text from app.review_log r order by id").map { r -> r.get(0, String::class.java) } })
    }
}
