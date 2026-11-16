# Review loop (ADR-037) — design

Design only. Source: the user-confirmed loop of 2026-11-17 (A = reviews_unlimited on, B = off). One rule
lives in core (`ReviewLoop`); server, phone local API, phone native (widget, notifications), web and CLI
only render its output. Nobody else counts cards or decides a phase.

**Amended 2026-11-17 (user, via LOOP-C).** (1) The review/new interleave is *not* removed: the normal
session uses a deterministic **proportional mix** in both modes (rule 5 below). Keep going keeps the
confirmed order: capped mode clears the backlog first, then new cards without end; unlimited mode serves
new cards and slots a review that comes due in next. (2) Dropping `scheduler.new_per_session` is
confirmed. (3) Keep going per device, not synced, is confirmed. The normative text is the contract
(`QueueBuilder.kt` rule 4-6, `ReviewLoop.kt`, openapi `getReviewQueue`); this file was updated to match.

## 1. Vocabulary (normative; same numbers on every client at the same instant)

| Term | Definition |
|---|---|
| eligible | QueueBuilder rules 0-1 (not retired, suspended, buried, excluded override, sibling-buried). |
| ready learning | eligible LEARNING/RELEARNING with `dueAt <= now`. Never capped, never served early (learn-ahead 0). |
| due review | eligible REVIEW with `dueAt < day.endMs` (day granularity, rule 3 as today; overdue included). |
| backlog | all due reviews an uncapped build would take (= REVIEW items of the keep-going build K). |
| ready reviews | due reviews within the remaining review budget (= REVIEW items of the normal build N). `backlog >= ready reviews`; equal when `reviews_unlimited`. |
| ready now | ready learning + ready reviews. New cards are NOT part of it (they have the bar). |
| later today | eligible LEARNING/RELEARNING with `now < dueAt < day.endMs`; `nextDueAt` = their min dueAt. Hint only. |
| new today / new goal | today's `introducedNew` count / `scheduler.new_per_day`. |
| keep going | per-request flag (as C-26). Lifts review caps and the new goal; nothing else. |

## 2. Core (contract Kotlin in `core/.../contracts/queue/`, impl `kotobaya.queue.DefaultReviewLoop`)
```kotlin
public enum class LoopPhase(public val wire: String) {
    REVIEWING("reviewing"), GOAL_REACHED("goal_reached"), DONE_FOR_TODAY("done_for_today"),
    WAITING("waiting"), EMPTY("empty")
}
public data class LoopState(
    val phase: LoopPhase,
    val keepGoing: Boolean,          // echo of input.limits.keepGoing; the only input phase depends on besides data
    val readyLearning: Int, val readyReviews: Int,          // ready now = sum
    val laterToday: Int, val nextDueAt: InstantMs?,
    val backlog: Int, val backlogBeyondLimit: Int,          // beyond = backlog - readyReviews (0 when unlimited)
    val reviewsToday: Int, val reviewsLimit: Int?,          // today's countsAsReview; null = reviews_unlimited
    val newToday: Int, val newGoal: Int,
    val newAvailable: Boolean,       // K draws >= 1 new card or pending entry (ignores goal and deck caps)
    val legacy: LegacyCounts,        // new, learning, review, moreAvailable: frozen pre-ADR-037 wire meaning (5)
)
public data class LoopResult(val queue: ReviewQueue, val state: LoopState)   // queue = the build to SERVE
public interface ReviewLoop { public fun evaluate(input: QueueInput): LoopResult }
```

`evaluate` is pure. It builds N = `build(input with keepGoing=false)` and K = `build(input with keepGoing=true)`
(two builds, always; both from the same loaded input) and returns:

- `queue` = K if `input.limits.keepGoing` else N.
- counts: every field except `phase`/`keepGoing` is computed from N, K and `input.today` only, so it is
  identical with and without keep going (ADR-033 principle kept).
- `phase`, with S = `queue`, "non-empty" = `items` or `toMaterialize` non-empty:
  1. S non-empty -> REVIEWING.
  2. else if `!keepGoing` and K non-empty -> GOAL_REACHED if `limits.reviewsUnlimited` else DONE_FOR_TODAY.
  3. else if `laterToday > 0` -> WAITING (resume at `nextDueAt`).
  4. else EMPTY.
  So WAITING happens only when nothing is ready and no new card is left anywhere (B6); GOAL_REACHED /
  DONE_FOR_TODAY always have a Keep going that yields at least one card (never a dead end).

