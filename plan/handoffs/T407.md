# HANDOFF T407

STATUS: DONE
BRANCH: t/T407-web-jpdb-mapping
CHECK: `make check MODULE=web` → green (17 vitest, svelte-check 0 errors/0 warnings, vite build)

## Changed (≤ 6 lines)
- `web/src/routes/import/mapping/+page.svelte`: review queue (`listJpdbVocab`, status unresolved, 50/page, load more); resolved rows leave the table; re-publish jobs listed below.
- `web/src/routes/import/mapping/MappingRow.svelte`: per row, dictionary search (`searchDictionary`) pre-filled with spelling (reading if empty); pick → `putJpdbVocabResolution` mapped; Ignore → ignored.
- `web/src/routes/import/mapping/messages.ts`: `tm()` strings.
- `web/src/routes/import/+page.svelte` + `messages.ts`: one link line `imp.mapping`.

## Acceptance → tests
- `make check MODULE=web` green → no unit test (UI, none mandated); existing vitest 17 pass.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No job page exists, so "job link" is shown as the re-publish job's status text (snapshot at response time), not a link.
- Clearing a correction (`clearJpdbVocabResolution`) is not wired; ticket did not ask for it.
- Not live-tested: needs T411b + W-5 for real data.
