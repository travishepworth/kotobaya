# HANDOFF SRV-30

STATUS: DONE
BRANCH: t/SRV-30
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `server/.../review/ReviewApi.kt` `loopJson`: adds `keep_going_available` and `until_stop_parts {learning, later, review, new}` from `LoopState`.
- `ReviewApiTest.loopSame` also ignores the two new mode-dependent keys.
- Goldens re-recorded (`RECORD=1`): 32 updated, 2 new (`queue-loop-wait-before-stop`, `queue-loop-day-end-learning`); `queue-until-stop` step 8 is now `waiting`. word_audio untouched.

## Acceptance → tests
- server green, parity incl. queue-loop-wait-before-stop → `ParityGoldenTest` (re-run without RECORD passes)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The only non-additive golden diff is the phase change `done_for_today` -> `waiting` (queue-until-stop step 8).
- Goldens were recorded from the server, not hand-checked line by line against the contracts parity narratives.
