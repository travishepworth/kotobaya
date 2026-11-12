# HANDOFF T106

STATUS: DONE
BRANCH: t/T106-dict-search
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `server/.../dict/DictApi.kt`: `dictHandlers(db)` for searchDictionary, getDictEntry, getKanji (keyset cursor bound to q/common_only; scopes come from the spec). Not yet wired: add `handlers = dictHandlers(db)` to AppDeps in `Main.kt` (not my path).
- `server/.../dict/Romaji.kt`: local table-driven romaji→hiragana. Core has none; it should move to core.
- Tests/fixtures in `server/src/test/{kotlin,resources}/dict/`: `queries.json` (50), `ref_fixture.sql.gz` (285 KB, T101 loader output on fixtures/dict, data-only COPY dump), `make_fixture_dump.sh` regenerates it.

## Acceptance → tests
- 50 queries, expected top → `fiftyFixtureQueriesReturnTheExpectedTopResult`
- retired never returned → `retiredEntriesAreNeverSearchedButStillResolvable` (plus checked inside the 50)
- empty q 422; paging → `emptyAndBlankQueryIs422AndBadParamsToo`, `pagingWalksTheWholeResult...`
- C.UTF-8 + fixture → `DictFixture` loads the committed dump into the C.UTF-8 Testcontainers DB (no loader run in setup); it then ranks common entries by seq and retires 1584660.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No deinflection, per orchestrator note and openapi C-2 (ticket text said otherwise); `のんだ` is a query expecting an empty page. Gloss-only matches rank in the "rest" group.
- `/dict/entries/{seq}/examples` left as 501: no media sentence tables or Tatoeba rows are populated. `note_id` overlay is untested (needs app.note fixtures); `word_status` overlay is tested.
