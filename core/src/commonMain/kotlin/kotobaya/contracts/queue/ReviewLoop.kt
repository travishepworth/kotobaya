package kotobaya.contracts.queue

import kotobaya.contracts.InstantMs

/**
 * ADR-037, phase order amended by ADR-049: where the review loop stands for one [QueueInput].
 * Decided only by [ReviewLoop.evaluate]; server, phone local API, native widget/notifications, web
 * and CLI render it and never derive it.
 *
 * ADR-049 in one line: the stop screens ([GOAL_REACHED], [DONE_FOR_TODAY], [EMPTY]) appear only
 * when **nothing is left for today**: no card is ready and no learning card falls due before the
 * day ends. While a learning card is still on its timer the phase is [WAITING] (or [REVIEWING]).
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
     * The served queue ([LoopResult.queue]) is non-empty (`items`, `toMaterialize` or
     * `kanjiToMaterialize`): there is a card to show now, or one will exist after materialization
     * (C-27, ADR-040). Example: A1 below.
     */
    REVIEWING("reviewing"),

    /**
     * Stop screen. Only with `limits.reviewsUnlimited == true`: the served queue is empty,
     * **no learning card falls due later today** (`laterToday == 0`, ADR-049), and the unbounded
     * keep-going build K∞ has at least one card. Without keep going that is the old meaning
     * ("nothing is ready under the limits"); C-31 (ADR-052): it is also reached **with** keep
     * going when a `keepGoingNewTarget` is given and has been reached while new cards remain
     * (the batch is done; W3 below). With keep going and no target it cannot occur. In practice the day's new goal is
     * reached (or no new group fits it) and every card studied today has left its learning steps
     * for today. Keep going always yields a card. Example: A3 below; W1 step 4.
     */
    GOAL_REACHED("goal_reached"),

    /**
     * Stop screen. Only with `limits.reviewsUnlimited == false`:
     * the served queue is empty, **no learning card falls due later today**
     * (`laterToday == 0`, ADR-049), and the unbounded keep-going build K∞ has at least one card
     * (backlog beyond the review cap and/or new cards; with keep going and a reached
     * `keepGoingNewTarget`: new cards, C-31). Chosen by the setting, not by which cap bound (B7).
     * Example: B3 below.
     */
    DONE_FOR_TODAY("done_for_today"),

    /**
     * ADR-049: the served queue is empty and at least one learning/relearning card falls due later
     * today (`laterToday > 0`): show a countdown to [LoopState.nextDueAt] and resume then. Checked
     * **before** the stop screens, with and without keep going, whether or not new cards or a
     * backlog remain; [LoopState.keepGoingAvailable] says whether a Keep going button is offered
     * while waiting. Never served early (no learn-ahead, ADR-033).
     * Example: W1 step 2 (goal 40/40, 14 learning cards on their timers, 707 new cards) →
     * `WAITING`, `keepGoingAvailable = true`. Example: B6 (keep going, no backlog, no new, 8
     * learning cards due later) → `WAITING`, `keepGoingAvailable = false`.
     */
    WAITING("waiting"),

    /**
     * Stop screen. Nothing is ready, nothing more with keep going, nothing later today. The day
     * is done and there is no Keep going. C-31 (ADR-051): exactly when K∞ is empty and
     * `laterToday == 0`; it is the **only** phase besides `REVIEWING` that never offers Keep
     * going. Example: B6 once the 8 cards have graduated.
     */
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
     * Kanji cards served ahead of their word are NEW items and count; a unit withheld for a
     * missing kanji (ADR-040) is not counted until it is materialized. New clients use
     * [LoopState.untilStop] instead.
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
     * `ReviewQueue.moreAvailable` of the normal build N: N is empty (`items`, `toMaterialize` and
     * `kanjiToMaterialize`) and the keep-going build K is not. Independent of `input.limits.keepGoing`, so
     * it can be true in a keep-going evaluation (the API still sends `more_available: false` for a
     * keep-going request; that is the server's mapping, see openapi). Example: B3 → true.
     */
    public val moreAvailable: Boolean,
)

/**
 * ADR-049 (additive): [LoopState.untilStop] split into the four numbers the review header and Home
 * show, so that the header adds up to the progress bar's remainder and no client does arithmetic.
 * Wire: `ReviewQueue.loop.until_stop_parts`.
 *
 * Invariants (property-tested): every member `>= 0`; `learning + later + review + new ==
 * LoopState.untilStop`; `learning == LoopState.readyLearning`; `later == LoopState.laterToday`.
 * Like [LoopState.untilStop] it follows the mode: [review] and [new] differ with keep going.
 *
 * Example (W1 step 1, the ADR-049 case): `UntilStopParts(learning = 9, later = 14, review = 0,
 * new = 0)`, sum 23 = `untilStop`. Example (A1): `(0, 26, 0, 40)`, sum 66. Example (B4, keep
 * going, batch 10): `(0, 0, 135, 10)`, sum 145. Example (B3, a stop screen): `(0, 0, 0, 0)`.
 */
