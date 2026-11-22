package kotobaya.contracts.queue

import kotobaya.contracts.InstantMs
import kotobaya.contracts.fsrs.CardState

/**
 * C-31 (ADR-051): what the review surface shows. Decided only by [LoopPresenter.present]; web and
 * the shell SPA render it and never derive a screen from `loop.phase`, `items` or their own
 * counters. There is no "pending" / "Refresh" screen any more: its two causes are [SETTLING].
 *
 * Wire: none (the presenter runs in the client; `@kotobaya/core` exports it). [wire] is the value
 * clients use for test ids and logs. Example: `LoopScreen.SETTLING.wire == "settling"`.
 */
public enum class LoopScreen(
    /** Lower snake case identifier, unique per entry. Example: `"done_for_today"`. */
    public val wire: String,
) {
    /**
     * A card is on screen and can be graded. Chosen whenever the client holds a card
     * (`hasLocalCard`), whatever the state says. Example: P1 … P5 below.
     */
    CARD("card"),

    /**
     * No card is held but one is expected: answers are still pending, or the last state says
     * `REVIEWING` and the client has not got its items yet. A calm transitional state: the header
     * numbers stay, no grade buttons, no "Refresh" button; the client fetches by itself (openapi
     * `getReviewQueue` rule C7). Never a dead end: it ends with the next applied state.
     * Example: P6.
     */
    SETTLING("settling"),

    /** [LoopPhase.WAITING] with nothing pending: the countdown to `LoopState.nextDueAt`. Example: P7. */
    WAITING("waiting"),

    /** [LoopPhase.GOAL_REACHED] with nothing pending: stop screen with Keep going. Example: P8. */
    GOAL_REACHED("goal_reached"),

    /** [LoopPhase.DONE_FOR_TODAY] with nothing pending: stop screen with Keep going. */
    DONE_FOR_TODAY("done_for_today"),

    /** [LoopPhase.EMPTY] with nothing pending: the final "You're done" screen, no Keep going. */
    EMPTY("empty"),
}

/**
 * C-31 (ADR-051): one answer the user has given that the [LoopState] passed to
 * [LoopPresenter.present] does **not** reflect yet.
 *
 * **When an answer is pending (normative for clients).** From the moment the user grades a card
 * until the client applies a state whose request was **sent after** that answer's
 * `answerReview` response was received. So it covers: an answer waiting in the outbox (browser,
 * rule C8), an answer in flight, and an answer already acknowledged while the state on screen is
 * older. An undo removes the undone answer from the list. The list is in the order the answers
 * were given. A client that is unsure keeps the answer pending: the cost is a header that is
 * one too low for one fetch, never a wrong screen.
 *
 * Example (a review answered Again, acknowledged, next step in 10 minutes):
 * `PendingAnswer(kind = REVIEW, rating = 1, resultingState = RELEARNING, dueAt = 1795169400000)`.
 * Example (a new card graded Good in a browser that is offline, no scheduler preview at hand):
 * `PendingAnswer(kind = NEW, rating = 3, resultingState = null, dueAt = null)`.
 */
public data class PendingAnswer(
    /**
     * The [QueueItem.kind] the card had in the queue it was answered from (`QueueItem.kind` of
     * the API item): it names the header part the card was counted in. Example: `REVIEW`.
     */
    public val kind: QueueItemKind,
    /** The rating sent, 1 (Again) … 4 (Easy). Values outside 1..4 are treated as 1. Example: `3`. */
    public val rating: Int,
    /**
     * `card.state` after the answer: from the `answerReview` response when it has arrived, else
     * from the core scheduler preview the client used for the button labels, else `null`
     * (unknown). `NEW` cannot result from an answer and is treated as `null`. Example: `LEARNING`.
     */
    public val resultingState: CardState?,
    /**
     * `card.due_at` after the answer, from the same source as [resultingState]; `null` when
     * unknown. Only compared with `dayEndsAt` ("does the card come back today"), never with a
     * clock. Example: `1795169400000`.
     */
    public val dueAt: InstantMs?,
)

