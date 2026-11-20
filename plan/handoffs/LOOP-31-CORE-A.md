# HANDOFF LOOP-31-CORE-A

STATUS: DONE
BRANCH: t/LOOP-31-CORE-A (from origin/t/LF-int)
CHECK: `make check MODULE=core` → green (jvm + js)

## Changed (≤ 6 lines)
- `ReviewLoopInvariantFuzzTest` (2 000 runs jvm / 200 js, ≤ 60 steps, real FsrsScheduler): asserts I1–I5, I7, I8, the target bound, and mode-independent counts.
- Before the fix (current code): I1–I5 and I8 held; I7 failed in 117 seeds; the target bound failed in 1 232 of 2 000 seeds (target ignored).
- `DefaultQueueBuilder`: rule 6 "Target": `targetRemaining` budget, draw not limited by the batch so `availableToday` = new cards left to the target, first-draw exception; the normal build and the `moreAvailable` probe ignore the target.
- `DefaultReviewLoop`: K_T served, phase rule 3 without `!keepGoing`, `keepGoingAvailable = S empty && K∞ non-empty`, `parts.new` from K_T.

## Acceptance → tests
- Fuzz, deterministic → `invariantsHoldAfterEveryStep`, `aSeedAlwaysPlaysTheSameSession`
- C-31 examples → `ReviewLoopTargetTest` (W3 1–6 + capped twin, W4, X1, X2, X3), `QueueKeepGoingTargetTest`; existing tests unchanged
- EMPTY/kga → `emptyMeansKeepGoingHasNothingAndStopScreensAlwaysOfferIt`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Mutation-checked: the fuzz fails if rules 2/3 are swapped (I2), if kga comes from K_T (I4), or if N reads the target (Counts/I8).
- The fuzz runs 2 000 runs on jvm or 200 on js, chosen by `1.0.toString()` (no expect/actual in owned paths). I6 is left to CORE-B.
