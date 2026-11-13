# HANDOFF AND-SEARCH

STATUS: DONE
BRANCH: t/AND-SEARCH-search-tab
CHECK: `make check MODULE=android` → green (all test results 0 failures; rc=0)

## Changed (≤ 6 lines)
- `search/`: Search tab (Dictionary / My cards modes), `WordSearch` (on-device `InstalledDictionary`, else `GET /dict/search`), `LocalCardSearch` (core parser + SQLite compiler on replica), `ApiCardSearch` fallback (`GET /cards`); parse errors shown inline.
- `word/`: `WordRepository`, `WordDetailScreen`, `NoteDetailScreen` (card without entry).
- `strings_search.xml`; one line in `FeatureModules.kt`; `AppStartsTest` no longer expects a Search placeholder (outside owned paths; siblings will touch the same line).

## Acceptance → tests
- My-cards query via core parser + SQLite compiler, parse error as state → `LocalCardSearchTest` (5 cards, 5 queries, 2 error cases)
- Tab replaces placeholder, results open word detail → `searchFeature` route; no UI tests

## New dependencies
- none (no `ApiClient.kt` / `T.kt` edits needed)

## For the reviewer (≤ 3 lines)
- `NavRoute.content` has no args/navController, so `word/{seq}` is not a nav route: detail is state inside the Search screen (BackHandler), as StruggleScreen does. Composables are reusable if a route is added later.
- Replica "usable" = `SyncBootstrap.ready` completed; local errors fall back to API. `is:hot` and live smart-deck terms are not fed to the compiler. Add-to-my-words omitted.
