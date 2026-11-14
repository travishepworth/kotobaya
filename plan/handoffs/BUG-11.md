# HANDOFF BUG-11

STATUS: DONE
BRANCH: t/BUG-11-jpdb-deck-url
CHECK: `make check MODULE=worker` → green

## Changed
- pages.py: list URL now carries the title slug (kept from input URL, else resolved from the overview at the slugless `/vocabulary-list`); 0-row first page raises.
- fetch.py: `get(accept=...)`; a rejected cached body is a miss, a rejected fetched body is never cached (poisoned PVC entries ignored).
- handler.py: `DeckJob.deck_ref` keeps the raw input so the slug reaches `fetch_deck`; `jpdb_id` stays normalized.
- Fixtures: new real trimmed `deck-overview.html`; list fixtures' next links now slugged (README updated).

## Acceptance → tests
- slugless fetch maps all rows → test_slugless_url_resolves_slug_via_overview_and_maps_every_row
- slugged direct → test_title_from_list_page_and_no_bare_deck_request
- 0 rows raises → test_zero_row_first_page_raises
- cached 0-row not reused → test_zero_row_page_is_never_cached_and_cached_one_is_ignored

## New dependencies
- none

## For the reviewer
- Note: bare `/<kind>/<id>` 404s; the overview is at slugless `/vocabulary-list` (verified live). Slugless input costs one extra request.
- Re-import (item 4): not changed; plan_request already proceeds for an existing live jpdb media (only raises if deleted/non-jpdb). Not exercised against a live empty deck.
