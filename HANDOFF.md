# HANDOFF AND-DECKS

STATUS: DONE
BRANCH: t/AND-DECKS-decks-tab
CHECK: `make check MODULE=android` → green

## Changed
- New `android/.../decks/` (DeckRepository over the replica via raw SqlDriver, DecksFeature screens), `strings_decks.xml`, one line in FeatureModules.
- Review: `QueueSource.forDeck(id)` (default no-op), `asQueueSource()` overrides it with `load(n, deckId)`; `ReviewBackend.newSession(deckId=null)`, `ReviewScreen(..., deckId=null)`.
- AppStartsTest: Decks no longer a placeholder; added `stopKoin()` @After (order-dependent Koin clash with AppStartStoreTest).

## Acceptance → tests
- Counts == `load(deckFilter)`, study loads only that deck → `DeckRepositoryTest`
- No-deck review unchanged → existing review tests green

## New dependencies
- none

## For the reviewer
- NavRoute content takes no args, so list/detail/study are internal state of the `decks` route (study embeds ReviewScreen with deckId), not a `decks/{id}` nav route. No AppNav/T.kt/ApiClient edits needed.
- Words come from replica notes (label = expression, else item key); no API fallback. Smart decks have no resolver on device, so counts are empty (note shown). Create/rename/delete deferred (read-only).
