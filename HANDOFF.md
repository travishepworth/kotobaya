# HANDOFF W-3

STATUS: DONE
BRANCH: t/W-3-web-nav
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- `web/src/routes/+layout.svelte`: added Cards (/cards) and Note types (/note-types) to the `nav` array; Review, Decks, Settings were already present.
- `web/src/lib/i18n.ts`: added `nav.cards` ("Cards") and `nav.noteTypes` ("Note types").
- Active marking (`aria-current`) and logged-out behaviour unchanged.

## Acceptance → tests
- `make check MODULE=web` green → web build (no unit tests; UI per CLAUDE.md)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Active marking is the existing exact-match `pathname === href`, so `/cards/<id>` will not highlight Cards. Left as-is; say if prefix matching is wanted.
- I did not verify that the /cards and /note-types routes exist; links are added per ticket.
- `make fmt` also reformatted two worker files; reverted, not committed.
