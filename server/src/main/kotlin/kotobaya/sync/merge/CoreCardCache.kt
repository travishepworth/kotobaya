package kotobaya.sync.merge

import com.fasterxml.jackson.databind.JsonNode
import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.PresetRow
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.fsrs.Scheduler
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayDecision
import kotobaya.contracts.replay.ReplayEngine
import kotobaya.contracts.replay.ReplayKey
import kotobaya.contracts.replay.ReplayReview
import kotobaya.fsrs.FsrsScheduler
import kotobaya.replay.DefaultReplayEngine
import kotobaya.server.json
import kotobaya.sync.CardCacheHook
import kotobaya.sync.PresetChange
import kotobaya.sync.SyncJson
import org.jooq.DSLContext
import org.jooq.Record
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The card cache of SYNC.md 9 on the server, computed by the core's frozen [ReplayEngine] (T203)
 * with the effective preset turned into [SchedulerParams] by the core's [Scheduler.paramsOf].
 *
 * - As [CardCacheHook] (install as `SyncConfig.cardCache`): called inside the op transaction, which
 *   holds lock 7033001, so the replay is done "in the transaction that applied the triggering op"
 *   (9.3). A `review.logged` is folded or fully replayed as [ReplayEngine.decide] says (9.2);
 *   `review.undone`, `card.merged`, a winning `card.preset_set` and every preset change replay in
 *   full. A published preset (`weights_version` raised) is replayed eagerly too, so no stale cache
 *   is ever read or folded into (9.3 allows lazy; eager is simpler and equally correct).
 * - [replayAll] is the startup replay of SYNC.md 10.5 (see `CoreSchedulerReplay`).
 *
 * **Maintained caches** (9.3, 10.5): cards with `deleted_at IS NULL` and `merged_into_id IS NULL`.
 * Tombstoned cards and merge losers are skipped (deletion and merges are permanent, and SYNC.md 11
 * item 3 compares caches only for live non-loser cards).
 *
 * **A card the engine refuses** ([ReplayEngine.replay] / [ReplayEngine.fold] throw
 * [IllegalArgumentException] on duplicate ids or rows violating the `review_log` CHECKs, which the
 * server's primary key and CHECKs make impossible): the card is logged at ERROR and its cache is left
 * as it was; the op, or the startup replay, still commits. Failing instead would wedge every
 * device's outbox (a 500 on every push) or keep the server unready forever because of one card,
 * while the stale cache is only a derived value that the next successful replay rebuilds. Every
 * replica runs the same engine on the same rows, so they agree on refusing it.
 */
class CoreCardCache(
    private val engine: ReplayEngine = DefaultReplayEngine,
    private val scheduler: Scheduler = FsrsScheduler,
) : CardCacheHook {
    private val log = LoggerFactory.getLogger(CoreCardCache::class.java)

    /** The version this cache is computed with (`core_scheduler_version`, SYNC.md 10.5). */
    val schedulerVersion: String get() = engine.schedulerVersion

    override fun reviewLogged(tx: DSLContext, reviewId: UUID, targetCardId: UUID) {
        val card = loadCard(tx, targetCardId) ?: return
        val review = loadReviews(tx, "r.id = ?::uuid", reviewId.toString()).firstOrNull()?.second ?: return
        val params = paramsFor(card.presetId, loadPresets(tx))
        val lastFolded = card.cache.lastReviewId?.let { id ->
            loadReviews(tx, "r.id = ?::uuid", id).firstOrNull()?.second?.key
        }
        guarded(targetCardId) {
            when (engine.decide(card.cache, lastFolded, review, params)) {
                ReplayDecision.NONE -> Unit
                ReplayDecision.FOLD -> write(tx, targetCardId, engine.fold(card.cache, review, params))
                ReplayDecision.FULL_REPLAY -> replay(tx, setOf(targetCardId))
            }
        }
    }

    override fun reviewUndone(tx: DSLContext, reviewId: UUID, targetCardId: UUID) {
        replay(tx, setOf(targetCardId))
    }

    override fun replayCards(tx: DSLContext, cardIds: Set<UUID>) {
        replay(tx, cardIds)
    }

    override fun presetChanged(tx: DSLContext, presetId: UUID, change: PresetChange) {
        val cards = when (change) {
            // 9.2: every card with that preset_id (its effective preset changes to the Default).
            PresetChange.DELETED -> tx.fetch("select id from app.card where preset_id = ?::uuid", presetId.toString())
            // 9.2: every card whose *effective* preset it is: preset_id = P (P live), and for the Default
            // preset also every card whose own preset is tombstoned.
            PresetChange.UPDATED, PresetChange.PUBLISHED -> tx.fetch(
                "select c.id from app.card c join app.fsrs_params p on p.id = c.preset_id " +
                    "where (c.preset_id = ?::uuid and p.deleted_at is null) or (?::uuid = ?::uuid and p.deleted_at is not null)",
                presetId.toString(), presetId.toString(), Reserved.DEFAULT_PRESET_ID,
            )
        }.map { it.get(0, UUID::class.java) }.toSet()
        if (cards.isNotEmpty()) replay(tx, cards)
    }

    /** Full replay of every maintained card (SYNC.md 10.5). Returns the number of caches written. */
    fun replayAll(tx: DSLContext): Int = replay(tx, null)

    /**
     * Full replay of [cardIds] (null = all), skipping the ones that are not maintained. Reads every
     * replay set in one query: rows of the card or of a card merged into it (9.1); the engine filters
     * undone / non-schedule-feeding rows and sorts by `(reviewed_at, hlc, id)` itself.
     */
    private fun replay(tx: DSLContext, cardIds: Set<UUID>?): Int {
        if (cardIds != null && cardIds.isEmpty()) return 0
        val presets = loadPresets(tx)
        val ids = cardIds?.map(UUID::toString)?.toTypedArray()
        val cards = tx.fetch(
            "select id, preset_id::text from app.card where deleted_at is null and merged_into_id is null" +
                (if (ids != null) " and id = any(?::uuid[])" else ""),
            *listOfNotNull(ids).toTypedArray(),
        ).map { it.get(0, UUID::class.java) to it.get(1, String::class.java) }
        if (cards.isEmpty()) return 0
        val byTarget = HashMap<UUID, MutableList<ReplayReview>>()
        val filter = "r.undone_at is null and r.review_type in ('scheduled', 'learning', 'manual')" +
            (if (ids != null) " and coalesce(c.merged_into_id, c.id) = any(?::uuid[])" else "")
        for ((target, review) in loadReviews(tx, filter, *listOfNotNull(ids).toTypedArray())) {
            byTarget.getOrPut(target) { mutableListOf() } += review
        }
        var written = 0
        for ((id, presetId) in cards) {
            guarded(id) {
                write(tx, id, engine.replay(byTarget[id].orEmpty(), paramsFor(presetId, presets)))
                written++
            }
        }
        return written
    }

    private inline fun guarded(cardId: UUID, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            log.error("card {}: the replay engine refused its replay set; cache left unchanged", cardId, e)
        }
    }

    // ------------------------------------------------------------------ rows

    private class CardRow(val presetId: String, val cache: CardCache)

    private fun loadCard(tx: DSLContext, id: UUID): CardRow? {
        val r = tx.fetchOne(
            "select preset_id::text, state, step, stability, difficulty, $DUE, interval_days, $LAST_AT, " +
                "last_review_id::text, reps, lapses, weights_version from app.card c " +
                "where id = ?::uuid and deleted_at is null and merged_into_id is null",
            id.toString(),
        ) ?: return null
        val state = r.get(1, String::class.java)
        val schedule = CardSchedule(
            state = CardState.entries.first { it.wire == state },
            step = (r.get(2) as Number?)?.toInt(),
            stability = (r.get(3) as Number?)?.toDouble(),
            difficulty = (r.get(4) as Number?)?.toDouble(),
            dueAt = (r.get(5) as Number?)?.toLong(),
            intervalDays = (r.get(6) as Number?)?.toInt(),
            lastReviewAt = (r.get(7) as Number?)?.toLong(),
            reps = (r.get(9) as Number).toInt(),
            lapses = (r.get(10) as Number).toInt(),
        )
        return CardRow(r.get(0, String::class.java), CardCache(schedule, r.get(8, String::class.java), (r.get(11) as Number).toInt()))
    }

    private fun write(tx: DSLContext, id: UUID, c: CardCache) {
        val s = c.schedule
        tx.execute(
            "update app.card set state = ?, step = ?, stability = ?, difficulty = ?, due_at = ?::timestamptz, interval_days = ?, " +
                "last_review_at = ?::timestamptz, last_review_id = ?::uuid, reps = ?, lapses = ?, weights_version = ? where id = ?::uuid",
            s.state.wire, s.step?.toShort(), s.stability, s.difficulty, s.dueAt?.let(SyncJson::iso), s.intervalDays,
            s.lastReviewAt?.let(SyncJson::iso), c.lastReviewId, s.reps, s.lapses, c.weightsVersion, id.toString(),
        )
    }

    /** `(target card, row)` for the `review_log` rows matching [where] (alias `r`; `c` is the row's card). */
    private fun loadReviews(tx: DSLContext, where: String, vararg args: Any?): List<Pair<UUID, ReplayReview>> = tx.fetch(
        "select coalesce(c.merged_into_id, c.id), r.id::text, r.card_id::text, r.hlc::text, ${ms("r.reviewed_at")}, r.utc_offset_min, " +
            "r.review_type, r.rating, r.manual_action, ${ms("r.manual_due_at")}, r.undone_hlc::text " +
            "from app.review_log r join app.card c on c.id = r.card_id where $where",
        *args,
    ).map { it.get(0, UUID::class.java) to review(it) }

    private fun review(r: Record): ReplayReview {
        val type = r.get(6, String::class.java)
        val action = r.get(8, String::class.java)
        return ReplayReview(
            id = r.get(1, String::class.java),
            cardId = r.get(2, String::class.java),
            hlc = r.get(3, String::class.java),
            reviewedAt = (r.get(4) as Number).toLong(),
            utcOffsetMin = (r.get(5) as Number?)?.toInt(),
            reviewType = ReviewType.entries.first { it.wire == type },
            rating = (r.get(7) as Number?)?.toInt()?.let { v -> Rating.entries.first { it.value == v } },
            manualAction = action?.let { a -> ManualAction.entries.first { it.wire == a } },
            manualDueAt = (r.get(9) as Number?)?.toLong(),
            undoneHlc = r.get(10, String::class.java),
        )
    }

    /** Every preset row (a handful), by id. */
    private fun loadPresets(tx: DSLContext): Map<String, PresetRow> =
        tx.fetch("select to_jsonb(p)::text from app.fsrs_params p").associate { rec ->
            val n = json.readTree(rec.get(0, String::class.java))
            n["id"].asText() to PresetRow(
                id = n["id"].asText(),
                desiredRetention = n["desired_retention"].asDouble(),
                maximumIntervalDays = n["maximum_interval_days"].asInt(),
                learningStepsSeconds = n["learning_steps_s"].map(JsonNode::asInt),
                relearningStepsSeconds = n["relearning_steps_s"].map(JsonNode::asInt),
                fuzz = n["fuzz"].asBoolean(),
                easyDays = n["easy_days"].map(JsonNode::asDouble),
                fsrsVersion = n["fsrs_version"]?.takeUnless { it.isNull }?.asText(),
                weights = n["weights"]?.takeUnless { it.isNull }?.map(JsonNode::asDouble),
                weightsVersion = n["weights_version"].asInt(),
                deleted = !(n["deleted_at"]?.isNull ?: true),
            )
        }

    /** The effective preset (9.1): the card's preset, or the Default preset if that row is tombstoned. */
    private fun paramsFor(presetId: String, presets: Map<String, PresetRow>): SchedulerParams {
        val own = presets[presetId]
        val effective = if (own == null || own.deleted) checkNotNull(presets[Reserved.DEFAULT_PRESET_ID]) { "the Default preset is missing" } else own
        return scheduler.paramsOf(effective)
    }

    private companion object {
        fun ms(col: String) = "(extract(epoch from $col) * 1000)::bigint"
        val DUE = ms("c.due_at")
        val LAST_AT = ms("c.last_review_at")
    }
}
