# HANDOFF WEB-KG-IDLE

STATUS: DONE
BRANCH: t/WEB-KG-IDLE-web
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- web/src/lib/review/keepGoingDay.ts: `KEEP_GOING_IDLE_MS` (10 min); lazy idle expiry checked in `keepGoingDay()` (so every fetch, Home and review) and `expireKeepGoingIfIdle()`; `touchKeepGoing()` restarts the clock; `startKeepGoing` starts it.
- web/src/lib/state/review.svelte.ts: `send()` touches the idle clock on each answer; `onVisible()` runs the idle check.
- web/tests/review/keepGoingIdle.svelte.test.ts: fake Date; 11 min → next fetch false; 9 min → true; answer restarts clock; resume after 11 min ends it.

## Acceptance → tests
- 11 min idle → next fetch keep_going=false → "11 min idle: the next fetch sends keep_going=false"
- 9 min idle → still true → "9 min idle: the next fetch still sends keep_going=true"

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Decision: idle runs from the last answer or the Keep going press (not from the last fetch). Undo does not count as an answer.
- Expiry is lazy (no timer), so a backgrounded WebView ends it on the next read or visibility change.
