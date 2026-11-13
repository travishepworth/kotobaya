# AND-SEARCH — Android Search tab

- Phase: P8 · Model: sonnet · Risk: med
- Deps: none
- Branch: t/AND-SEARCH-search-tab
- Owned paths (only edit these): `android/src/main/kotlin/kotobaya/android/search/` (new), `android/src/main/kotlin/kotobaya/android/word/` (new word detail route), `android/src/main/res/values/strings_search.xml`, `android/src/test/kotlin/kotobaya/android/search/`, one line in `android/src/main/kotlin/kotobaya/android/di/FeatureModules.kt`, additive lines in `android/src/main/kotlin/kotobaya/android/i18n/T.kt`, additive methods in `android/src/main/kotlin/kotobaya/android/net/ApiClient.kt`
- Module(s) for CI: android

## Read
- Spec: search syntax and dictionary sections of docs/SPEC.md; Android section
- Contracts: contracts/api/openapi.yaml `searchDict`, `getEntry`/dictionary ops, `listCards` (search param)
- Existing code: `android/src/main/kotlin/kotobaya/android/nav/AppNav.kt` (NavRoute, Routes.SEARCH="search"), `android/src/main/kotlin/kotobaya/android/review/ReviewFeature.kt`, `android/src/main/kotlin/kotobaya/android/struggle/StruggleFeature.kt`, `android/src/main/kotlin/kotobaya/android/di/FeatureModules.kt`, `android/src/main/kotlin/kotobaya/android/dict/InstalledDictionary.kt` (`search()`, `DictEntryDetail`), `android/src/main/kotlin/kotobaya/android/sync/SqlLearningTables.kt`, core `kotobaya/search/SearchParser.kt` (DefaultSearchQueryParser) and `search/sql/SearchSqlCompiler.kt` (SQLite dialect)

## Do
1. Search tab at route `search`: a query box with two modes — **Dictionary** (words) and **My cards**.
2. Dictionary mode: on-device `InstalledDictionary.search()` when the package is installed; otherwise the API `searchDict`. Results show headword, reading, first gloss, status; paging.
3. My cards mode: parse with core's DefaultSearchQueryParser (show parse errors inline, from the parser), compile with SearchSqlCompiler (SQLite dialect) against the local replica. If the local replica is not usable, fall back to the API cards search. No search parsing re-implemented in Android.
4. A word detail route (`word/{seq}`, in `word/`): DictEntryDetail (readings, glosses, kanji, the user's notes/cards for that entry and their status), from device data or the API. A dictionary result opens it; a card result opens the word detail of its entry (or the card's note if it has no entry).
5. Optional: an "Add to my words" action only if an op-backed note-creation path already exists on device (T305b NoteCreator); otherwise leave it out.

## Acceptance
- [ ] Test: a My-cards query runs through core parser + SQLite compiler against an in-memory replica and returns the expected cards (one fixture with 3–5 cards; a parse error surfaces as an error state, not a crash).
- [ ] The Search tab replaces the placeholder; results open the word detail screen (no UI tests required).
- [ ] `make check MODULE=android` green.

## Out of scope
Decks tab (AND-DECKS), Settings tab (AND-SETTINGS), kanji screen, server or contract changes.

## Rules (all three P8 Android tickets)
- Register the tab by adding a FeatureEntry whose NavRoute uses the shell route string (`Routes.X`); it replaces the PlaceholderScreen via allRoutes. Edits outside your package: **one line** in `di/FeatureModules.kt` featureEntries (and only if strictly needed, one line in `nav/AppNav.kt`). Two sibling tickets add lines there too: expect trivial rebases.
- Every user-visible string goes through i18n (`i18n/T.kt`, your own `res/values/strings_<feature>.xml`). Additive lines in `i18n/T.kt` are allowed.
- Learning-data mutations only via sync ops (core OpMinter, the same path ReviewFeature/notes use). No direct writes to replica tables (CLAUDE.md rule 8).
- Offline first: read the local replica / on-device data where it exists; fall back to the API through `net/ApiClient.kt` (hand-written OkHttp; add methods there only additively, for endpoints already in `contracts/api/openapi.yaml`). No new server endpoints, no contract edits; if you need one, return CONTRACT_CHANGE_REQUEST.
- No new dependencies without a reason in HANDOFF.
- Gradle: run checks as `flock /tmp/kotobaya-gradle.lock make check MODULE=android` (one Gradle build at a time on this machine). Check the rc directly; never pipe through tail.
