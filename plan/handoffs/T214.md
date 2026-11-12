# HANDOFF T214

STATUS: DONE
BRANCH: t/T214-web-import
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- `web/src/routes/import/+page.svelte`: pick file → SHA-256 → `putBlob(upload)` → `importJpdbHistory` (import_reviews / import_word_status toggles) → follows the job via `job.updated` SSE (refetch `getJob`, also on reconnect) → counters table, unmapped and kanji_unmapped tables.
- `counters.ts`, `messages.ts` (own i18n). Known counters in fixed order; any other numeric result member (e.g. `reviews_id_collisions`) is listed under "Other counters" (reads the raw `Job.result`).
- Flagged outside owned paths: nav link `/import` in `web/src/routes/+layout.svelte`, key `nav.import` in `web/src/lib/i18n.ts`.

## Acceptance → tests
- Manual only (needs T207c wiring). No tests added (UI).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No mapping review queue route exists, so no link; the full unmapped lists are on the page.
- Generated `putBlob` types `body` as the model `Blob` (shadows the DOM Blob); I cast with `as never`.