/**
 * C-31 (ADR-051): the output of [LoopPresenter.present]: everything the review header, the
 * progress bar and the screen switch need.
 *
 * Invariants (P-I1 … P-I5 of [LoopPresenter]): every member of [parts] is `>= 0`; [untilStop] is
 * `parts.learning + parts.review + parts.new` (ADR-054: `parts.later` is not summed); a terminal
 * [screen] has all four parts at 0; `WAITING` and terminal screens have [untilStop] 0.
 *
 * Example (P2 below): `LoopPresentation(screen = CARD, parts = UntilStopParts(learning = 0, later
 * = 6, review = 29, new = 30), untilStop = 59)`.
 */
public data class LoopPresentation(
    /** Which screen to show. */
    public val screen: LoopScreen,
    /**
     * The header numbers ("learning · to review · new") and the later-today hint (`later`),
     * already projected for the pending answers. Clients show them as they are (a part that is 0
     * may be hidden; `later` only in the hint line, never as a header chip, ADR-054) and never
     * add, subtract or re-derive. Example: `(0, 6, 29, 30)`: header "29 to review · 30 new",
     * hint "6 later today".
     */
    public val parts: UntilStopParts,
    /**
     * `parts.learning + parts.review + parts.new` (ADR-054: the cards doable now; `parts.later`
     * is **not** added): the remainder of the progress bar, `done / (done + untilStop)` with
     * `done` = the answers of this visit (pending ones included). A pending answer whose card
     * returns today moves it into `later`, so it leaves `untilStop` while `done` grows by one:
     * `done + untilStop` usually holds steady (exceptions: [LoopState.untilStop]). Example: `59`.
     */
    public val untilStop: Int,
)

