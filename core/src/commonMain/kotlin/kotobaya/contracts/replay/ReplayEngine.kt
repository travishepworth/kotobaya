package kotobaya.contracts.replay

import kotobaya.contracts.HlcString
import kotobaya.contracts.InstantMs
import kotobaya.contracts.Uuid
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.fsrs.SchedulerParams

/**
 * Position of a review in replay order (SYNC.md 9.1): `(reviewed_at, hlc, id)`, compared
 * lexicographically — `reviewedAt` numerically, then `hlc` byte-wise, then `id` byte-wise. All three
 * are written once and stored identically on every replica. Note: `hlc` here is
 * `review_log.hlc` (= `replay_hlc` for imports), not necessarily the op's envelope HLC.
 */
public data class ReplayKey(
    /** `review_log.reviewed_at`. */
    public val reviewedAt: InstantMs,
    /** `review_log.hlc`. */
    public val hlc: HlcString,
    /** `review_log.id`. */
    public val id: Uuid,
)

/** The `review_log` columns that replay reads. One instance per row. */
public data class ReplayReview(
    /** `id`. */
    public val id: Uuid,
    /** `card_id` as stored (may be a merge loser of the card being replayed). */
    public val cardId: Uuid,
    /** `hlc` (replay position). */
    public val hlc: HlcString,
    /** `reviewed_at`. */
    public val reviewedAt: InstantMs,
    /** `utc_offset_min`; null = unknown. */
    public val utcOffsetMin: Int?,
    /** `review_type`. */
    public val reviewType: ReviewType,
    /** `rating`; null iff [reviewType] is MANUAL. */
    public val rating: Rating?,
    /** `manual_action`; non-null iff [reviewType] is MANUAL. */
    public val manualAction: ManualAction?,
    /** `manual_due_at`; non-null iff [manualAction] is SET_DUE. */
    public val manualDueAt: InstantMs?,
    /** `undone_hlc`; non-null = undone (not in any replay set). */
    public val undoneHlc: HlcString?,
    /**
     * `import_source`: null for a native review, `"jpdb"` for imported jpdb history (server-minted,
     * OPS.md `review.logged`). Read by the import-graduation rule of SYNC.md 9.5 (BUG-6). Default null,
     * so a caller that does not pass it gets the native semantics; **every replica that maintains card
     * caches MUST pass the stored column**, or its caches diverge from the other replicas' (SYNC.md 11).
     */
    public val importSource: String? = null,
) {
    /** This row's [ReplayKey]. */
    public val key: ReplayKey get() = ReplayKey(reviewedAt, hlc, id)
}

/**
 * A card's whole cache: [schedule] plus the replay bookkeeping columns.
 * Never travels in ops (SYNC.md 9).
 */
public data class CardCache(
    /** `state … lapses`. */
    public val schedule: CardSchedule,
    /** `card.last_review_id`: id of the last row folded (rated or manual); null if none. */
    public val lastReviewId: Uuid?,
    /** `card.weights_version`: the preset's `weights_version` this cache was computed with. */
    public val weightsVersion: Int,
)

/** What to do after a `review.logged` row has been inserted (SYNC.md 9.2, OPS.md `review.logged`). */
public enum class ReplayDecision {
    /** Nothing: the row is not schedule-feeding (drill/cram), or is undone, or is the row already last folded. */
    NONE,

    /** Fold the row into the current cache with [ReplayEngine.fold]. */
    FOLD,

    /** Recompute the cache from the whole replay set with [ReplayEngine.replay]. */
    FULL_REPLAY,
}

/** Result of applying a `review.undone` to a `review_log` row ([ReviewLogRules.undo]). */
public data class UndoOutcome(
    /** True iff the row is written: `undone_hlc` was null or the op's hlc is smaller. */
    public val apply: Boolean,
    /** The new `undone_hlc` (= op hlc) when [apply], else the stored value unchanged. */
    public val undoneHlc: HlcString?,
    /** The new `undone_at` (= wall(op hlc)) when [apply], else the stored value unchanged. */
    public val undoneAt: InstantMs?,
    /**
     * True iff the row went from not undone to undone **and** its review type feeds the schedule:
     * the target card (the row's card, or its survivor) needs a full replay. Lowering an existing
     * `undone_hlc` never triggers a replay.
     */
    public val replayTarget: Boolean,
)

