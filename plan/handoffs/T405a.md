# HANDOFF T405a

STATUS: DONE
BRANCH: t/T405a-media-library
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- `web/src/routes/media/+page.svelte`: library list, kind/title filters, cursor paging, rename/reparse (not jpdb)/delete+confirm.
- `web/src/routes/media/new/+page.svelte`: wizard (Files/URL/Paste/jpdb), uploads via `PUT /blobs`, live job progress, unmapped link to `/import/mapping`.
- `web/src/lib/media/{import,messages}.ts`: UUIDv7 `import_id` minting, `submitImport` retry helper, strings.
- `web/tests/media-import.test.ts`.

## Acceptance → tests
- Retried import reuses same `import_id` → `submitImport reuses the same import_id when the first call fails with a network error`
- make check green → done

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Test sits in `web/tests/` (vitest include pattern), outside the ticket's owned list.
- `import_id` is kept across manual retries until reset; jpdb deck import has no `import_id` in the contract (server dedupes).
- Segment count is `media.segments?.length` (may be 0 if the list omits segments); `/media/[id]` and `/import/mapping` pages belong to other tickets.