QueueBuilder rule changes (contract KDoc; `DefaultQueueBuilder`):
- Rule 5 (order, normal build): due learning first, then the ready reviews R (`(dueAt, cardId)`, most overdue
  first) and the drawn new cards N in the **proportional mix**: with `a` = today's `countsAsReview`, `b` = today's
  `introducedNew`, `Rt = a + |R|`, `Gt = max(1, newPerDay)`, while both lists have cards the next item is a new
  card iff `b * Rt < a * Gt` (new progress behind review progress), else a review; then `b += 1` / `a += 1`; when
  one list runs out the other follows. Integers only; a tie is a review. The counters advance exactly as `today`
  does, so one build's order equals rebuilding after every answer. Both modes: R is every ready review when
  unlimited, the reviews left under the cap otherwise. The mix does not depend on the batch.
  Example: nothing done, goal 2, R1-R4 due, N1 N2 -> `R1 N1 R2 R3 N2 R4`. Replaces the fixed-stride interleave.
- Rule 4 normal budget: `dayRemaining = max(0, newPerDay - introducedToday)`; groups are drawn while
  `drawn < max(1, keepGoingNewBatch)` and the group fits `dayRemaining` (atomic against the DAY, the batch may
  overshoot like rule 6). `newPerSession` / `newShownThisSession` are deprecated and ignored (no "done for now").
  Per-deck `newPerDay` still applies in N, lifted in K. The batch keeps materialization small (server: `limit`,
  device: 10).
- Rule 6 (K): unchanged (review caps lifted, new batch, due learning, all reviews, then new; no mix).
- `ReviewQueue.moreAvailable` unchanged in meaning (N empty and K non-empty); deprecated in favour of `phase`.
- `learnAheadMs`: loaders keep passing 0; `ReviewLoop.evaluate` replaces a value > 0 by 0 for both builds
  (served queue and counts). Only `LoopResult.queue.toMaterialize` (the served build) is ever materialized.

Edge cases (write tests for each): goal 39/40 and only a 3-card add_kanji group left -> N has no new ->
goal_reached at 39/40 (UI shows the real numbers); per-deck new caps bind before the goal -> same phase rule;
new pool empty before the goal, backlog beyond cap -> DONE_FOR_TODAY; `new_per_day = 0` with new cards ->
phase 2 immediately once reviews are done; `reviews_per_day = 0` -> ready reviews 0, backlog all; a learning
card coming due during GOAL_REACHED/DONE -> REVIEWING again (1 ready), then back; day rollover -> everything
recomputed from the new `day`; retired cards never counted anywhere; `toMaterialize` non-empty for a
read-only caller counts as REVIEWING (as C-27).

## 4. Client rules (normative, in openapi description + SHELL.md 4.3)
- C1 Due first: a client may hold a page, but before showing each next card it MUST refetch if
  `now >= T`, T = min(`loop.later_today.next_due_at` of the last response, `due_at` of every card it answered
  since that left the card in learning/relearning). So a card that came due is the very next card.
- C2 Keep going is persisted per client for the study day: store the `study_day` it was started in
  (web/shell SPA: `localStorage["kotobaya.keepGoingDay"]`; CLI daemon: its state file; native widget needs
  none, it shows mode-independent counts). Active iff it equals the latest response's `study_day`; on mismatch
  drop it and refetch with `keep_going=false`. Home, review and widget all send the persisted value. This
  replaces ADR-031's "one visit" scope. Not an op, not a setting.
- C3 Display: ready-now number = `ready.total` when not keeping going; `ready.learning + backlog.reviews`
  when keeping going. Backlog line only when `backlog.beyond_limit > 0`. Later hint when
  `later_today.count > 0`. New bar `new.today/new.goal` (may exceed the goal after keep going: clamp the
  bar, show the number).
- C4 Empty `items` -> render by `loop.phase` only; `waiting` schedules one refetch at `next_due_at`
  (existing slack/min-gap rules). New clients never read `counts`, `more_available`, `next_learning_due_at`.
- C5 Materialization retries (C-27) unchanged: `reviewing` with empty `items` and `to_materialize > 0` -> refetch.

## 5. API (openapi, additive)
`ReviewQueue.loop` (new, sent by every ADR-037 server and local API; schema `ReviewLoop`, all fields required):

```yaml
loop:
  phase: reviewing            # reviewing | goal_reached | done_for_today | waiting | empty
  keep_going: false           # the request's flag
  ready: {learning: 1, review: 200, total: 201}
  later_today: {count: 26, next_due_at: '2026-11-17T09:06:00.000Z'}   # next_due_at null iff count 0
  backlog: {reviews: 335, beyond_limit: 135}
  reviews: {today: 0, limit: 200}        # limit null = scheduler.reviews_unlimited
  new: {today: 12, goal: 40, available: true}
```

