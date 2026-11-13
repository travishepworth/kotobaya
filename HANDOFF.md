# HANDOFF T604

STATUS: BLOCKED
BRANCH: t/T604-cli
CHECK: `make check MODULE=cli` → red (no `check-cli` target yet; nothing implemented, by design of the block)

## Changed (≤ 6 lines)
- Nothing implemented. Branch cut from main (a03fdc4); only this HANDOFF.md is committed.

## Acceptance → tests
- none run (blocked before implementation)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The API has no deck name on media import: `MediaImportRequest.deck` is `MediaDeckOptions` (enabled, new_cards_per_day, priority); a new deck's id is the media id.
- New media also requires `kind` (anime|novel|vn|manga|web|other) and `title`; the ticket names neither a flag nor a default.

## If BLOCKED
QUESTION: How should `kotobaya media import` handle these, given `POST /media/import` (importMedia) requires `kind` and `title` for new media and has no deck-name field?
1. `--deck NAME`: (a) drop it for now; (b) use NAME as the title; (c) map to deck options (enabled/new_cards_per_day/priority) on a new flag; (d) CONTRACT_CHANGE_REQUEST to add a deck name.
2. `kind`: (a) add `--kind` with default `other`; (b) infer from the extension (unreliable); (c) required flag.
3. `--title` absent: default to the file name without extension?