public data class UntilStopParts(
    /**
     * Learning/relearning cards that can be answered now: always [LoopState.readyLearning].
     * `>= 0`. Example: 9.
     */
    public val learning: Int = 0,
    /**
     * Learning/relearning cards on their timer that come back before the day ends: always
     * [LoopState.laterToday]. `>= 0`. Example: 14.
     */
    public val later: Int = 0,
    /**
     * Reviews still to answer in this mode: [LoopState.readyReviews] without keep going,
     * [LoopState.backlog] with keep going (the caps are lifted, so the whole backlog is in sight).
     * `>= 0`. Example: B3 → 0; B4 (the same data with keep going) → 135.
     */
    public val review: Int = 0,
    /**
     * New cards still to introduce in this mode: without keep going rule 4's `availableToday` of
     * the normal build N (left to today's goal under the per-deck caps, not bounded by the batch,
     * kanji cards of the units included); with keep going what rule 4 of K drew (`drawn`, about one
     * batch). C-31: with keep going **and** a `keepGoingNewTarget`, `availableToday` of that
     * build: the new cards left to the target, not bounded by the batch.
     * `>= 0`. Example: goal 40, 10 introduced, plenty of new cards → 30; goal met → 0;
     * keep going with batch 10 → 10; keep going with target 80, 47 introduced → 33.
     */
    public val new: Int = 0,
)