- Scope: computed for the request's `deck_id`; unaffected by `kinds`, `limit` and (except `phase`,
  `keep_going`) by `keep_going`.
- Deprecated, still sent with frozen meaning for old clients (old CLI, older web bundle), all from
  `LoopState.legacy`: `counts.new` (NEW items + toMaterialize of N), `counts.learning`
  (ready learning + later today: the old over-count, documented as such), `counts.review` (= ready.review),
  `more_available`, `next_learning_due_at` (= later_today.next_due_at). `StatsSummary.due` /
  `more_available`: same deprecation note, computed through `ReviewLoop` too; no new StatsSummary field
  (Home uses `getReviewQueue?limit=1`).
- `keep_going` parameter: unchanged wire; description rewritten to C2. Enum is open for readers: an unknown
  `phase` is treated as `reviewing` if `items` non-empty, else `empty`.
- `scheduler.new_per_session`: SCHEMA.md 6 marks it deprecated/ignored (still accepted by `updateSettings`).
- No op, no table, no SCHEDULER_VERSION change: the queue is a pure view; replay untouched; C-27 ops unchanged.

## 6. Shell contract
- `ops.json`: `getReviewQueue` stays `local`; SHELL.md 4.3 note rewritten (handler = `ReviewLoop.evaluate`
  + views; `newShownThisSession` gone).
- `LOCAL_API_VERSION` 1 -> 2 and the web bundle's `min_local_api` 2: the new SPA cannot render without `loop`
  (7.2 gains "or a field a new SPA requires is added"). Old SPA on new native keeps working via legacy fields.
- Lock to portrait (device-local, not synced, no op): `GET/PATCH /shell/v1/display`
  (`getDisplayPrefs`, `updateDisplayPrefs`), schema `ShellDisplay {lock_portrait: boolean}` (default false),
  patch `ShellDisplayPatch {lock_portrait?: boolean}` (`minProperties: 1`), capability `display`,
  unpaired `401` / rebuild `503` like the other preference ops (table section 12), 6.3 gains: host sets
  `requestedOrientation = PORTRAIT` when true, `UNSPECIFIED` when false, at activity create and immediately
  on PATCH; stored in device preferences; survives restart; the SPA hides the row without the capability.

## 7. Supersedes
- ADR-031: scope ("one visit", never persisted) and `more_available` as the UI signal -> C2 and `phase`.
  The flag, rule 6 and "not a setting/op" stay.
- ADR-033: "counts include learning due later today" and the single waiting state -> ready/later split, B6.
  No learn-ahead and mode-independent counts stay.
- ADR-034: "new-card limits unchanged / counts from the same build" -> A (goal_reached + Keep going).
- ADR-023/028: already superseded; the rule 5 fixed-stride interleave (SPEC "Queue building") becomes the
  proportional mix: SPEC note added.
- ADR-032 (C-27): budget wording only (day remaining + batch instead of min(day, session)); README "C-27"
  window `B = max(1, keepGoingNewBatch)`.
- ADR-035, ADR-036: unchanged.

## 8. Migration / compatibility
- Data: none. Settings: none added; `scheduler.new_per_session` ignored.
- Old CLI / old web against new server: legacy fields keep shape and meaning; visible differences: order
  (proportional mix instead of the fixed stride), `counts.new` bounded by `limit`, no session-cap "done for now".
  Nothing breaks.
- Old APK (0.1.31): its SPA talks to its own bundled local API (version-locked); sync is untouched. Until the
  new APK the phone shows the old numbers (expected, not a break). An OTA web bundle is refused by
  `min_local_api`.
- New web against an old server cannot happen (deployed from one pin); new CLI against an old server: daemon
  treats a missing `loop` as a hard "server too old" status line (no fallback logic).
- Release order: server+web, CLI, then APK.

## 9. Parity goldens (server is the oracle; recorded by LOOP-SRV with `RECORD=1`, reproduced by LOOP-AND)
Re-record every golden whose steps include `getReviewQueue` (body gains `loop`; order may change):
`queue-counts-keep-going`, `queue-learning-wait`, `queue-kinds-filter-counts`, `queue-materialize-count`,
`queue-reviews-unlimited`, `queue-day-rollover-timezone`, `empty-replica`, `parent-deleted-by-pull`,
`dict-entry-missing`, `reading-retired-reenabled`, `answer-retry-same-id`.
Retire `queue-session-cap-more-available` (tag `queue.session_cap_more_available` removed from 8.4).
New scenarios + mandatory tags: `queue-loop-unlimited-later-new` (`queue.loop_never_waits`),
`queue-loop-cap-keep-going-backlog` (`queue.loop_done_backlog_first`), `queue-loop-goal-reached`
(`queue.loop_goal_reached`), `queue-loop-waiting-empty` (`queue.loop_waiting`, `queue.loop_empty`).
`queue.counts_keep_going` now asserts `loop` minus `phase`/`keep_going` equal across the flag.

