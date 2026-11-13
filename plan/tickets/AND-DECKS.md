# AND-DECKS — Android Decks tab

- Phase: P8 · Model: sonnet · Risk: med
- Deps: none
- Branch: t/AND-DECKS-decks-tab
- Owned paths (only edit these): `android/src/main/kotlin/kotobaya/android/decks/` (new), `android/src/main/res/values/strings_decks.xml`, `android/src/test/kotlin/kotobaya/android/decks/`, one line in `android/src/main/kotlin/kotobaya/android/di/FeatureModules.kt`, additive lines in `android/src/main/kotlin/kotobaya/android/i18n/T.kt`, additive methods in `android/src/main/kotlin/kotobaya/android/net/ApiClient.kt`, minimal additive changes in `android/src/main/kotlin/kotobaya/android/review/ReviewFeature.kt` (+ its route) to accept an optional deck id
- Module(s) for CI: android

## Read
- Spec: decks and review-queue sections of docs/SPEC.md; Android section
- Contracts: contracts/api/openapi.yaml decks operations (listDecks, getDeck, deck items) and review queue (deck filter); contracts/sync ops for decks (only if you add a mutation)
- Existing code: `android/src/main/kotlin/kotobaya/android/nav/AppNav.kt` (Routes.DECKS="decks"), `android/src/main/kotlin/kotobaya/android/review/ReviewFeature.kt`, `android/src/main/kotlin/kotobaya/android/di/FeatureModules.kt`, `android/src/main/kotlin/kotobaya/android/queue/LocalQueueLoader.kt` (`load(deckFilter)`), `android/src/main/kotlin/kotobaya/android/sync/SqlLearningTables.kt`, LocalQueue.sq; web decks screen `web/src/routes/decks/` for what the web already offers

## Do
1. Decks tab at route `decks`: list the user's decks (normal and smart) from the local replica, with due / new / learning counts per deck from LocalQueueLoader (deck filter), plus total cards. Refresh after sync.
2. Deck detail (`decks/{id}`): name, type, counts, smart-deck query (read-only), the deck's words (paged; from the replica, or the API deck items endpoint if items aren't replicated), and a **Study this deck** button.
3. Study this deck: open the review screen with the deck filter (extend ReviewFeature's route with an optional deck id argument, defaulting to today's behaviour). The review session uses `LocalQueueLoader.load(deckFilter)`.
4. Deck creation/rename/delete only if the deck ops already exist in contracts/sync and can be minted via OpMinter; otherwise leave them for later and say so in HANDOFF. No new server endpoints.

## Acceptance
- [ ] Test: deck counts shown for a deck equal `LocalQueueLoader.load(deckFilter)` counts on a replica fixture with two decks, and starting a deck review loads only that deck's cards.
- [ ] Review with no deck argument behaves as before (existing review tests stay green).
- [ ] `make check MODULE=android` green.

## Out of scope
Search tab, Settings tab, web or server changes, deck sharing/import.

## Rules (all three P8 Android tickets)
- Register the tab by adding a FeatureEntry whose NavRoute uses the shell route string (`Routes.X`); it replaces the PlaceholderScreen via allRoutes. Edits outside your package: **one line** in `di/FeatureModules.kt` featureEntries (and only if strictly needed, one line in `nav/AppNav.kt`). Two sibling tickets add lines there too: expect trivial rebases.
- Every user-visible string goes through i18n (`i18n/T.kt`, your own `res/values/strings_<feature>.xml`). Additive lines in `i18n/T.kt` are allowed.
- Learning-data mutations only via sync ops (core OpMinter, the same path ReviewFeature/notes use). No direct writes to replica tables (CLAUDE.md rule 8).
- Offline first: read the local replica / on-device data where it exists; fall back to the API through `net/ApiClient.kt` (hand-written OkHttp; add methods there only additively, for endpoints already in `contracts/api/openapi.yaml`). No new server endpoints, no contract edits; if you need one, return CONTRACT_CHANGE_REQUEST.
- No new dependencies without a reason in HANDOFF.
- Gradle: run checks as `flock /tmp/kotobaya-gradle.lock make check MODULE=android` (one Gradle build at a time on this machine). Check the rc directly; never pipe through tail.
