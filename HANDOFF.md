# HANDOFF LOOP-WEB + PORTRAIT-WEB

STATUS: DONE
BRANCH: t/LOOP-WEB+PORTRAIT-WEB
CHECK: `make check MODULE=web GRADLE=:` → green (Gradle skipped as instructed; core npm symlinked)

## Changed (≤ 6 lines)
- `lib/review/loop.ts`, `keepGoingDay.ts`: read `loop` only; C2 store `kotobaya.keepGoingDay`, one helper for Home and review
- `state/review.svelte.ts`: phases from `loop.phase`; C1 refetch before the next card, C4 single wake, C5 ≤3 retries; old session-cap code removed
- `state/today.svelte.ts` + `LoopHero`/`LoopMeta`: ready number, later hint, new bar, backlog line, phase screens, Keep going
- `routes/review/LoopEnd.svelte`: goal_reached, done_for_today, waiting, empty screens
- Lock to portrait: `device.svelte.ts` (`setLockPortrait`, reverts on failure), DeviceSection row, strings
- `MIN_LOCAL_API` 2 (manifest + protocol)

## Acceptance → tests
- phases, no `counts` read → `tests/review/loopPhases.svelte.test.ts` (fixture throws on deprecated fields)
- C2 → `keepGoingDay.svelte.test.ts`; C1 → `dueFirst.svelte.test.ts`; A1 → `tests/home/homeLoop.test.ts`
- portrait → `tests/shell/displayPrefs.svelte.test.ts`
- layout + screenshots → `playwright/review-loop.spec.ts`, `web/screenshots/shell/loop-*.png` (360/412/desktop, all reviewed)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Missing `loop` falls back to legacy fields (as asked), isolated in `legacyLoop`; the contract says no fallback.
- Not owned, follow-up: remove the Settings "new per session" row (StudySection, settings/keys); `mock/fake-shell.js` still reports local_api 1, so old shell-mode specs show "update". I edited 3 tests that assert min_local_api.
