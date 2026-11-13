package kotobaya.sync.client.apply

import kotobaya.contracts.Reserved
import kotobaya.contracts.Uuid
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
import kotobaya.contracts.replay.ReplayReview
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.fsrs.FsrsScheduler
import kotobaya.replay.DefaultReplayEngine

/**
 * Where a device reports replay sets the engine refused (SYNC.md 9.4 item 3: log the card id and the
 * exception; a device SHOULD show it in its sync diagnostics).
 *
 * [refusals] lists the refusals seen by this process (an append-only event list, kept even if the
 * transaction that saw it later rolls back); [refusedCacheCards] reads the durable truth: the cards
 * whose cache *is* the refused cache now. [sink] is called for every refusal (the platform logger at
 * ERROR).
 */
public class DeviceDiagnostics(
    private val sink: (Refusal) -> Unit = {},
) {
    /** One refused replay or fold. */
    public data class Refusal(
        /** The card that got the refused cache. */
        public val cardId: Uuid,
        /** The engine's exception message. */
        public val error: String,
    )

    private val events = ArrayList<Refusal>()

    /** Refusals recorded so far, oldest first. */
    public val refusals: List<Refusal> get() = events.toList()

    /** Records a refusal and passes it to the sink. */
    public fun record(cardId: Uuid, error: String) {
        val r = Refusal(cardId, error)
        events.add(r)
        sink(r)
    }

    /** Readers. */
    public companion object {
        /**
         * Ids of the cards whose cache is the refused cache (`last_review_id = REPLAY_REFUSED_ID`,
         * SYNC.md 9.4), sorted. Count it for `SyncSnapshot.read(tx) { refusedCacheCards(it).size }`.
         */
        public fun refusedCacheCards(tables: LearningTables): List<Uuid> =
            tables.where(LearningTable.CARD, "last_review_id", JsonString(Reserved.REPLAY_REFUSED_ID))
                .map { (it.getValue("id") as JsonString).value }
                .sorted()
    }
}

/**
 * The card cache of SYNC.md 9 on a device, computed only through the frozen [ReplayEngine] contract
 * (`decide` / `fold` / `replay`) with the effective preset turned into [SchedulerParams] by
 * [Scheduler.paramsOf]. The device twin of the server's `CoreCardCache` (T202c/F-3).
 *
 * Every call happens in the transaction that applied the triggering op (9.3). **Maintained caches**
 * are those of cards with `deleted_at` and `merged_into_id` NULL; others are skipped (9.3, 11 item 3).
 * A published preset is replayed eagerly (9.3 allows lazy; eager means no stale cache is ever read).
 *
 * **Refusal (9.4):** when the engine throws [IllegalArgumentException] the card gets the refused cache
 * `CardCache(CardSchedule.NEW, REPLAY_REFUSED_ID, params.weightsVersion)` (never the old cache), the
 * refusal goes to [diagnostics], and nothing is thrown: the page, push, rebuild or startup replay commits.
 */
