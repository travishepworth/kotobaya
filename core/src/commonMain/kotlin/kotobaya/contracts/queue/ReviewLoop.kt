package kotobaya.contracts.queue

import kotobaya.contracts.InstantMs

/**
 * ADR-037: where the review loop stands for one [QueueInput]. Decided only by [ReviewLoop.evaluate];
 * server, phone local API, native widget/notifications, web and CLI render it and never derive it.
 *
 * Wire: [wire] is the value of the API's `ReviewQueue.loop.phase`. The set is **open for readers**:
 * a client that meets an unknown value treats it as `reviewing` when the response's `items` is
 * non-empty and as `empty` otherwise. Example: `LoopPhase.GOAL_REACHED.wire == "goal_reached"`.
 */
public enum class LoopPhase(
    /** Wire value (lower snake case), unique per entry. Example: `"done_for_today"`. */
    public val wire: String,
) {
    /**
     * The served queue ([LoopResult.queue]) is non-empty (`items` or `toMaterialize`): there is a
     * card to show now, or one will exist after materialization (C-27). Example: A1 below.
     */
    REVIEWING("reviewing"),

    /**
     * Only without keep going and only with `limits.reviewsUnlimited == true`: nothing is ready
     * under the limits, and a keep-going build has at least one card. In practice the day's new
     * goal is reached (or no new group fits it). Keep going always yields a card. Example: A3 below.
     */
    GOAL_REACHED("goal_reached"),

    /**
     * Only without keep going and only with `limits.reviewsUnlimited == false`: nothing is ready
     * under the limits, and a keep-going build has at least one card (backlog beyond the review
     * cap and/or new cards). Chosen by the setting, not by which cap bound (B7). Example: B3 below.
     */
    DONE_FOR_TODAY("done_for_today"),

    /**
     * Nothing is ready, keep going would not help (or is already on and empty), and at least one
     * learning/relearning card falls due later today: resume at [LoopState.nextDueAt]. Happens only
     * when no new card is left anywhere (B6). Example: keep going, no backlog, no new, 8 learning
     * cards due later → `WAITING`.
     */
    WAITING("waiting"),

    /** Nothing is ready, nothing more with keep going, nothing later today. The day is done. */
    EMPTY("empty"),
}

/**
 * The pre-ADR-037 wire counts, kept with their **frozen** meaning for old clients (old CLI daemon,
 * older web bundle). New clients never read them. The API sends them as the deprecated
 * `ReviewQueue.counts` / `more_available` and `StatsSummary.due` / `more_available`.
 *
 * Example (A1 below): `LegacyCounts(new = 40, learning = 26, review = 0, moreAvailable = false)`.
 */
public data class LegacyCounts(
    /**
     * New cards still to introduce today under the day goal and the per-deck caps, **not** bounded
     * by the batch (frozen: old clients show it as "new left today"): the NEW items plus
     * `toMaterialize.size` of the unbounded normal build N∞ ([ReviewLoop] "Evaluation"). `>= 0`.
     * Example: goal 40, 12 introduced, batch 10, 100 new cards → 28 (not 10); 3 new cards exist → 3.
     */
    public val new: Int,
    /**
     * `readyLearning + laterToday`: learning/relearning cards due before the day ends, ready or not.
     * This is the old over-count (ADR-033) the loop replaces; it is kept only so old clients see what
     * they always saw. `>= 0`. Example: 0 ready, 26 later → 26.
     */
    public val learning: Int,
    /** Equal to [LoopState.readyReviews]. `>= 0`. Example: 335 due, cap 200, none done → 200. */
    public val review: Int,
    /**
     * `ReviewQueue.moreAvailable` of the normal build N: N is empty (`items` and `toMaterialize`)
     * and the keep-going build K is not. Independent of `input.limits.keepGoing`, so
     * it can be true in a keep-going evaluation (the API still sends `more_available: false` for a
     * keep-going request; that is the server's mapping, see openapi). Example: B3 → true.
     */
    public val moreAvailable: Boolean,
)

