# HANDOFF T401a

STATUS: DONE
BRANCH: t/T401a-jpdb-deck-client
CHECK: `make check MODULE=worker` → green

## Changed (≤ 6 lines)
- `worker/src/kotobaya_worker/importers/jpdb_deck/client/`: models, `CachedFetcher` (rate limit + disk cache), `PagesDeckSource`, `ApiDeckSource` (+ `make_source`).
- `worker/tests/importers/jpdb_deck/client/test_jpdb_deck_client.py`
- `fixtures/jpdb/deck/` (3 list pages, deck page, mocked API response, README; CC0)

## Acceptance → tests
- Parser exact rows + skipped → `test_pages_parser_on_fixtures`
- Paging, cache hit = 0 calls → `test_paging_follows_next_links_and_cache_hit_makes_no_network_call` (+ `test_max_age_expires_cache`)
- No key in cache → `test_api_key_never_in_cache`
- Rate limit ≥ 2 s → `test_rate_limiter_spaces_calls`
- API mapping / fallback → `test_api_source_maps_mocked_response`, `test_api_schema_mismatch_falls_back_to_pages`

## New dependencies
- none (stdlib only)

## For the reviewer (≤ 3 lines)
- API response layout is ASSUMED (`vocabulary` rows `[vid, spelling, reading, rank]`, parallel `occurences`, optional `title`), isolated in `map_api_response`; non-numeric deck ids skip API and use Pages. Numeric id -> pages path `deck/<id>` is unverified.
- HUMAN_CHECKS: (1) fetch one real public deck via Pages, compare row counts with the site; (2) with `JPDB_API_KEY`, run API path once, paste real field names back; verify numeric-id deck URL form.