/**
 * Computes card caches (SYNC.md 9). Pure: a cache is a function of (replay set in 9.1 order,
 * effective preset parameters, core version) and nothing else — no clock, no other cards.
 *
 * **Replay set** of card C (built by the caller from its tables, SYNC.md 9.1): every `review_log`
 * row with `card_id = C` **or** `card_id` = a card whose `merged_into_id = C` (merges are flattened,
 * so one hop covers multi-level merges such as scenario `merge-two-level-cards`), `undone_at IS NULL`,
 * `review_type IN (scheduled, learning, manual)`. The engine re-checks the last two conditions and
 * sorts by [ReplayKey] itself, so a caller may pass the rows unsorted and unfiltered by type/undo.
 *
 * **Effective preset** (caller's job): the row `C.preset_id` points at, or the Default preset
 * (`Reserved.DEFAULT_PRESET_ID`) if that row is tombstoned; turned into [SchedulerParams] by
 * `Scheduler.paramsOf`.
 *
 * **Invariant (fold = replay):** for every replay set S sorted by key and every row r that sorts
 * after all of S, `fold(replay(S, p), r, p) == replay(S + r, p)` exactly (bit-identical doubles).
 * This holds under C-1b too: a manual row leaves `lastReviewAt` unchanged in both paths.
 *
 * **Import graduation (SYNC.md 9.5, BUG-6):** a rated row with [ReplayReview.importSource] `"jpdb"`
 * and rating HARD, GOOD or EASY, folded into a schedule in state LEARNING or RELEARNING whose
 * `lastReviewAt` is non-null and at least `Reserved.DAY_MS` before the row's `reviewedAt`, is folded
 * with `Scheduler.review(before, row, params.copy(learningStepsSeconds = emptyList(),
 * relearningStepsSeconds = emptyList()))`: FSRS's own memory-state update for that rating and elapsed
 * time, and the card graduates to REVIEW (interval from the new stability, fuzz seeded by the row id)
 * instead of repeating a (re)learning step. Every other row (native rows, AGAIN, a gap under one day,
 * NEW/REVIEW states) is folded with the unmodified params. Pure in (schedule before, row, params), so
 * the fold = replay invariant holds unchanged.
 *
 * **Cross-platform invariant:** `replay` and `fold` give bit-identical doubles on jvm, android and js
 * for the same inputs (SYNC.md 11.3 convergence). They add no arithmetic of their own beyond
 * `Scheduler.review`/`Scheduler.manual` (the import-graduation test above is one Long subtraction
 * and comparison); the [kotobaya.contracts.fsrs.PortableMath] rules and the ban
 * on kotlin.math transcendentals apply to this implementation as well.
 *
 * **Core version (C-1c):** [schedulerVersion] identifies the algorithm. A device stores the version it
 * last replayed with in `sync_state` key `core_scheduler_version` (SYNC.md 10.5; the server in the
 * place SYNC.md 10.5 names). When it differs from [schedulerVersion], the replica fully replays every
 * live, non-loser card **before folding anything**, then stores the new version.
 */
public interface ReplayEngine {
    /**
     * Opaque version of the replay algorithm, e.g. `"fsrs6-2"` (`"fsrs6-1"` before the import-graduation
     * rule of SYNC.md 9.5, BUG-6). Changes whenever any output for any input could change.
     */
    public val schedulerVersion: String

    /** Total order of SYNC.md 9.1: negative if `a` sorts first. */
    public fun compare(a: ReplayKey, b: ReplayKey): Int

    /**
     * Full replay: start from [CardSchedule.NEW]; for each row in 9.1 order fold it
     * (rated → `Scheduler.review`, with the import-graduation params of SYNC.md 9.5 where that rule
     * applies; manual → `Scheduler.manual`). Elapsed time for a rated row is
     * measured from the previous **rated** row's `reviewed_at` (`CardSchedule.lastReviewAt`, C-1b;
     * manual rows do not move it), clamped to `>= 0`.
     *
     * Result: `lastReviewId` = id of the last row (null if the set is empty); `weightsVersion =
     * params.weightsVersion` **always** (a full replay makes the cache current). An empty set, or
     * one ending in a RESET, gives state NEW and `dueAt = null` (SYNC.md 9.3).
     *
     * @throws IllegalArgumentException if two schedule-feeding, not-undone rows in [reviews] share an
     *   `id` (a replay set never holds one review twice; the caller passes each `review_log` row once;
     *   rows the engine filters out are not checked), or if such a row violates the `review_log` CHECKs: a rated row (SCHEDULED/LEARNING) without
     *   [ReplayReview.rating], a MANUAL row without [ReplayReview.manualAction], or a SET_DUE row
     *   without [ReplayReview.manualDueAt]. Such rows cannot come from a database that enforces
     *   `V1__init.sql`; the exception signals a caller bug. **Caller duty on refusal (SYNC.md 9.4,
     *   C-8; server and devices alike):** write the refused cache
     *   `CardCache(CardSchedule.NEW, Reserved.REPLAY_REFUSED_ID, params.weightsVersion)` in the same
     *   transaction, log the card at ERROR, and let the triggering op / page / startup replay commit.
     *   Never keep the previous cache, never fail the push.
     */
    public fun replay(reviews: List<ReplayReview>, params: SchedulerParams): CardCache

