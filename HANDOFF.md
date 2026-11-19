# HANDOFF CORE-30

STATUS: DONE
BRANCH: t/CORE-30 (from t/C-30-loop-wait)
CHECK: `make check MODULE=core` → green (jvm + js)

## Changed (≤ 6 lines)
- `core/.../queue/DefaultReviewLoop.kt`: phase rule 2 `laterToday > 0 → WAITING` now runs before the stop screens (both modes). Sets `untilStopParts` (untilStop = their sum) and `keepGoingAvailable = !keepGoing && legacy.moreAvailable`. Learn-ahead is still forced to 0.
- New `ReviewLoopWaitTest.kt`. `QueuePropertyTest` gains the ADR-049 properties. `QueueParityScenarioTest` gains the two C-30 scenarios. Old tests that expected a stop phase with `laterToday > 0` now expect WAITING.

## Acceptance → tests
- W1 steps 1-4 + capped twin → `w1Step1…`, `w1Step2…`, `w1PlayedThrough…`
- Reported case (9 ready / 14 later / 707 new) → `reportedCaseAfterTheNineAreAnsweredWaitsForTheFourteen`
- W2 → `w2WaitsForRelearningBeforeDoneForToday`
- Day-end boundary → `learningDueAtDayEndDoesNotHoldTheDayOpen`, `queueLoopDayEndLearning`
- README edge cases and properties → remaining `ReviewLoopWaitTest` tests, `loopInvariantsHoldOnRandomInputs`
- Parity → `queueLoopWaitBeforeStop`, `queueUntilStop` (step 8 now WAITING)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `GOLDEN_FINGERPRINT` was re-recorded because `LoopState.toString()` has new fields and some phases changed. The queue builder is untouched.
- The parity scenarios were replayed from their narratives, not from the JSON steps (core has no parity runner).