/**
 * The loop's numbers for one [QueueInput] (ADR-037). Every field except [phase] and [keepGoing] is
 * computed from the normal build N, the keep-going build K, N∞ (legacy only) and `input.today`, so it is
 * **identical with and without keep going** (same input otherwise): every client shows the same
 * numbers at the same instant whatever mode it is in.
 *
 * Invariants (property-tested by LOOP-CORE):
 * - `readyLearning, readyReviews, laterToday, backlog, backlogBeyondLimit, reviewsToday, newToday, newGoal >= 0`;
 * - `backlog >= readyReviews`; `backlogBeyondLimit == backlog - readyReviews`; it is 0 when
 *   `reviewsLimit == null`;
 * - `nextDueAt == null` iff `laterToday == 0`; otherwise `now < nextDueAt < day.endMs`;
 * - `phase == REVIEWING` iff the served queue is non-empty;
 * - `phase` in {`GOAL_REACHED`, `DONE_FOR_TODAY`} implies `!keepGoing` and K non-empty;
 * - retired cards (rule 0) are in no count.
 *
 * Example (B3 below): `phase = DONE_FOR_TODAY, keepGoing = false, readyLearning = 0, readyReviews = 0,
 * laterToday = 0, nextDueAt = null, backlog = 135, backlogBeyondLimit = 135, reviewsToday = 200,
 * reviewsLimit = 200, newToday = 40, newGoal = 40, newAvailable = true`.
 */
public data class LoopState(
    /** The phase (rule in [ReviewLoop]). */
    public val phase: LoopPhase,
    /** Echo of `input.limits.keepGoing`; besides the data, the only input [phase] depends on. */
    public val keepGoing: Boolean,
    /**
     * **Ready learning**: eligible (rules 0–1) LEARNING/RELEARNING cards with `dueAt <= now` (a null
     * `dueAt` counts as due). Never capped, never served early. Equals the LEARNING items of N (and
     * of K). Example: 2 relearning cards whose 10-minute step elapsed → 2.
     */
    public val readyLearning: Int,
    /**
     * **Ready reviews**: the REVIEW items of N, i.e. eligible REVIEW cards with `dueAt < day.endMs`
     * (day granularity; overdue included) within the remaining review budget (global and per deck;
     * no budget when `reviewsUnlimited`), at most one per note when burying. "Ready now" =
     * `readyLearning + readyReviews`; new cards are not part of it. Example: 335 due, cap 200, 0
     * done → 200; cap 200, 200 done → 0; `reviews_per_day = 0` → 0.
     */
    public val readyReviews: Int,
    /**
     * **Later today**: eligible LEARNING/RELEARNING cards with `now < dueAt < day.endMs`. A hint
     * only: they are not ready and never make the loop wait while anything else can be shown.
     * Example: 26 cards in a learning step that has not elapsed → 26.
     */
    public val laterToday: Int,
    /** The smallest `dueAt` among the [laterToday] cards; null iff `laterToday == 0`. Example: `now + 300_000`. */
    public val nextDueAt: InstantMs?,
    /**
     * **Backlog**: every due review an uncapped build would take = the REVIEW items of K.
     * `>= readyReviews`; equal when `reviewsUnlimited`. Example: 335 due, cap 200 → 335.
     */
    public val backlog: Int,
    /** `backlog - readyReviews`: due reviews the caps hold back. 0 when `reviewsUnlimited`. Example: 135. */
    public val backlogBeyondLimit: Int,
    /** Today's `countsAsReview` reviews in `input.today` (after rule 0). Keep-going reviews count. Example: 200. */
    public val reviewsToday: Int,
    /**
     * `max(0, limits.reviewsPerDay)`, or null when `limits.reviewsUnlimited` (no cap at all). The
     * global setting only; per-deck caps are not shown here. Example: 200; unlimited → null.
     */
    public val reviewsLimit: Int?,
    /**
     * Today's `introducedNew` reviews in `input.today` (after rule 0). May exceed [newGoal] after
     * keep going. Example: 12; after 60 keep-going introductions past a goal of 40 → 100.
     */
    public val newToday: Int,
    /** `max(0, limits.newPerDay)` (`scheduler.new_per_day`). Example: 40. */
    public val newGoal: Int,
    /**
     * True iff K contains at least one NEW item or one `toMaterialize` entry: a new card exists that
     * keep going could introduce, ignoring the day goal and per-deck new caps (disabled decks,
     * KNOWN/excluded words and sibling burying still apply). Example: A3 → true; B6 → false.
     */
    public val newAvailable: Boolean,
    /** The frozen pre-ADR-037 counts for old clients. */
    public val legacy: LegacyCounts,
)

