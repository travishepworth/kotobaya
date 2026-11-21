# HANDOFF LF-W3

STATUS: DONE
BRANCH: t/LF-W3-web-first-sync
CHECK: `make check MODULE=web` → green (366 tests, 6 new)

## Changed (≤ 6 lines)
- `web/src/lib/state/shell.svelte.ts`: `first_sync` from `shell.sync` events and `getSyncStatus`; `isFirstSyncPending()`; `done` latches.
- `web/src/lib/ui/FirstSync.svelte` (new): full-screen "Setting up: N of M", indeterminate when `total` is null, waiting line offline.
- `web/src/routes/+layout.svelte`: shows FirstSync instead of the app shell while pending (never on /login). Outbox effect untouched.
- `web/src/routes/messages.ts`: four `firstSync.*` strings.
- `web/tests/shell/firstSync.test.ts`: tests in the existing `tests/shell` folder, not beside the owned files.

## Acceptance → tests
- Shown only in shell mode, only while done is false, gone on the done event → "shows while done is false, from shell.sync events, and disappears on the event that reports done"; "is never pending outside the shell, even with a not-done event"
- Never again after done → "is never shown again once done was seen"
- `make check MODULE=web` green → run above

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Remote-only affordance NOT changed: the existing gate is route-level (`needsConnection`, `ROUTE_OPS` in `access.ts`, not owned). Per-action inline "needs connection" (SHELL.md 9.4) needs files outside this ticket. Question for the orchestrator.
- Added the offline "waiting for a connection" line from SHELL.md 10.18 (not in the ticket).