    /**
     * Incremental fold of one row into [cache]. Precondition: [decide] returned [ReplayDecision.FOLD]
     * for this row against this cache. Result: `lastReviewId = review.id`,
     * `weightsVersion = params.weightsVersion`.
     *
     * @throws IllegalArgumentException if the row is not schedule-feeding or is undone, or if
     *   `cache.weightsVersion != params.weightsVersion` (folding into a stale cache would make it
     *   permanently differ from a replica that replayed first, SYNC.md 9.3). A refusal for a row that
     *   violates the `review_log` CHECKs is handled like a refused [replay] (SYNC.md 9.4: write the
     *   refused cache). Calling it against a refused cache ([CardCache.lastReviewId] =
     *   `Reserved.REPLAY_REFUSED_ID`) is a precondition violation: [decide] never answers FOLD for one.
     */
    public fun fold(cache: CardCache, review: ReplayReview, params: SchedulerParams): CardCache

    /**
     * Decision for a `review.logged` row just inserted for target card T (the row's card, or its
     * survivor), in this order:
     * 1. not schedule-feeding, or undone → [ReplayDecision.NONE];
     * 1b. `cache.lastReviewId == Reserved.REPLAY_REFUSED_ID` (a refused cache, SYNC.md 9.4; C-8) →
     *    [ReplayDecision.FULL_REPLAY], before any lookup, so not even a row whose id equals the marker
     *    can be folded into it;
     * 2. `cache.weightsVersion != params.weightsVersion` → [ReplayDecision.FULL_REPLAY];
     * 3. `cache.lastReviewId == null` → [ReplayDecision.FOLD];
     * 4. [lastFolded] is null (the row named by `lastReviewId` is not present locally, e.g. mid-rebuild)
     *    → [ReplayDecision.FULL_REPLAY];
     * 5. `review.id == cache.lastReviewId` → [ReplayDecision.NONE];
     * 6. `compare(review.key, lastFolded) > 0` → [ReplayDecision.FOLD], else [ReplayDecision.FULL_REPLAY]
     *    (out-of-order arrival, a late review with an earlier `reviewed_at`, a re-minted review).
     *
     * [lastFolded] is the [ReplayKey] of the row whose id is `cache.lastReviewId`, looked up by the caller.
     * A device answering a card uses the same decision for the review it mints (SYNC.md 9.3).
     * Caches of tombstoned cards and merge losers need not be maintained; the caller may skip them.
     */
    public fun decide(
        cache: CardCache,
        lastFolded: ReplayKey?,
        review: ReplayReview,
        params: SchedulerParams,
    ): ReplayDecision
}

/**
 * The device-side append-only rules for `review_log` (SYNC.md 7.9), which Postgres enforces by
 * trigger and a device's apply code MUST enforce itself. Pure decisions; the caller executes them.
 *
 * Statements a device may issue on `review_log`, and no others:
 * `INSERT` of a new id (rule 1); one `UPDATE … SET undone_hlc = ?, undone_at = ?` when [undo] says
 * apply (rule 3); the full wipe at the start of a rebuild (rule 5, SYNC.md 10.3).
 */
public interface ReviewLogRules {
    /**
     * Rule 1: whether `review.logged` inserts. True iff no row with this id exists ([existing] is
     * false). If a row exists — even with different content — nothing is written and no replay is
     * triggered.
     */
    public fun shouldInsert(existing: Boolean): Boolean

    /**
     * Rules 3–4 (**earliest undo wins**): given the stored `undone_hlc` / `undone_at` of the row and
     * the undo op's hlc: apply iff `storedUndoneHlc == null || opHlc < storedUndoneHlc`
     * (byte-wise). Never clears, never raises. [rowFeedsSchedule] is the row's
     * `review_type.feedsSchedule`.
     *
     * Required tests (SYNC.md 7.9): duplicate insert with different content keeps the first; undo
     * A(h=20) then B(h=15) → 15; B then A → 15; the same undo twice → unchanged, second apply false;
     * undo for an unknown review id → the caller writes nothing and raises no error (device);
     * scenario `undo-on-two-devices`.
     */
    public fun undo(
        storedUndoneHlc: HlcString?,
        storedUndoneAt: InstantMs?,
        opHlc: HlcString,
        opWallMs: InstantMs,
        rowFeedsSchedule: Boolean,
    ): UndoOutcome
}
