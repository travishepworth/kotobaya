# HANDOFF LOOP-31-CORE-B

STATUS: CONTRACT_CHANGE_REQUEST (work done, green; one clause false)
BRANCH: t/LOOP-31-CORE-B
CHECK: `make check MODULE=core` → green

## Changed
- `queue/DefaultLoopPresenter.kt`: screen rule, projection, floor.
- `jsMain/.../LoopPresenterJs.kt`: `presentLoop` plus `Js*` DTOs, like `previewIntervals`.
- Fuzz: a "hold the answer back" step and a stale partial fetch; I6 (P-I1…P-I4 plus consistency) checked after every step.
- `LoopPresenterTest` (P1–P10, edge cases). `jsTest/LoopPresenterJsTest`: new dir outside owned paths; acceptance needs it.

## Acceptance → tests
- P1–P10 → `LoopPresenterTest.p1…p10*`
- I6 jvm+js → `ReviewLoopInvariantFuzzTest.invariantsHoldAfterEveryStep`
- JS → `LoopPresenterJsTest.p3RoundTrips`

## New dependencies
- none

## For the reviewer
- Naive presenter (fuzz written first): fails I6 (frozen header) in 15/200 seeds: 8 break the bound, 7 the exact check. `i6CatchesANaivePresenter` locks this in. Never a terminal screen with pending answers, so "done screen" likely lives in web wiring.
- JS inputs are classes: external interfaces were left undeclared in `.d.ts`.

## If CONTRACT_CHANGE_REQUEST
CONTRACT: core/.../contracts/queue/LoopPresenter.kt
CHANGE: limit the "plain inputs" exact equality and P5's sibling note to `burySiblings = false`.
WHY: with burying, the day's first answer to a LEARNING card buries a counted REVIEW/NEW sibling (37/2000 seeds, e.g. 171). The `<= pending.size` bound holds; the fuzz test already applies this limit.
ADDITIVE: yes
