# HANDOFF LF-W4

STATUS: DONE
BRANCH: t/LF-W4-web-needs-connection
CHECK: `make check MODULE=web` → green (371 tests, 3 new)

## Changed (≤ 6 lines)
- `lib/ui/access.ts`: `actionNeedsConnection(opId, state)`, `needsConnectionFor(opId)` (from ops.json `proxy`), `uploadsOffered()`.
- `lib/ui/NeedsConnectionHint.svelte` (new) + 3 strings in `shellMessages.ts`.
- Triggers disabled + hint offline: DeckForm (createDeck, type list), GrammarCapture test, TokenSheet split/join (putParseOverride), reader override delete, /import/mapping (waits, loads when online).
- Job polling: `JobWatch.waiting`/skips `getJob` offline (`state/jobs.svelte.ts`); "Waiting for a connection" on /import and /media/new.
- Uploads hidden in shell: /import form replaced by note; /media/new loses the file tab.
- `tests/shell/needsConnection.test.ts`.

## Acceptance → tests
- Offline proxy disabled; online/non-shell enabled → "disables a proxy action…"
- Uploads hidden → "upload entry points are hidden in shell mode…"
- make check green.

## New dependencies
- none

## For the reviewer
- Touched `state/jobs.svelte.ts` (outside listed paths, trigger only). Component wiring is untested (UI).