/**
 * C-31 (ADR-051): the one place that turns "the last loop state I applied + what the user has
 * answered since + whether I hold a card" into the screen and the header. Pure and deterministic
 * (jvm, android, js); implementation `kotobaya.queue.DefaultLoopPresenter` (ticket CORE-31),
 * exported to `@kotobaya/core`. It exists because a client necessarily mixes an evaluation that
 * is a few answers old with cards it holds locally; done ad hoc, that showed frozen counts
 * followed by a sudden end screen, and an end screen while cards were held.
 *
 * It decides nothing about scheduling: the phase, the counts and the queue still come from
 * [ReviewLoop.evaluate] alone. With no pending answer and no held card it returns the state's own
 * phase and parts unchanged.
 *
 * **Screen** (first match wins):
 * 1. `hasLocalCard` → [LoopScreen.CARD].
 * 2. `pending` is not empty → [LoopScreen.SETTLING].
 * 3. otherwise by `state.phase`: `REVIEWING` → [LoopScreen.SETTLING] (the state has a card or
 *    something to materialize and the client holds none: it fetches); `WAITING` → `WAITING`;
 *    `GOAL_REACHED` → `GOAL_REACHED`; `DONE_FOR_TODAY` → `DONE_FOR_TODAY`; `EMPTY` → `EMPTY`.
 *
 * **Parts.** Rule 3: `state.untilStopParts`, unchanged. Rules 1 and 2: the **projection**: start
 * with `p = state.untilStopParts` and apply every pending answer in list order:
 *
 * | `kind` | Outcome | Part that loses 1 | Part that gains 1 | `untilStop` (ADR-054) |
 * |---|---|---|---|---|
 * | `NEW` | returns today | `new` | `later` | − 1 |
 * | `NEW` | leaves today | `new` | none | − 1 |
 * | `LEARNING` | returns today | `learning` (if it is 0: `later`) | `later` | − 1 (same when taken from `later`) |
 * | `LEARNING` | leaves today | `learning` (if it is 0: `later`) | none | − 1 (same when taken from `later`) |
 * | `REVIEW` | returns today | `review` | `later` | − 1 |
 * | `REVIEW` | leaves today | `review` | none | − 1 |
 *
 * (Before ADR-054 the "returns today" rows kept `untilStop` unchanged, because `later` was
 * summed. The `untilStop` column is a consequence of the parts, not a separate rule: when
 * nothing is taken because the part is 0, `untilStop` does not change.)
 *
 * - A part never goes below 0: when the part that should lose 1 is 0 (and, for `LEARNING`,
 *   `later` is 0 too) nothing is taken; the gain still applies.
 * - **Returns today** = `resultingState` is `LEARNING` or `RELEARNING` and (`dueAt == null` or
 *   `dueAt < dayEndsAt`). **Leaves today** = `resultingState` is `REVIEW`, or it is `LEARNING` /
 *   `RELEARNING` with `dueAt >= dayEndsAt` (the step ends in the next study day; such a card
 *   does not hold the day open, [LoopState.laterToday]).
 * - `resultingState == null` (unknown): returns today, except `kind == REVIEW` with `rating >=
 *   2` and `kind == LEARNING` with `rating >= 3`, which leave. (A deliberate over-count: an
 *   unknown outcome never produces a number that is too low for longer than one fetch.)
 * - A returning card always goes to `later`, never to `learning`: the presenter has no clock. A
 *   pending card whose step has elapsed is found by client rule C1 (fetch when `now >= dueAt`),
 *   and the fetched state then counts it under `learning`.
 * - Then, **only for rule 1** (a card is held), the **floor**: with `h` = the part of
 *   `localHeadKind` (`NEW` → `new`, `LEARNING` → `learning`, `REVIEW` → `review`): if `p[h] == 0`
 *   then `p[h] = 1`, and when `h` is `learning` and `p.later > 0`, `p.later` loses 1 (the held
 *   card is one that came due). So a card on screen is always counted and the header is never
 *   all zero while a card is shown. `localHeadKind == null` with `hasLocalCard` (a caller bug)
 *   is treated as `REVIEW`.
 *
 * `untilStop` = `p.learning + p.review + p.new` of the resulting parts (ADR-054: `p.later` is not
 * summed). The floor of a held `LEARNING` card that takes one from `later` therefore raises
 * `untilStop` by one: the card came due and is doable now.
 *
 * **Why no sibling column.** [QueueBuilder] rule 1 puts at most one NEW or REVIEW card per note
 * in a build, so `state.untilStopParts` never counted a sibling of a held card; answering a card
 * therefore moves exactly its own part and burying its siblings moves none (P5). One exception
 * with `burySiblings`: the day's first answer to a `LEARNING` card buries that note's NEW or
 * REVIEW sibling, which the state did count; no [PendingAnswer] tells the presenter, so its
 * parts lag by that sibling until the next state (within the `<= pending.size` bound below).
 * Where the state is older than a change made elsewhere (another device, a pull), the next
 * applied state corrects the numbers; the presenter never guesses.
 *
 * **Invariants (normative; fuzz-tested as I6 of [ReviewLoop]).** "Terminal" = `screen` in
 * {`GOAL_REACHED`, `DONE_FOR_TODAY`, `EMPTY`}.
 * - **P-I1** terminal ⇒ `pending` is empty, `hasLocalCard` is false and every part is 0.
 * - **P-I2** `parts.learning + parts.review + parts.new > 0` ⇒ `screen` in {`CARD`, `SETTLING`}.
 * - **P-I3** every part `>= 0`; `untilStop == parts.learning + parts.review + parts.new`;
 *   `screen == CARD` ⇒ `untilStop >= 1`.
 * - **P-I4** `pending` empty and `!hasLocalCard` ⇒ `parts == state.untilStopParts` and `screen`
 *   is `state.phase`'s (with `REVIEWING` → `SETTLING`): the presenter adds nothing of its own.
 * - **P-I5** (ADR-054) `untilStop > 0` ⇒ `screen` in {`CARD`, `SETTLING`}; `screen` in {`WAITING`,
 *   `GOAL_REACHED`, `DONE_FOR_TODAY`, `EMPTY`} ⇒ `untilStop == 0` (also with `parts.later > 0`
 *   on `WAITING`). With nothing pending and no held card, `untilStop > 0` ⇔ `state.phase ==
 *   REVIEWING` (from I9 of [ReviewLoop] and P-I4). `SETTLING` may show `untilStop == 0` (P6, P7).
 * - Consistency with the loop (the fuzz test's check): let `state'` be the evaluation after the
 *   pending answers were applied at the same `now`, with the same limits, and every pending
 *   answer's `resultingState` / `dueAt` known. **Asserted for every input without keep going
 *   or with a keep-going target:** `|present(…).untilStop − state'.untilStop| <= pending.size`
 *   (the projection is never off by more than the answers it projects); with `burySiblings`
 *   this bound is the whole rule. **Asserted for plain inputs** (additionally `burySiblings =
 *   false`, no pending entries, no kanji units, no per-deck limits):
 *   part by part with `learning + later` taken together (the presenter has no clock to tell
 *   them apart), and `present(…).untilStop == state'.untilStop` whenever every returning
 *   pending answer has `dueAt > now` (true for every learning step longer than 0 at the same
 *   `now`; a card that came back already due is under `learning` in `state'` but under `later`
 *   in the projection, ADR-054).
 *
 * **Edge cases.**
 * - `pending` empty, `hasLocalCard` true, state terminal (the client holds a card from an older
 *   page, e.g. a card that came due by rule C1 while the stop state was applied): `CARD`, parts
 *   = the floor only, e.g. `(1, 0, 0, 0)`. The client fetches; a card on screen is never
 *   replaced by an end screen (client rule C1).
 * - More pending answers than the state counted (duplicates in the list, or a state newer than
 *   the client thinks): parts stop at 0, `SETTLING` or `CARD`, never negative, never terminal.
 * - The same answer twice in `pending` (a caller bug): counted twice; corrected by the next state.
 * - Out-of-order list: the result does not depend on the order except through the "if it is 0"
 *   fallbacks.
 * - `dayEndsAt` in the past (the study day rolled over while the page was open): every
 *   `LEARNING` outcome "leaves today"; the client's day-change fetch (rule C6) replaces the state.
 * - Clock skew: the presenter reads no clock. `dueAt` and `dayEndsAt` come from the same party
 *   (the server, or the device), so their comparison is skew-free.
 * - Keep going with a target reached while answers are pending: `SETTLING` until the state
 *   after the last answer is applied, then that state's stop screen.
 *
 * **Worked examples.** `dayEndsAt` = D. State parts are written `(learning, later, review, new)`.
 * - **P1** (nothing pending): state `REVIEWING` `(0, 5, 30, 30)`, `pending = []`, a REVIEW card
 *   held → `CARD`, `(0, 5, 30, 30)`, `untilStop 60` (`0 + 30 + 30`; the 5 later are the hint).
 * - **P2** (Again on a review): P1, the user grades it Again; the response says `RELEARNING`,
 *   due in 10 minutes (before D); the next held card is a REVIEW card → `pending = [(REVIEW, 1,
 *   RELEARNING, now + 600 000)]` → `CARD`, `(0, 6, 29, 30)`, `untilStop 59` (the card left the
 *   total for `later` and `done` grew by one: the bar's total stays 60). This equals a fresh
 *   evaluation ([ReviewLoop] U1).
 * - **P3** (new → learning): P2, the next card is NEW and is graded Good → `LEARNING`, due in 10
 *   minutes: `pending` gains `(NEW, 3, LEARNING, now + 600 000)` → held REVIEW card → `CARD`,
 *   `(0, 7, 29, 29)`, `untilStop 58`.
 * - **P4** (learning graduating): state `REVIEWING` `(2, 1, 0, 0)`; the user grades the first
 *   learning card Good → `REVIEW`, due in 2 days: `pending = [(LEARNING, 3, REVIEW, now + 172 800
 *   000)]`, the second learning card held → `CARD`, `(1, 1, 0, 0)`, `untilStop 1` (state: 2).
 * - **P5** (sibling bury): `burySiblings`; note X has a meaning card M (REVIEW, due today) and a
 *   reading card R (REVIEW, due today); the state counted only M (`review 30`: one per note). M
 *   is graded Good → `REVIEW`: `pending = [(REVIEW, 3, REVIEW, …)]` → `(0, 5, 29, 30)`. R is now
 *   sibling-buried, and nothing more is subtracted for it: it was never counted. A fresh
 *   evaluation gives the same `review 29`. (This equality holds because M was a REVIEW card;
 *   had M been `LEARNING` and R counted, the fresh evaluation is lower by R: see "Why no
 *   sibling column".)
 * - **P6** (day end): 23:58 local, D = midnight's instant; state `REVIEWING` `(1, 0, 0, 0)`; the
 *   last learning card is graded Again → `LEARNING`, due in 10 minutes = D + 480 000 (after D):
 *   `pending = [(LEARNING, 1, LEARNING, D + 480 000)]`, no card held → **leaves today** →
 *   `SETTLING`, `(0, 0, 0, 0)`, `untilStop 0` (state: 1); the fetch returns `GOAL_REACHED` (the card does not
 *   hold the day open) → with `pending` now empty: `GOAL_REACHED`, zeros. Had it been due at
 *   D − 1: `SETTLING`, `(0, 1, 0, 0)`, `untilStop 0`, then `WAITING` with `untilStop 0` and the
 *   hint "1 later today".
 * - **P7** (last card answered, answer still in the outbox): state `REVIEWING` `(0, 0, 1, 0)`; the
 *   only review is graded Good, the browser is offline: `pending = [(REVIEW, 3, null, null)]`, no
 *   card held → `SETTLING`, `(0, 0, 0, 0)` — **not** an end screen (P-I1: an end screen needs
 *   `pending` empty). When the answer is acknowledged and the next state (`DONE_FOR_TODAY`) is
 *   applied: `DONE_FOR_TODAY`.
 * - **P8** (state says reviewing, nothing held, nothing pending; the old "pending" screen):
 *   state `REVIEWING` `(0, 0, 0, 12)` with `items` empty and `to_materialize 3` (a read-only
 *   caller, or the three rounds ran out) → `SETTLING`, `(0, 0, 0, 12)`; the client refetches
 *   (rule C5).
 * - **P9** (held card, stale terminal state): state `GOAL_REACHED` zeros; a LEARNING card held
 *   (it came due through an undo) → `CARD`, `(1, 0, 0, 0)` by the floor.
 * - **P10** (keep-going batch, [ReviewLoop] W3): state `REVIEWING`, keep going, target 80, `(0,
 *   0, 0, 40)`, `untilStop 40`; three NEW cards graded Good → `LEARNING` due in 1 minute: `CARD`,
 *   `(0, 3, 0, 37)`, `untilStop 37` (with `done` 3 the bar's total stays 40).
 */
public interface LoopPresenter {
    /**
     * Presents [state] for a client that has given the answers [pending] since [state] was
     * evaluated and holds a card iff [hasLocalCard], as the interface documents. Never throws.
     *
     * @param state the [LoopState] of the latest `getReviewQueue` response (or local evaluation)
     *   the client applied.
     * @param pending the answers [state] does not reflect, oldest first ([PendingAnswer]); empty
     *   when there are none.
     * @param hasLocalCard true iff the client holds a card it can show now (one that has no
     *   pending answer).
     * @param localHeadKind [QueueItem.kind] of that card; null iff [hasLocalCard] is false.
     * @param dayEndsAt `day_ends_at` of the response [state] came from (the end of its study
     *   day); a learning card due at or after it does not come back today.
     */
    public fun present(
        state: LoopState,
        pending: List<PendingAnswer>,
        hasLocalCard: Boolean,
        localHeadKind: QueueItemKind?,
        dayEndsAt: InstantMs,
    ): LoopPresentation
}
