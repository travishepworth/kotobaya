# HANDOFF LOOP-WEB2

STATUS: BLOCKED
BRANCH: t/LOOP-WEB2
CHECK: `make check MODULE=web` → green (run with `GRADLE=true` to skip the Gradle npmPackage step, per machine limit; core/build/npm/core symlinked)

## Changed (≤ 6 lines)
- web/src/routes/settings/StudySection.svelte: removed `scheduler.new_per_session` from the limits group (key kept in lib/settings/keys.ts)
- web/mock/fake-shell.js: `local_api_version: 2`

## Acceptance → tests
- Item 2 and 3 → existing web check (vitest, svelte-check, build) green
- Item 1 (end-screen quotas line) → NOT DONE, see BLOCKED

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Item 1 is not started: its files are outside Owned. Items 2 and 3 are committed and green.

## If BLOCKED
QUESTION: Owned lists `web/src/lib/ui/Loop*.svelte`, but no such files exist. The loop UI is `web/src/lib/review/LoopHero.svelte` and `LoopMeta.svelte`, and the strings are in `web/src/lib/review/messages.ts` and `web/src/routes/review/messages.ts` (LoopEnd uses `rv`). Item 1 needs edits there: may I add those four paths to Owned (or move the strings), and should the quota line be "200/200 reviews · 40/40 new" as literal text?