/**
 * The loop's numbers for one [QueueInput] (ADR-037). Every field except [phase], [keepGoing],
 * [keepGoingAvailable], [untilStop] and [untilStopParts] is computed from the normal build N, the
 * keep-going build K, N∞ (legacy only) and `input.today`, so it is **identical with and without
 * keep going** (same input otherwise): every client shows the same numbers at the same instant
 * whatever mode it is in. [untilStop] / [untilStopParts] are the progress bar and header of the
 * visit and follow the mode (ADR-038, ADR-049).
 *
 * Invariants (property-tested by LOOP-CORE):
 * - `readyLearning, readyReviews, laterToday, backlog, backlogBeyondLimit, reviewsToday, newToday, newGoal >= 0`;
 * - `backlog >= readyReviews`; `backlogBeyondLimit == backlog - readyReviews`; it is 0 when
 *   `reviewsLimit == null`;
 * - `nextDueAt == null` iff `laterToday == 0`; otherwise `now < nextDueAt < day.endMs`;
 * - `phase == REVIEWING` iff the served queue is non-empty;
 * - ADR-049: `phase == WAITING` iff the served queue is empty and `laterToday > 0` (in both modes);
 * - ADR-049, C-31: `phase` in {`GOAL_REACHED`, `DONE_FOR_TODAY`} implies K∞ non-empty,
 *   `laterToday == 0`, `nextDueAt == null`, `readyLearning == 0`, `readyReviews == 0` and
 *   `untilStop == 0`, and (`!keepGoing` or `limits.keepGoingNewTarget != null`);
 *   `phase == EMPTY` implies the same zeros and K∞ empty;
 * - C-31: `keepGoingAvailable == (served queue empty && K∞ non-empty)`; hence true in
 *   `GOAL_REACHED` / `DONE_FOR_TODAY`, false in `REVIEWING`, false in `EMPTY`. With
 *   `limits.keepGoingNewTarget == null` this is the pre-C-31 value `!keepGoing && N empty && K
 *   non-empty` (the served queue of a keep-going evaluation is then K∞ itself), so it is false
 *   whenever `keepGoing` there;
 * - retired cards (rule 0) are in no count;
 * - `untilStop >= 0`; without keep going `untilStop == readyLearning + laterToday + readyReviews +
 *   availableToday(N)`; `phase == REVIEWING` implies `untilStop >= 1`;
 *   `untilStop == 0` iff `phase` in {`GOAL_REACHED`, `DONE_FOR_TODAY`, `EMPTY`} (ADR-049: the bar
 *   is full exactly at a stop screen);
 * - `untilStopParts` sums to `untilStop`; its `learning == readyLearning`, `later == laterToday`,
 *   `review == (if (keepGoing) backlog else readyReviews)`;
 * - C-31: the invariants **I1 … I8** of [ReviewLoop] hold for every evaluation.
 *
 * Example (B3 below): `phase = DONE_FOR_TODAY, keepGoing = false, readyLearning = 0, readyReviews = 0,
 * laterToday = 0, nextDueAt = null, backlog = 135, backlogBeyondLimit = 135, reviewsToday = 200,
 * reviewsLimit = 200, newToday = 40, newGoal = 40, newAvailable = true, untilStop = 0,
 * keepGoingAvailable = true, untilStopParts = (0, 0, 0, 0)`.
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
     * **Later today**: eligible LEARNING/RELEARNING cards with `now < dueAt < day.endMs`. They
     * are not ready and are never served early. While the served queue is non-empty they are only
     * a number in the header; once it is empty they make the phase [LoopPhase.WAITING] and hold
     * back the stop screen (ADR-049). A learning card due at or after `day.endMs` is **not**
     * counted and does not hold the day open (it is ready learning of the next study day).
     * Suspended, buried and retired cards are not eligible and not counted.
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
     * True iff K contains at least one NEW item, one `toMaterialize` entry or one
     * `kanjiToMaterialize` literal: a new card exists that
     * keep going could introduce, ignoring the day goal and per-deck new caps (disabled decks,
     * KNOWN/excluded words and sibling burying still apply). Example: A3 → true; B6 → false.
     */
    public val newAvailable: Boolean,
    /** The frozen pre-ADR-037 counts for old clients. */
    public val legacy: LegacyCounts,
    /**
     * ADR-038 (additive): **answers left until the stop screen**. The review progress bar's total
     * is `done + untilStop`, `done` = the answers the client has sent in this visit. Each card
     * counts once, whatever number of steps it still has, so the total grows when a card comes
     * back: Again on a review moves it from [readyReviews] to [laterToday] (`untilStop` unchanged,
     * `done + 1`), and introducing a new card moves it from the new part to [laterToday].
     *
     * **Without keep going** (`keepGoing == false`): `readyLearning + laterToday + readyReviews +
     * newLeft`, with `newLeft` = rule 4's `availableToday` of the normal build N: the new cards
     * that can still be introduced today under the day goal and the per-deck caps, not bounded by
     * the batch, kanji cards of the units included (drawn, withheld and to be minted alike).
     * These are the answers before the stop screen (`GOAL_REACHED` / `DONE_FOR_TODAY` /
     * `EMPTY`). Learning cards due later today are included because they come back before the
     * day is done, and since ADR-049 the loop waits for them (`WAITING`) instead of stopping: so
     * during `WAITING` it equals [laterToday] (`>= 1`) and at a stop screen it is always 0.
     *
     * **With keep going** (`keepGoing == true`): the stream has no end, so it is what is in sight:
     * `readyLearning + laterToday + backlog + newBatch`, with `newBatch` = what rule 4 of the
     * keep-going build K drew (`drawn`: at most about `keepGoingNewBatch`, units whole). It
     * therefore depends on the batch (the API's `limit`) in this mode, and only in this mode.
     *
     * **With keep going and a target** (C-31; `keepGoing == true`, `limits.keepGoingNewTarget !=
     * null`): the stream has an end again, so `newBatch` is replaced by `availableToday` of the
     * served build (rule 6 "Target": the new cards left to the target that the pool can supply,
     * **not** bounded by the batch): `readyLearning + laterToday + backlog + newLeftToTarget`. It
     * counts down to 0 as the batch is worked off and is 0 exactly at the stop screen that
     * follows. Example (W3 step 2): `0 + 0 + 0 + 40 = 40`.
     *
     * The default `0` exists only so that existing callers compile; `ReviewLoop.evaluate` MUST set
     * it. `>= 0`.
     * Example (A1): 0 ready, 26 later, goal 40, 0 introduced, 100 new → `0 + 26 + 0 + 40 = 66`.
     * Example (B3): `0`; (B4, B3 with keep going, batch 10): `0 + 0 + 135 + 10 = 145`.
     * Example (goal 40, 10 introduced, 30 reviews due, 5 learning cards on a timer, plenty of new
     * cards): `0 + 5 + 30 + 30 = 65`; with only 12 new cards left in the decks: `47`.
     * Example (W1, ADR-049): goal met, 9 ready learning, 14 later → `9 + 14 + 0 + 0 = 23`.
     */
    public val untilStop: Int = 0,
    /**
     * ADR-049 (additive), redefined by C-31 (ADR-051): **pressing Keep going now would serve a
     * card now**. True iff the served queue is empty (`items`, `toMaterialize` and
     * `kanjiToMaterialize`) and the unbounded keep-going build K∞ is non-empty. "Pressing" means
     * a keep-going evaluation of the same input with the next target, `newToday + max(1,
     * newGoal)`; that build is non-empty exactly when K∞ is (it takes every due review, and
     * with `targetRemaining >= 1` it draws a new card when one exists, rule 6 "Target"), so
     * the definition needs no third build. Always true in `GOAL_REACHED` / `DONE_FOR_TODAY`; in
     * `WAITING` true when a backlog beyond the cap or a new card exists (the user may keep going
     * instead of watching the countdown; due learning cards still come first as they fall due)
     * and false otherwise; false in `REVIEWING` and in `EMPTY`. In a keep-going evaluation
     * **without** a target it is always false, as before (the served queue is K∞); **with** a
     * target it is true once the batch is done and new cards remain, in `WAITING` as well as at
     * the stop screen (before C-31 a `WAITING` reached while keeping going never offered the
     * button). Without keep going it still equals `legacy.moreAvailable`. Clients render the
     * button from this field only.
     *
     * The default `false` exists only so that existing callers compile; `ReviewLoop.evaluate` MUST
     * set it.
     * Example: W1 step 2 (waiting, 707 new cards) → true; B6 (waiting, nothing new) → false; W1
     * step 3 (the same instant with keep going) → false (a card is served); W3 step 4 (keep
     * going, target reached, new cards remain) → true.
     */
    public val keepGoingAvailable: Boolean = false,
    /**
     * ADR-049 (additive): [untilStop] split into learning ready / learning later today / reviews /
     * new, see [UntilStopParts]. The review header and Home show exactly these four numbers.
     *
     * The default (all 0) exists only so that existing callers compile; `ReviewLoop.evaluate` MUST
     * set it. Example: W1 step 1 → `(9, 14, 0, 0)`.
     */
    public val untilStopParts: UntilStopParts = UntilStopParts(),
)

/**
 * What [ReviewLoop.evaluate] returns.
 *
 * Example (B4 below): `queue.items` = due learning, then the 135 backlog reviews by `(dueAt, cardId)`,
 * then up to a batch of NEW; `state.phase = REVIEWING`.
 */
