# HANDOFF C-30

STATUS: DONE
BRANCH: t/C-30-loop-wait
CHECK: `make check MODULE=contracts` → green; `:core:compileKotlinJvm :core:compileKotlinJs` → green

## Changed (≤ 6 lines)
- `ReviewLoop.kt`: phase order `WAITING` (rule 2) before `GOAL_REACHED`/`DONE_FOR_TODAY` (rule 3); additive `LoopState.keepGoingAvailable`, `untilStopParts` (`UntilStopParts`), defaults so core compiles; W1/W2, edge cases, invariants.
- openapi `ReviewLoop`: `keep_going_available`, `until_stop_parts {learning, later, review, new}` (optional); rules C3, C4, new C6 (refetch at `next_due_at` in every phase).
- Parity: new `queue-loop-wait-before-stop` (`queue.loop_waits_before_stop`) and `queue-loop-day-end-learning` (`queue.loop_learning_past_day_end`: due at `day_ends_at` → `goal_reached`, later 0, `until_stop` 0; due 1 ms earlier → `waiting`), both mandatory; `queue-until-stop` step 8 → `waiting`; 19 whole-`/loop` expectations extended. A3 KDoc states `laterToday = 0`.
- `docs/REVIEW-LOOP.md` §14, SHELL.md, SUMMARYs, core README; clients regenerated.

## Acceptance → tests
- contracts green, scenarios hand-evaluated → `contracts/api/check.sh` parity checks; goldens not recorded (SRV-30).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Tickets: CORE-30 (DefaultReviewLoop; tests in contracts/core/README "ADR-049"), then SRV-30 (map + re-record goldens), AND-30, WEB-30 (Home/header/C6/waiting screen), CLI-30.
- My decisions: Keep going offered during `waiting` (backlog too, not only new); `until_stop_parts.review` = backlog with keep going; no `LOCAL_API_VERSION` bump.
- Scenarios assume Easy on a learning card graduates past today and a 60 s first step; day-end scenario pins `reviewed_at` and asserts `/card/due_at`.
