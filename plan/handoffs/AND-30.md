# HANDOFF AND-30

STATUS: DONE
BRANCH: t/AND-30
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `android/.../shell/api/review/QueueRoute.kt` `loopJson`: adds `keep_going_available` and `until_stop_parts {learning, later, review, new}` from core `LoopState`, matching the server. Phase order comes from core.

## Acceptance → tests
- android green, local parity incl. queue-loop-wait-before-stop and queue-loop-day-end-learning → existing LocalParityTest against the SRV-30 goldens (passes unchanged)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No native count surface (widget/tile/notification) exists in android that reads until_stop or the loop, so nothing else changed.
- word_audio untouched.