/**
 * What [ReviewLoop.evaluate] returns.
 *
 * Example (B4 below): `queue.items` = due learning, then the 135 backlog reviews by `(dueAt, cardId)`,
 * then up to a batch of NEW; `state.phase = REVIEWING`.
 */
public data class LoopResult(
    /**
     * The build to **serve**: K when `input.limits.keepGoing`, else N. Callers show its `items` and
     * materialize its `toMaterialize` (C-27) and **nothing else**: the `toMaterialize` of the build
     * that is not served (K during a normal request) MUST NOT be materialized, or a count-only
     * request would create notes past the day's goal.
     */
    public val queue: ReviewQueue,
    /** The loop's phase and numbers for the same input. */
    public val state: LoopState,
)

/**
 * ADR-037: the one review loop. Pure and deterministic (same input → same result on jvm, android
 * and js); implementation `kotobaya.queue.DefaultReviewLoop` (ticket LOOP-CORE). Every caller that
 * shows a queue, a count or an end state (server `getReviewQueue` / `getStatsSummary`, the phone's
 * local API, widget and notifications) calls this and renders the result; nobody else counts cards
 * or decides a phase.
 *
 * **Evaluation.** With `input0` = [input] where `limits.learnAheadMs` is replaced by `0` (a value
 * `> 0` is ignored: the loop never serves a learning card early):
 * - N = `QueueBuilder.build(input0 with limits.keepGoing = false)` (the normal build);
 * - K = `QueueBuilder.build(input0 with limits.keepGoing = true)` (the keep-going build, rule 6);
 * - both always, both from the same loaded input, same `keepGoingNewBatch`;
 * - N∞ = `QueueBuilder.build(input0 with limits.keepGoing = false, keepGoingNewBatch = Int.MAX_VALUE)`,
 *   used **only** for [LegacyCounts.new] (an implementation may take the number from N's own
 *   unbounded draw, rule 4 `availableToday`, as long as the result is the same); it is never
 *   served and its `toMaterialize` is never materialized;
 * - `queue` = K if `input.limits.keepGoing` else N;
 * - counts as documented on [LoopState] and [LegacyCounts].
 *
 * **Phase.** S = `queue`; "non-empty" = `items` or `toMaterialize` non-empty. First match wins:
 * 1. S non-empty → [LoopPhase.REVIEWING].
 * 2. else if `!keepGoing` and K non-empty → [LoopPhase.GOAL_REACHED] if `limits.reviewsUnlimited`
 *    else [LoopPhase.DONE_FOR_TODAY].
 * 3. else if `laterToday > 0` → [LoopPhase.WAITING] (resume at `nextDueAt`).
 * 4. else [LoopPhase.EMPTY].
 *
 * Consequences: `WAITING` happens only when nothing is ready and no new card is left anywhere;
 * `GOAL_REACHED` / `DONE_FOR_TODAY` always have a Keep going that yields at least one card (never
 * a dead end); a learning card that comes due makes N non-empty, so the phase is `REVIEWING` again.
 *
 * **Order of what is served** is the builder's (rule 5 for N: due learning, then ready reviews and
 * new cards in the proportional mix; rule 6 for K: due learning, reviews, new). A card that came
 * due is therefore always at the front of the next evaluation.
 *
 * **Edge cases** (each is a LOOP-CORE test):
 * - Goal 39/40 and only a 3-card add_kanji group left: the group does not fit the day (rule 4,
 *   atomic), N has no new → phase 2 at 39/40; `newToday = 39`, `newGoal = 40` (UIs show the real numbers).
 * - Per-deck new caps bind before the goal: same rule (N has no new, K has → phase 2).
 * - New pool empty before the goal and backlog beyond the cap → `DONE_FOR_TODAY` (K non-empty through its reviews).
 * - `new_per_day = 0` with new cards: phase 2 as soon as the ready reviews are done.
 * - `reviews_per_day = 0` (limited): `readyReviews = 0`, `backlog` = all due reviews, `reviewsLimit = 0`.
 * - A learning card falls due during `GOAL_REACHED` / `DONE_FOR_TODAY`: `REVIEWING` with
 *   `readyLearning = 1`; after it is answered the phase is as before.
 * - Day rollover: everything is recomputed from the new `input.day` and `input.today` (empty at
 *   the start of the day); nothing carries over, including a caller's keep-going flag (rule 6 Scope).
 * - Retired cards (rule 0, ADR-035) are in no count and no item.
 * - A read-only caller that cannot materialize: `toMaterialize` non-empty still counts as
 *   non-empty → `REVIEWING` (C-27; the API then reports `to_materialize > 0` with possibly empty `items`).
 * - Empty input (no candidates, no pending entries): all counts 0, `nextDueAt = null`, `EMPTY`.
 * - Duplicate candidates or `today` rows are the loader's bug; the loop does not deduplicate.
 * - Candidate order, deck order and `today` order do not change the result.
 *
 * **Worked examples.** Settings unless stated: `reviewsPerDay` 200, `newPerDay` 40, batch 10,
 * `burySiblings` true; "A" = `reviewsUnlimited` true, "B" = false.
 * - **A1** (A; 0 ready, 26 learning cards due later today, 100 new cards, 0 introduced today):
 *   N = 10 NEW → `REVIEWING`; `readyLearning 0, readyReviews 0, laterToday 26, nextDueAt` = their
 *   earliest due, `backlog 0/0, reviewsToday 0, reviewsLimit null, newToday 0, newGoal 40,
 *   newAvailable true`; `legacy = (new 40, learning 26, review 0, moreAvailable false)`. Never `WAITING`.
 * - **B3** (B; 200 reviews done today, 40 introduced, 135 due reviews left, new cards remain):
 *   N empty, K non-empty → `DONE_FOR_TODAY`; `readyReviews 0, backlog 135, backlogBeyondLimit 135,
 *   reviewsToday 200, reviewsLimit 200, newToday 40, newGoal 40, newAvailable true`;
 *   `legacy = (0, 0, 0, moreAvailable true)`.
 * - **B4** (B3 with `keepGoing = true`): `queue` = K = due learning, then the 135 backlog reviews by
 *   `(dueAt, cardId)`, then up to 10 NEW (no NEW before the last backlog review) → `REVIEWING`,
 *   `keepGoing true`; every other [LoopState] field equals B3's.
 * - **A3** (A; 40/40 introduced, no due reviews, new cards remain): N empty, K = 10 NEW →
 *   `GOAL_REACHED`. With `keepGoing = true` → `REVIEWING`, items NEW; after 60 more introductions
 *   still `REVIEWING` with `newToday 100, newGoal 40` (no end while new cards exist).
 */
public interface ReviewLoop {
    /**
     * Evaluates the loop for [input] as the interface documents. Never throws for well-formed
     * input; negative limits count as 0, `keepGoingNewBatch < 1` as 1.
     */
    public fun evaluate(input: QueueInput): LoopResult
}