## 10. Tests (core golden scenarios in `ReviewLoopTest`, jvm + js; the same tables drive server and parity)
Settings unless stated: reviews_per_day 200, new_per_day 40, batch 10, bury on.
- A1 (user case) unlimited, 0 ready, 26 learning later, 100 new, new today 0 -> reviewing; items = 10 NEW;
  ready 0/0; later 26 + next_due_at; never waiting. legacy counts.learning = 26.
- A2 unlimited, 335 due reviews -> ready.review 335, backlog 335/0, limit null; order learning, then reviews and
  new in the proportional mix.
- M1 mix: nothing done, goal 2, R1-R4, N1 N2 -> `R1 N1 R2 R3 N2 R4`. M2: 200 ready, goal 40, nothing done -> new
  after the 1st review, then after every 5 more; the 40th new follows the 196th review. M3: 9 ready, 12/40
  introduced, 0 reviewed -> `R R R N N R N N N N R ...`. M4: for random inputs, answering the head and rebuilding
  yields the previous build's tail (same order); the order does not depend on the batch. M5: capped mode mixes
  too (cap 2, goal 1, 5 due -> `R1 N1 R2`).
- A3 unlimited, new today 40/40, no reviews, new remain -> goal_reached; keepGoing -> reviewing, NEW items;
  after 60 more introduced still reviewing (indefinite); new.today 100, goal 40.
- A4 A3 + keepGoing + a review and a learning card come due -> items = learning, review, then new.
- B1 335 due, 0 done -> ready.review 200, backlog 335/135, reviewing; first item = most overdue; new cards mixed
  in over the 200.
- B2 200 reviews done, new 12/40, new exist -> reviewing, items NEW only; backlog 135/135; a learning card due
  now is first and does not change reviews.today.
- B3 (user case) 200/200 and 40/40, 135 left -> done_for_today, backlog 135/135.
- B4 (user case) B3 + keepGoing -> reviewing; items = due learning, then backlog by (dueAt, cardId); no NEW
  before the last backlog review when backlog > page; counts equal to B3 except phase/keep_going.
- B5 B4 with backlog cleared -> NEW items only, forever (as A3). B6 (either setting) keepGoing, no backlog, no new anywhere, 8 later -> waiting with next_due_at; none later -> empty.
- B7 not keepGoing, caps hit, backlog 0, new remain -> done_for_today (not goal_reached: setting off). B8 pool exhausted at 25/40, backlog 135 beyond cap -> done_for_today.
- E1-E6: section 2 edge cases (39/40 kanji group, deck caps, new_per_day 0, reviews_per_day 0, rollover, retired).
- P1 property (extend `QueuePropertyTest`): counts invariant under keepGoing; `backlog >= ready.review`;
  phase reviewing iff served queue non-empty; phase in {goal_reached, done_for_today} implies K non-empty;
  no LEARNING item with `dueAt > now` ever; N.items is order-independent of input list order.
- Server: `ReviewApiTest` A1, B3->B4, A3; legacy fields frozen (old-client snapshot test); `kinds`/`limit`
  do not change `loop`. Android: parity goldens + widget/notification count = ready total. Web: state tests
  for each phase, C1 refetch, C2 persistence and day change, layout test + screenshots (Home with backlog
  line and later hint; goal_reached; done_for_today; waiting). CLI: daemon status per phase, keep-going day
  persisted across restart, missing `loop`.

## 11. Rejected
Fixing only `counts.learning` (keeps the dead-end wait and the session cap); keep going as a synced setting
(ADR-031 rejected synced expiring state; offline devices would disagree); phase derived in each client (five
copies of one rule); reviews "due by now" at instant granularity (cards due at 20:00 trickle in and bring
waiting back); keeping `new_per_session` as a third stop state; reviews strictly before new in the normal session
(user 2026-11-17: a regression); a mix from the remaining counts alone (recomputed after each answer it degenerates
into reviews-first); a mix for capped keep going (user: backlog first).

## 12. Most worth a second look
1. The proportional mix (amended): new cards spread over the day's reviews by progress against today's totals;
   with a lopsided history (many reviews done, goal raised later) new cards come in a run until caught up.
2. Keep going is per client (pressing it on the phone does not switch the widget); counts stay identical.
3. `done_for_today` vs `goal_reached` chosen by the setting, not by which cap bound (B7).
