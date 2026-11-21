# HANDOFF LF-W3

STATUS: DONE
BRANCH: t/LF-W3-web-first-sync
CHECK: `make check MODULE=web` → green (368 tests, 8 new)

## Changed (≤ 6 lines)
- `web/src/lib/state/shell.svelte.ts`: `first_sync` from `shell.sync` events and `getSyncStatus`; `isFirstSyncPending()`; `done` latches.
- `web/src/lib/ui/FirstSync.svelte` (new): full-screen "Setting up: N of M"; indeterminate when `total` is null; waiting line offline.
- `web/src/routes/+layout.svelte`: FirstSync replaces the app shell while pending (never on /login). Outbox effect untouched.
- `web/src/lib/ui/access.ts`: `ROUTE_OPS` now `{}` (see below); `web/tests/shell/access.test.ts` updated.
- `web/src/routes/messages.ts` (4 strings); `web/tests/shell/firstSync.test.ts`.

## Acceptance → tests
- Shown only in shell mode, only while done is false, gone on the done event → "shows while done is false, from shell.sync events, and disappears on the event that reports done"; "is never pending outside the shell, even with a not-done event"
- Never again after done → "is never shown again once done was seen"
- Route gate → "the table holds only remote-only (proxy) operations…"; "remote-only actions inside mixed routes do not gate the route…"
- `make check MODULE=web` green

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No route is wholly remote, so no route can be gated under the all-proxy rule. Listing a mixed route (e.g. /media/[id]/read, /import/mapping) would gate its local reads offline. Decision needed: keep the empty table, or change the gate rule (out of ROUTE_OPS scope).
- Per-action "needs connection" still needed (not route-reachable): `createDeck` type=list (/decks/new, forwarded per request); `putParseOverride`/`deleteParseOverride` (reader, /media/[id]/read); `testGrammarRules` (grammar capture, reader); `listJpdbVocab` (/import/mapping); `getJob` (import/media job polling). Blob uploads `putBlob` (/import, /media/new) are proxy but SHELL.md 9.4 says not offered in shell; confirm hidden vs. affordance.
- Design section 3 lists media/import/note-types/presets/grammar writes as remote; ops.json (C-31) has them local or hidden. Used ops.json.
