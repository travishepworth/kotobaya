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
     * Stop screen. Only without keep going and only with `limits.reviewsUnlimited == true`: nothing
     * is ready under the limits, **no learning card falls due later today** (`laterToday == 0`,
     * ADR-049), and a keep-going build has at least one card. In practice the day's new goal is
     * reached (or no new group fits it) and every card studied today has left its learning steps
     * for today. Keep going always yields a card. Example: A3 below; W1 step 4.
     */
    GOAL_REACHED("goal_reached"),

    /**
     * Stop screen. Only without keep going and only with `limits.reviewsUnlimited == false`:
     * nothing is ready under the limits, **no learning card falls due later today**
     * (`laterToday == 0`, ADR-049), and a keep-going build has at least one card (backlog beyond
     * the review cap and/or new cards). Chosen by the setting, not by which cap bound (B7).
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
     * is done and there is no Keep going. Example: B6 once the 8 cards have graduated.
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
     * batch). `>= 0`. Example: goal 40, 10 introduced, plenty of new cards → 30; goal met → 0;
     * keep going with batch 10 → 10.
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
 * - ADR-049: `phase` in {`GOAL_REACHED`, `DONE_FOR_TODAY`} implies `!keepGoing`, K non-empty,
 *   `laterToday == 0`, `nextDueAt == null`, `readyLearning == 0`, `readyReviews == 0` and
 *   `untilStop == 0`; `phase == EMPTY` implies the same zeros and K empty;
 * - `keepGoingAvailable == (!keepGoing && N empty && K non-empty)`; hence true in `GOAL_REACHED` /
 *   `DONE_FOR_TODAY`, false in `REVIEWING` without keep going, false in `EMPTY`, false whenever
 *   `keepGoing`;
 * - retired cards (rule 0) are in no count;
 * - `untilStop >= 0`; without keep going `untilStop == readyLearning + laterToday + readyReviews +
 *   availableToday(N)`; `phase == REVIEWING` implies `untilStop >= 1`;
 *   `untilStop == 0` iff `phase` in {`GOAL_REACHED`, `DONE_FOR_TODAY`, `EMPTY`} (ADR-049: the bar
 *   is full exactly at a stop screen);
 * - `untilStopParts` sums to `untilStop`; its `learning == readyLearning`, `later == laterToday`,
 *   `review == (if (keepGoing) backlog else readyReviews)`.
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
     * ADR-049 (additive): **offer a Keep going button now**. True iff `keepGoing == false`, the
     * normal build N is empty and the keep-going build K is non-empty (`items`, `toMaterialize` or
     * `kanjiToMaterialize`): a keep-going request would serve at least one card right now. Always
     * true in `GOAL_REACHED` / `DONE_FOR_TODAY`; in `WAITING` it is true when a backlog beyond the
     * cap or a new card exists (the user may keep going instead of watching the countdown; due
     * learning cards still come first as they fall due) and false otherwise; false in `REVIEWING`
     * without keep going and in `EMPTY`; false in every keep-going evaluation. Equals
     * `!keepGoing && legacy.moreAvailable`. Clients render the button from this field only.
     *
     * The default `false` exists only so that existing callers compile; `ReviewLoop.evaluate` MUST
     * set it.
     * Example: W1 step 2 (waiting, 707 new cards) → true; B6 (waiting, nothing new) → false; W1
     * step 3 (the same instant with keep going) → false.
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
 * ADR-037, phase order amended by ADR-049: the one review loop. Pure and deterministic (same input → same result on jvm, android
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
 * **Phase (ADR-049 order).** S = `queue`; "non-empty" = `items`, `toMaterialize` or
 * `kanjiToMaterialize` non-empty. First match wins:
 * 1. S non-empty → [LoopPhase.REVIEWING].
 * 2. else if `laterToday > 0` → [LoopPhase.WAITING] (resume at `nextDueAt`), **with and without
 *    keep going, whatever K holds**.
 * 3. else if `!keepGoing` and K non-empty → [LoopPhase.GOAL_REACHED] if `limits.reviewsUnlimited`
 *    else [LoopPhase.DONE_FOR_TODAY].
 * 4. else [LoopPhase.EMPTY].
 *
 * (ADR-037 had rules 2 and 3 the other way round: the stop screen appeared while learning cards
 * were on their timers, 23 of them within 8 minutes in the report that led to ADR-049.)
 *
 * `keepGoingAvailable = !keepGoing && N empty && K non-empty`, independent of the phase rule.
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
 * - **A3** (A; 40/40 introduced, no due reviews, new cards remain): N empty, K = 10 NEW →
 *   `GOAL_REACHED`. With `keepGoing = true` → `REVIEWING`, items NEW; after 60 more introductions
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
 */
public interface ReviewLoop {
    /**
     * Evaluates the loop for [input] as the interface documents. Never throws for well-formed
     * input; negative limits count as 0, `keepGoingNewBatch < 1` as 1.
     */
    public fun evaluate(input: QueueInput): LoopResult
}