public class DeviceCardCache(
    /** The replay engine (the core's [DefaultReplayEngine] in production). */
    public val engine: ReplayEngine = DefaultReplayEngine,
    private val scheduler: Scheduler = FsrsScheduler,
    /** Where refusals are reported. */
    public val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
) {
    /** After `review.logged` inserted [review] for target card [targetCardId]: fold or replay as `decide` says (9.2). */
    public fun reviewLogged(tables: LearningTables, review: Row, targetCardId: Uuid) {
        val card = maintained(tables, targetCardId) ?: return
        val rr = toReplayReview(review) ?: return
        val params = paramsFor(tables, card)
        val cache = cacheOf(card)
        guarded(tables, targetCardId, params) {
            val lastFolded = cache.lastReviewId
                ?.takeUnless { it == Reserved.REPLAY_REFUSED_ID }
                ?.let { id -> tables.get(LearningTable.REVIEW_LOG, listOf(JsonString(id)))?.let(::toReplayReview)?.key }
            when (engine.decide(cache, lastFolded, rr, params)) {
                ReplayDecision.NONE -> Unit
                ReplayDecision.FOLD -> write(tables, targetCardId, engine.fold(cache, rr, params))
                ReplayDecision.FULL_REPLAY -> write(tables, targetCardId, engine.replay(replaySet(tables, targetCardId), params))
            }
        }
    }

    /** Full replay of each maintained card of [cardIds] (undo, merge, preset change; 9.2). */
    public fun replayCards(tables: LearningTables, cardIds: Collection<Uuid>) {
        for (id in cardIds.distinct().sorted()) {
            val card = maintained(tables, id) ?: continue
            replayOne(tables, id, paramsFor(tables, card))
        }
    }

    /** Full replay of every card whose effective preset is [presetId] (9.2: `fsrs_params.updated` / `published`). */
    public fun replayEffectivePreset(tables: LearningTables, presetId: Uuid) {
        val presetDeleted = isDeleted(tables.get(LearningTable.FSRS_PARAMS, listOf(JsonString(presetId))))
        val cards = LinkedHashSet<Uuid>()
        if (!presetDeleted) tables.where(LearningTable.CARD, "preset_id", JsonString(presetId)).forEach { cards += idOf(it) }
        if (presetId == Reserved.DEFAULT_PRESET_ID) {
            for (p in tables.all(LearningTable.FSRS_PARAMS)) {
                if (isDeleted(p)) tables.where(LearningTable.CARD, "preset_id", p.getValue("id")).forEach { cards += idOf(it) }
            }
            tables.all(LearningTable.CARD).forEach { c ->
                if (tables.get(LearningTable.FSRS_PARAMS, listOf(c.getValue("preset_id"))) == null) cards += idOf(c)
            }
        }
        replayCards(tables, cards)
    }

    /** Full replay of every card whose `preset_id` is [presetId] (9.2: `fsrs_params.deleted`, first transition). */
    public fun replayPresetCards(tables: LearningTables, presetId: Uuid) {
        replayCards(tables, tables.where(LearningTable.CARD, "preset_id", JsonString(presetId)).map(::idOf))
    }

    /** Full replay of every maintained card (SYNC.md 10.5). Returns how many caches were written. */
    public fun replayAll(tables: LearningTables): Int {
        var n = 0
        for (card in tables.all(LearningTable.CARD).sortedBy(::idOf)) {
            if (!isMaintained(card)) continue
            replayOne(tables, idOf(card), paramsFor(tables, card))
            n++
        }
        return n
    }

    /**
     * SYNC.md 9.1 replay set of [cardId] as stored: rows of the card and of every card merged into it,
     * not undone, schedule-feeding, in `(reviewed_at, hlc, id)` order. A row the engine could not
     * read (unknown `review_type`) is left out.
     */
    public fun replaySet(tables: LearningTables, cardId: Uuid): List<ReplayReview> {
        val rows = ArrayList<Row>()
        rows += tables.where(LearningTable.REVIEW_LOG, "card_id", JsonString(cardId))
        for (loser in tables.where(LearningTable.CARD, "merged_into_id", JsonString(cardId))) {
            rows += tables.where(LearningTable.REVIEW_LOG, "card_id", loser.getValue("id"))
        }
        return rows.mapNotNull(::toReplayReview)
            .filter { it.undoneHlc == null && it.reviewType.feedsSchedule }
            .sortedWith { a, b -> engine.compare(a.key, b.key) }
    }

    private fun replayOne(tables: LearningTables, cardId: Uuid, params: SchedulerParams) {
        guarded(tables, cardId, params) { write(tables, cardId, engine.replay(replaySet(tables, cardId), params)) }
    }

    private inline fun guarded(tables: LearningTables, cardId: Uuid, params: SchedulerParams, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            write(tables, cardId, CardCache(CardSchedule.NEW, Reserved.REPLAY_REFUSED_ID, params.weightsVersion))
            diagnostics.record(cardId, e.message ?: e.toString())
        }
    }

    // ------------------------------------------------------------------ rows

    private fun maintained(tables: LearningTables, id: Uuid): Row? =
        tables.get(LearningTable.CARD, listOf(JsonString(id)))?.takeIf(::isMaintained)

    private fun write(tables: LearningTables, cardId: Uuid, c: CardCache) {
        val row = tables.get(LearningTable.CARD, listOf(JsonString(cardId))) ?: return
        val s = c.schedule
        val out = LinkedHashMap(row)
        out["state"] = JsonString(s.state.wire)
        out["step"] = num(s.step?.toLong())
        out["stability"] = s.stability?.let(::JsonNumber) ?: JsonNull
        out["difficulty"] = s.difficulty?.let(::JsonNumber) ?: JsonNull
        out["due_at"] = num(s.dueAt)
        out["interval_days"] = num(s.intervalDays?.toLong())
        out["last_review_at"] = num(s.lastReviewAt)
        out["last_review_id"] = c.lastReviewId?.let(::JsonString) ?: JsonNull
        out["reps"] = JsonNumber(s.reps.toDouble())
        out["lapses"] = JsonNumber(s.lapses.toDouble())
        out["weights_version"] = JsonNumber(c.weightsVersion.toDouble())
        tables.put(LearningTable.CARD, out)
    }

    /** The effective preset (9.1): the card's preset row, or the Default preset if it is tombstoned (or absent). */
    private fun paramsFor(tables: LearningTables, card: Row): SchedulerParams {
        val own = tables.get(LearningTable.FSRS_PARAMS, listOf(card["preset_id"] ?: JsonNull))
        val effective = if (own == null || isDeleted(own)) {
            checkNotNull(tables.get(LearningTable.FSRS_PARAMS, listOf(JsonString(Reserved.DEFAULT_PRESET_ID)))) { "the Default preset is missing" }
        } else {
            own
        }
        return scheduler.paramsOf(presetRowOf(effective))
    }

    internal companion object {
        fun idOf(row: Row): Uuid = (row.getValue("id") as JsonString).value

        fun isDeleted(row: Row?): Boolean = row != null && (row["deleted_at"] ?: JsonNull) != JsonNull

        fun isMaintained(card: Row): Boolean =
            (card["deleted_at"] ?: JsonNull) == JsonNull && (card["merged_into_id"] ?: JsonNull) == JsonNull

        fun num(v: Long?): JsonValue = if (v == null) JsonNull else JsonNumber(v.toDouble())

        private fun long(v: JsonValue?): Long? = (v as? JsonNumber)?.value?.toLong()

        private fun str(v: JsonValue?): String? = (v as? JsonString)?.value

        fun cacheOf(card: Row): CardCache {
            val state = str(card["state"])
            val schedule = CardSchedule(
                state = CardState.entries.firstOrNull { it.wire == state } ?: CardState.NEW,
                step = long(card["step"])?.toInt(),
                stability = (card["stability"] as? JsonNumber)?.value,
                difficulty = (card["difficulty"] as? JsonNumber)?.value,
                dueAt = long(card["due_at"]),
                intervalDays = long(card["interval_days"])?.toInt(),
                lastReviewAt = long(card["last_review_at"]),
                reps = long(card["reps"])?.toInt() ?: 0,
                lapses = long(card["lapses"])?.toInt() ?: 0,
            )
            return CardCache(schedule, str(card["last_review_id"]), long(card["weights_version"])?.toInt() ?: 0)
        }

        /**
         * A `review_log` row as the engine reads it. Values outside the CHECKs become null so that the
         * engine, not this reader, refuses them (9.4). Null for a row of unknown `review_type`.
         */
        fun toReplayReview(row: Row): ReplayReview? {
            val type = ReviewType.entries.firstOrNull { it.wire == str(row["review_type"]) } ?: return null
            val rating = long(row["rating"])?.toInt()
            return ReplayReview(
                id = str(row["id"]) ?: return null,
                cardId = str(row["card_id"]) ?: return null,
                hlc = str(row["hlc"]) ?: return null,
                reviewedAt = long(row["reviewed_at"]) ?: return null,
                utcOffsetMin = long(row["utc_offset_min"])?.toInt(),
                reviewType = type,
                rating = Rating.entries.firstOrNull { it.value == rating },
                manualAction = ManualAction.entries.firstOrNull { it.wire == str(row["manual_action"]) },
                manualDueAt = long(row["manual_due_at"]),
                undoneHlc = str(row["undone_hlc"]),
                importSource = str(row["import_source"]),
            )
        }

        private fun doubles(v: JsonValue?): List<Double>? = (v as? JsonArray)?.items?.map { (it as JsonNumber).value }

        fun presetRowOf(row: Row): PresetRow = PresetRow(
            id = str(row["id"]) ?: "",
            desiredRetention = (row["desired_retention"] as? JsonNumber)?.value ?: 0.9,
            maximumIntervalDays = long(row["maximum_interval_days"])?.toInt() ?: 36500,
            learningStepsSeconds = doubles(row["learning_steps_s"])?.map { it.toInt() } ?: listOf(60, 600),
            relearningStepsSeconds = doubles(row["relearning_steps_s"])?.map { it.toInt() } ?: listOf(600),
            fuzz = (row["fuzz"] as? JsonBool)?.value ?: true,
            easyDays = doubles(row["easy_days"]) ?: List(7) { 1.0 },
            fsrsVersion = str(row["fsrs_version"]),
            weights = doubles(row["weights"]),
            weightsVersion = long(row["weights_version"])?.toInt() ?: 0,
            deleted = isDeleted(row),
        )
    }
}