public data class LoopResult(
    /**
     * The build to **serve**: the keep-going build when `input.limits.keepGoing` (C-31: built
     * with `limits.keepGoingNewTarget`, so K_T; equal to K∞ when the target is null), else N. Callers show its `items` and
     * materialize its `toMaterialize` (C-27) and **nothing else**: the `toMaterialize` of the build
     * that is not served (K during a normal request) MUST NOT be materialized, or a count-only
     * request would create notes past the day's goal.
     */
    public val queue: ReviewQueue,
    /** The loop's phase and numbers for the same input. */
    public val state: LoopState,
)

/**
 * ADR-037, phase order amended by ADR-049: the one review loop. Pure and deterministic (same input → same result on jvm, android
 * and js); implementation `kotobaya.queue.DefaultReviewLoop` (ticket LOOP-CORE). Every caller that
 * shows a queue, a count or an end state (server `getReviewQueue` / `getStatsSummary`, the phone's
 * local API, widget and notifications) calls this and renders the result; nobody else counts cards
 * or decides a phase.
 *
 * **Evaluation.** With `input0` = [input] where `limits.learnAheadMs` is replaced by `0` (a value
 * `> 0` is ignored: the loop never serves a learning card early):
 * - N = `QueueBuilder.build(input0 with limits.keepGoing = false)` (the normal build);
 * - K = K∞ = `QueueBuilder.build(input0 with limits.keepGoing = true, keepGoingNewTarget = null)`
 *   (the unbounded keep-going build, rule 6). "K" in the text written before C-31 means K∞;
 * - C-31: K_T = `QueueBuilder.build(input0 with limits.keepGoing = true)` with the input's own
 *   `keepGoingNewTarget` (rule 6 "Target"); K_T = K∞ when the target is null. Built only when
 *   `input.limits.keepGoing` and the target is not null;
 * - N and K∞ always, all from the same loaded input, same `keepGoingNewBatch`;
 * - N∞ = `QueueBuilder.build(input0 with limits.keepGoing = false, keepGoingNewBatch = Int.MAX_VALUE)`,
 *   used **only** for [LegacyCounts.new] (an implementation may take the number from N's own
 *   unbounded draw, rule 4 `availableToday`, as long as the result is the same); it is never
 *   served and its `toMaterialize` is never materialized;
 * - `queue` = S = K_T if `input.limits.keepGoing` else N;
 * - counts as documented on [LoopState] and [LegacyCounts]. `backlog`, `newAvailable` and
 *   `legacy.moreAvailable` are always taken from K∞, never from K_T, so they stay identical
 *   with and without keep going and whatever the target is.
 *
 * **Phase (ADR-049 order).** S = `queue`; "non-empty" = `items`, `toMaterialize` or
 * `kanjiToMaterialize` non-empty. First match wins:
 * 1. S non-empty → [LoopPhase.REVIEWING].
 * 2. else if `laterToday > 0` → [LoopPhase.WAITING] (resume at `nextDueAt`), **with and without
 *    keep going, whatever K holds**.
 * 3. else if K∞ non-empty → [LoopPhase.GOAL_REACHED] if `limits.reviewsUnlimited`
 *    else [LoopPhase.DONE_FOR_TODAY]. (C-31: the condition `!keepGoing` was dropped. Without a
 *    target nothing changes: S is K∞ in a keep-going evaluation, so rule 1 has already matched
 *    whenever K∞ is non-empty. With a target this is the stop screen after the batch.)
 * 4. else [LoopPhase.EMPTY] (K∞ empty and `laterToday == 0`).
 *
 * (ADR-037 had rules 2 and 3 the other way round: the stop screen appeared while learning cards
 * were on their timers, 23 of them within 8 minutes in the report that led to ADR-049.)
 *
 * `keepGoingAvailable = S empty && K∞ non-empty` (C-31; before: `!keepGoing && N empty && K
 * non-empty`, which is the same value whenever the target is null).
 *
 * **What C-31 replaces of ADR-039 / ADR-037** (and nothing else): (1) ADR-039 "within one visit
 * it stays an endless stream" → with a target, one goal-sized batch per press, then the stop
 * screen again; (2) "`GOAL_REACHED` / `DONE_FOR_TODAY` only without keep going" → also with keep
 * going and a reached target; (3) "`keepGoingAvailable` is false in every keep-going
 * evaluation" → false only while a card is served or K∞ is empty; (4) A3 "no end while new
 * cards exist" → only without a target. ADR-039's visit scope (in memory, one visit, ended by
 * leaving, restart, day change or 10 minutes idle) is **kept** and now also bounds the target.
 * A caller that passes `keepGoingNewTarget = null` gets the pre-C-31 result bit for bit.
 *
 * **Invariants I1 … I8 (C-31, ADR-051; normative, the fuzz test `ReviewLoopInvariantFuzzTest`
 * asserts each after every step).** `ready` = `readyLearning + readyReviews`; "terminal" =
 * `phase` in {`GOAL_REACHED`, `DONE_FOR_TODAY`, `EMPTY`}; `parts` = `untilStopParts`.
 * - **I1** `ready > 0` ⇒ `phase == REVIEWING` and `queue.items` is non-empty. (A ready card is
 *   always served: a header that shows something to study never sits on an end screen.)
 * - **I2** terminal ⇒ `readyLearning == 0`, `readyReviews == 0`, `laterToday == 0`,
 *   `untilStop == 0` and every member of `parts` is 0.
 * - **I3** `parts.learning + parts.later + parts.review + parts.new == untilStop`, every member
 *   `>= 0`, `parts.learning == readyLearning`, `parts.later == laterToday`.
 * - **I4** `phase == EMPTY` ⇒ K∞ empty and `!keepGoingAvailable`; `phase` in {`WAITING`,
 *   `GOAL_REACHED`, `DONE_FOR_TODAY`} ⇒ `keepGoingAvailable == (K∞ non-empty)`;
 *   `phase == REVIEWING` ⇒ `!keepGoingAvailable`; `GOAL_REACHED` / `DONE_FOR_TODAY` ⇒
 *   `keepGoingAvailable`. **Never a dead end:** `keepGoingAvailable` ⇒ the keep-going
 *   evaluation of the same input with `keepGoingNewTarget = newToday + max(1, newGoal)` is
 *   `REVIEWING` (the press serves a card or has one to materialize).
 * - **I5** no LEARNING item of `queue.items` has `dueAt > now` (never served early); if
 *   `readyLearning > 0`, `queue.items[0]` is a LEARNING item and it is the eligible learning
 *   card with the smallest `(dueAt, cardId)`, **with and without keep going and whatever the
 *   target** (ADR-052 a: a card that came due is the head, with no lag); in a keep-going build
 *   no NEW item precedes a REVIEW item, except by the **open-unit exception to ADR-052 (a)**
 *   ([QueueBuilder] rule 6 "Target"): the remaining NEW cards of a half-answered kanji unit
 *   (rule 5 "Open group first") stay ahead of the reviews. The fuzz test asserts exactly this:
 *   every NEW item that precedes a REVIEW item belongs to the open group, and no NEW item ever
 *   precedes a LEARNING item.
 * - **I6** the presenter ([LoopPresenter], its own invariants P-I1 … P-I4).
 * - **I7** without keep going, and with keep going and a target: answering `queue.items[0]`
 *   with any rating, at the same `now`, and re-evaluating with the same limits gives
 *   `untilStop(after) <= untilStop(before) + 1` (the bar's total `done + untilStop` never
 *   jumps; the expected value is `<= untilStop(before)`, the `+ 1` is the asserted bound). Not
 *   asserted for keep going **without** a target (its batch-bounded new part is refilled
 *   after every answer and a kanji unit may overshoot the batch) and not across a day rollover.
 * - **I8** (ADR-052 b) `!keepGoing` ⇒ `|NEW items of queue.items| + |queue.toMaterialize| +
 *   |queue.kanjiToMaterialize| <= max(0, newGoal − newToday)` and `parts.new <= max(0, newGoal −
 *   newToday)`. **`kanjiToMaterialize` is included:** every literal in it is a kanji note the
 *   build will mint and introduce today (for a drawn deck entry or for a withheld word), so it
 *   is a new card that is "served or counted" exactly like a NEW item; a build that listed
 *   literals beyond the bound would mint notes past the goal. (`parts.new` is rule 4's
 *   `availableToday`, which already counts drawn, withheld and to-be-minted kanji cards alike.)
 *   Both hold
 *   whatever `keepGoingNewTarget` holds and whatever was introduced by keeping going earlier
 *   today; in particular both are 0 (and all three lists hold nothing new) once
 *   `newToday >= newGoal`. (`parts.new` equals that bound
 *   whenever the enabled decks can supply that many under their own limits; the fuzz test
 *   asserts the bound.) (One documented overshoot, excluded from the fuzz generator by using the
 *   built-in kanji type: the first-of-the-day kanji part of rule 4, ADR-044.) So after a
 *   keep-going session Home and the header show **no** new cards: an unfinished batch is not
 *   owed.
 *
 * Fuzz test shape (CORE-31; jvm + js, seeded, at least 2 000 runs of at most 60 steps): a random
 * input (cards in all four states with random dues around `now` and around `day.endMs`, pending
 * entries, limits incl. `newPerDay = 0` and `reviewsPerDay = 0`, both `reviewsUnlimited` values,
 * `burySiblings` both ways); each step is one of: answer `queue.items[0]` with a random rating
 * through the real scheduler and append the `TodayReview`; advance `now` (by seconds, to the
 * next learning due, past `day.endMs` with a new `day` and empty `today`); press Keep going
 * (set `keepGoing = true`, `keepGoingNewTarget = newToday + max(1, newGoal)`); end the visit
 * (`keepGoing = false`, target null); hold the answer back as a [PendingAnswer] instead of
 * applying it (for I6). I1 … I8 are asserted after every step, I7 only for answer steps.
 *
 * Consequences:
 * - A stop screen (`GOAL_REACHED` / `DONE_FOR_TODAY` / `EMPTY`) means nothing is left for today:
 *   nothing ready, nothing later today, `untilStop == 0`.
 * - `GOAL_REACHED` / `DONE_FOR_TODAY` always have a Keep going that yields at least one card (never
 *   a dead end).
 * - `WAITING` no longer implies "no new card left": it happens whenever the served queue is empty
 *   and a learning card is on its timer. With `keepGoingAvailable` the client offers Keep going
 *   next to the countdown; a keep-going evaluation of the same input is then `REVIEWING` (K is
 *   non-empty) and serves backlog / new cards, never a learning card before its due time.
 * - A learning card that comes due makes N (and K) non-empty, so the phase is `REVIEWING` again and
 *   that card is first (rule 5 / 6 order).
 * - The loop never serves a learning card early in order to leave `WAITING` (no learn-ahead,
 *   ADR-033): `learnAheadMs` is forced to 0 as above.
 * - Sequence of a normal day without keep going: `REVIEWING` … (`WAITING` ↔ `REVIEWING`)* … then
 *   exactly one of `GOAL_REACHED` / `DONE_FOR_TODAY` / `EMPTY`, and that only once the last
 *   learning card due today was answered into a state that is not due again today.
 *
 * **Order of what is served** is the builder's (rule 5 for N: due learning, then ready reviews and
 * new cards in the proportional mix; rule 6 for K: due learning, reviews, new). A card that came
 * due is therefore always at the front of the next evaluation.
 *
 * **Edge cases** (each is a LOOP-CORE test):
 * - Goal 39/40 and only a 3-card kanji unit left (two kanji and their word): the unit leads with
 *   its first kanji (rule 4 "Fits or leads", ADR-043) → `REVIEWING`, `untilStop` counts 1 new card;
 *   after it, at 40/40, the phase is `WAITING` while that kanji (or any other card) is in a
 *   learning step due today and the stop screen (rule 3) after that. The goal is always reachable
 *   while new cards exist and is never overshot without keep going.
 * - The served build draws only withheld units (existing words whose kanji notes are missing):
 *   `items` empty, `kanjiToMaterialize` non-empty → `REVIEWING`; the caller materializes and
 *   re-evaluates (C-27); `untilStop` is the same before and after.
 * - Per-deck new caps bind before the goal: same rule (N has no new, K has → rule 2 while
 *   `laterToday > 0`, then rule 3).
 * - New pool empty before the goal and backlog beyond the cap → `DONE_FOR_TODAY` (K non-empty
 *   through its reviews), after any `WAITING` for today's learning cards; while waiting
 *   `keepGoingAvailable` is true (the backlog).
 * - `new_per_day = 0` with new cards: the stop screen as soon as the ready reviews are done
 *   **and** no learning card is left for today (a review answered Again is in relearning and
 *   holds the day open: `WAITING`).
 * - `reviews_per_day = 0` (limited): `readyReviews = 0`, `backlog` = all due reviews, `reviewsLimit = 0`.
 * - ADR-049: a stop screen has `laterToday == 0`, so no learning card can fall due during it from
 *   this input alone. One can still appear through an undo, an answer on another client or a
 *   pulled op: the next evaluation is then `WAITING` (not yet due) or `REVIEWING` with
 *   `readyLearning = 1`; after it is answered the phase is as before.
 * - ADR-049: a learning card whose step ends at or after `day.endMs` (answered shortly before
 *   the day boundary) is not in `laterToday`: it does not cause `WAITING` and the stop screen
 *   shows. After the rollover it is ready learning of the new day.
 * - ADR-049: `WAITING` with `keepGoingAvailable` and the user keeps going: the keep-going
 *   evaluation is `REVIEWING` (backlog, then new). When K runs empty while learning cards are
 *   still on their timers: `WAITING` with `keepGoing = true` and `keepGoingAvailable = false`.
 *   When the keep-going visit ends (ADR-039) during a wait: `WAITING`, `keepGoing = false`,
 *   `keepGoingAvailable` as computed.
 * - ADR-049: all remaining learning cards are suspended, buried or deleted during `WAITING`:
 *   `laterToday` drops to 0 and the next evaluation is rule 3 or 4 (callers re-evaluate after
 *   every local mutation and at `nextDueAt`; nothing is cached in the loop).
 * - ADR-049: clock skew. `laterToday` / `nextDueAt` are decided against `input.now` only; a
 *   card with `dueAt == now` is ready, not later. A caller whose clock is behind the evaluator's
 *   re-evaluates and gets either `WAITING` with a later `nextDueAt` or `REVIEWING`; it never
 *   derives the phase from its own clock.
 * - Day rollover: everything is recomputed from the new `input.day` and `input.today` (empty at
 *   the start of the day); nothing carries over, including a caller's keep-going flag (rule 6
 *   Scope: one review visit, ADR-039) and an open kanji group (rule 5).
 * - Retired cards (rule 0, ADR-035) are in no count and no item.
 * - A read-only caller that cannot materialize: `toMaterialize` non-empty still counts as
 *   non-empty → `REVIEWING` (C-27; the API then reports `to_materialize > 0` with possibly empty `items`).
 *   The same holds for `kanjiToMaterialize`.
 * - Empty input (no candidates, no pending entries): all counts 0, `nextDueAt = null`, `EMPTY`.
 * - Duplicate candidates or `today` rows are the loader's bug; the loop does not deduplicate.
 * - Candidate order, deck order and `today` order do not change the result.
 *
 * **Worked examples.** Settings unless stated: `reviewsPerDay` 200, `newPerDay` 40, batch 10,
 * `burySiblings` true; "A" = `reviewsUnlimited` true, "B" = false.
 * - **A1** (A; 0 ready, 26 learning cards due later today, 100 new cards, 0 introduced today):
 *   N = 10 NEW → `REVIEWING`; `readyLearning 0, readyReviews 0, laterToday 26, nextDueAt` = their
 *   earliest due, `backlog 0/0, reviewsToday 0, reviewsLimit null, newToday 0, newGoal 40,
 *   newAvailable true, untilStop 66`; `legacy = (new 40, learning 26, review 0, moreAvailable false)`.
 *   Never `WAITING`.
 * - **B3** (B; 200 reviews done today, 40 introduced, 135 due reviews left, new cards remain):
 *   N empty, K non-empty → `DONE_FOR_TODAY`; `readyReviews 0, backlog 135, backlogBeyondLimit 135,
 *   reviewsToday 200, reviewsLimit 200, newToday 40, newGoal 40, newAvailable true, untilStop 0`;
 *   `legacy = (0, 0, 0, moreAvailable true)`.
 * - **B4** (B3 with `keepGoing = true`): `queue` = K = due learning, then the 135 backlog reviews by
 *   `(dueAt, cardId)`, then up to 10 NEW (no NEW before the last backlog review) → `REVIEWING`,
 *   `keepGoing true`, `untilStop 145` (135 backlog + the batch of 10); every other [LoopState]
 *   field equals B3's.
 * - **A3** (A; 40/40 introduced, no due reviews, no learning card due later today
 *   (`laterToday = 0`; with `laterToday > 0` it is W1 step 2, `WAITING`), new cards remain):
 *   N empty, K = 10 NEW → `GOAL_REACHED`, `untilStop 0`, `keepGoingAvailable true`. With `keepGoing = true` → `REVIEWING`, items NEW; after 60 more introductions
 *   still `REVIEWING` with `newToday 100, newGoal 40` (no end while new cards exist).
 * - **U1** (ADR-038; B; goal 40, 10 introduced, 30 reviews due under the cap, 5 learning cards
 *   due later today, 0 ready, 200 new cards): `REVIEWING`, `untilStop = 0 + 5 + 30 + 30 = 65`.
 *   After one review answered Again: `readyReviews 29, laterToday 6` → still 65 (the bar's total
 *   grew by one with `done`). After a new card answered Good: new part 29, `laterToday` + 1 → 65.
 *   After a review answered Good: 64.
 * - **W1** (ADR-049, the reported case; A; goal 40, 40 introduced today, 0 due reviews, 707 new
 *   cards; 23 of today's cards are in learning steps, all due within 8 minutes, before the day
 *   ends):
 *   1. 9 of the 23 are due, 14 are not: N = K's head = those 9 LEARNING items →
 *      `REVIEWING`; `readyLearning 9, readyReviews 0, laterToday 14, nextDueAt` = the earliest
 *      of the 14, `backlog 0/0, reviewsLimit null, newToday 40, newGoal 40, newAvailable true,
 *      untilStop 23, untilStopParts (9, 14, 0, 0), keepGoingAvailable false` (N is non-empty);
 *      `legacy = (new 0, learning 23, review 0, moreAvailable false)`. Header "9 learning · 14
 *      later · 0 to review · 0 new" = 23 = the bar's remainder.
 *   2. None of the 23 is due (the reported instant: 13:15:24, one second after the 40th new
 *      card, the next due at 13:15:47; equally after the 9 of step 1 were answered into a
 *      further step): N empty, K = 10 NEW → **`WAITING`** (ADR-037 said `GOAL_REACHED`);
 *      `readyLearning 0, laterToday 23, nextDueAt` = 13:15:47, `untilStop 23, untilStopParts
 *      (0, 23, 0, 0), keepGoingAvailable true`; `legacy.moreAvailable true`.
 *   3. Step 2 with `keepGoing = true`, batch 10: `queue` = K = 10 NEW → `REVIEWING`, `keepGoing
 *      true, keepGoingAvailable false, untilStop 0 + 23 + 0 + 10 = 33, untilStopParts (0, 23, 0,
 *      10)`; every other field equals step 2's. At 13:15:47 the due learning card is K's first item.
 *   4. All 23 answered until none is due again today (graduated, or their next step ends after
 *      `day.endMs`), still 40/40, without keep going: N empty, K non-empty, `laterToday 0` →
 *      `GOAL_REACHED`, `untilStop 0, untilStopParts (0, 0, 0, 0), keepGoingAvailable true`. With
 *      `reviewsUnlimited = false` the same four steps end in `DONE_FOR_TODAY`.
 * - **W2** (ADR-049; B; 200/200 reviews, 40/40 new, 135 due reviews beyond the cap, 2 relearning
 *   cards due in 6 minutes): N empty, K = the backlog → `WAITING`, `keepGoingAvailable true`,
 *   `untilStop 2`, `(0, 2, 0, 0)`; with `keepGoing = true` → `REVIEWING` (the backlog),
 *   `untilStop 0 + 2 + 135 + 10 = 147`, `(0, 2, 135, 10)`. After the 2 graduate → B3.
 * - **W3** (C-31, ADR-052: one goal-sized batch per press; A; goal 40, 40 introduced today, no
 *   due reviews, no learning card left for today, 707 new cards, batch 10):
 *   1. `keepGoing = false`: N empty, K∞ = 10 NEW → `GOAL_REACHED`, `untilStop 0`, `(0, 0, 0, 0)`,
 *      `keepGoingAvailable true`, `newToday 40`. The client computes `T = 40 + max(1, 40) = 80`.
 *   2. `keepGoing = true, keepGoingNewTarget = 80`: `targetRemaining 40`; S = K_T = 10 NEW (the
 *      batch) → `REVIEWING`, `untilStop 0 + 0 + 0 + 40 = 40`, `(0, 0, 0, 40)`,
 *      `keepGoingAvailable false`.
 *   3. After 25 of them were introduced (`newToday 65`), 3 of those are in a step due in 2
 *      minutes, 1 is due now, the rest graduated: S = `[that LEARNING card, 10 NEW]` →
 *      `REVIEWING`, head = the due learning card (I5), `untilStop 1 + 3 + 0 + 15 = 19`,
 *      `(1, 3, 0, 15)`.
 *   4. `newToday 80`, nothing in a learning step due today: `targetRemaining 0` → K_T empty, K∞
 *      = 10 NEW → **`GOAL_REACHED` with `keepGoing = true`**, `untilStop 0`, `(0, 0, 0, 0)`,
 *      `keepGoingAvailable true`. (Before C-31: `REVIEWING` with the 81st new card.) Had 2 of
 *      the batch still been on their timers: `WAITING`, `(0, 2, 0, 0)`, `keepGoingAvailable
 *      true`, and this step after they graduate.
 *   5. The user presses again: `T = 80 + 40 = 120` → step 2 with `newToday 80`.
 *   6. Step 4 with only 12 new cards left in every deck when the press of step 1 happened:
 *      step 2 has `untilStop 12`, `(0, 0, 0, 12)`; once the 12 are introduced and graduated
 *      (`newToday 52`, target 80 not reached) K_T is empty **and K∞ is empty** → `EMPTY`,
 *      `keepGoingAvailable false`: the "You're done" screen without the button (ADR-051 a).
 *   With `reviewsUnlimited = false` steps 1 and 4 are `DONE_FOR_TODAY`.
 * - **W4** (C-31, ADR-052 a: a card that comes due is next; B; goal 20 met, target 40,
 *   `newToday 27`; keep going on; new cards n8 … n20 remain; card c answered Again 60 s ago
 *   comes due now; a review r that is due today has just arrived by pull):
 *   S = `[c, r, n8, …]`: the learning card first, then the review, then new cards. The
 *   evaluation one second **before** c's due: S = `[r, n8, …]`, `laterToday 1`. With n8's word
 *   unit half-answered (its kanji was the last answer, `s == 0`): S = `[c, n8, r, n9, …]` — the
 *   open group's remaining card stays ahead of the review, never ahead of the learning card.
 *   This is the **open-unit exception to ADR-052 (a)** ([QueueBuilder] rule 6 "Target"): the
 *   one case in which a due review is not served before a new card; it costs the review at
 *   most the remaining cards of that one unit.
 * - **X1** (C-31, ADR-052 b: exit mid-batch; A; goal 20, 20 introduced, plenty of new cards):
 *   1. Press Keep going: `T = 40`; `REVIEWING`, `(0, 0, 0, 20)`.
 *   2. The user answers 7 new cards (`newToday 27`; 2 of them in a step due in 5 minutes, 5
 *      graduated) and leaves Review. The client drops the flag and the target.
 *   3. Home evaluates with `keepGoing = false`, target null: `dayRemaining = max(0, 20 − 27) = 0`
 *      → N empty; `laterToday 2` → `WAITING`, `untilStop 2`, `(0, 2, 0, 0)` — **new 0**, not 13
 *      (I8); `keepGoingAvailable true`. After the 2 graduate: `GOAL_REACHED`, `(0, 0, 0, 0)`.
 *   4. The user returns and presses Keep going: `T = 27 + max(1, 20) = 47` → `REVIEWING`,
 *      `(0, 0, 0, 20)`: a **fresh full batch of 20**, not the 13 left of the old one.
 *   5. The same, but the 2 learning cards are due when the user returns before pressing:
 *      `keepGoing = false` → `REVIEWING` with those 2 (`(2, 0, 0, 0)`), then step 3's end.
 * - **X2** (C-31, target already exceeded; keep going with `T = 40`, but `newToday 43` because
 *   another device introduced cards and its reviews were pulled): `targetRemaining 0`; no NEW
 *   is drawn; with 3 due reviews S = those 3 → `REVIEWING`, `(0, 0, 3, 0)`; with none →
 *   `GOAL_REACHED` (new cards remain), `keepGoingAvailable true`; the press gives `T = 63`.
 * - **X3** (C-31, `newPerDay = 0`; B; 5 due reviews, 30 new cards, nothing introduced): normal
 *   build serves the 5 reviews and never a new card (`parts.new 0`, I8); after them
 *   `DONE_FOR_TODAY`, `keepGoingAvailable true`; press → `T = 0 + max(1, 0) = 1` → one NEW,
 *   `(0, 0, 0, 1)`; after it graduated → `DONE_FOR_TODAY` again; each press gives one card.
 */
public interface ReviewLoop {
    /**
     * Evaluates the loop for [input] as the interface documents. Never throws for well-formed
     * input; negative limits count as 0, `keepGoingNewBatch < 1` as 1.
     */
    public fun evaluate(input: QueueInput): LoopResult
}
