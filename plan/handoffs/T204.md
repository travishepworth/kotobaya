# HANDOFF T204

STATUS: DONE
BRANCH: t/T204-decks
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- server/.../kotobaya/decks/: `DecksModule(db, opApply).handlers` with 10 operations (DeckApi, DeckItemApi, DeckViews, SmartDecks, DeckLists). Every write is `OpApply.transaction` + `emit`. Wiring line for Main.kt: `handlers += DecksModule(db, sync.opApply).handlers`.
- `getDeckCoverage` is NOT registered: stays the 501 `not_implemented` stub (coverage is Phase 4).
- `SmartDecks.cardIds(dsl, deckId, nowMs, studyDay)` evaluates a smart deck via `SearchSqlCompiler` (Postgres); parse errors are 422 `invalid_query`.

## Acceptance → tests (DecksApiTest, 8 tests)
- Smart membership + invalid_query → `aSmartDeckSelectsItsCardsThroughTheCompiler…`
- Two-device convergence → `concurrentItemPutsAndRemovalsFromTwoDevicesConverge…`
- Delete cascade → `deletingADeckHidesItsItemsButKeepsTheRows…`
- Merged note resolves to survivor → `itemsKeyedToAMergedAwayNote…`
- Also CRUD/idempotency, read-only types, list enabling, scopes.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- JLPT lists have no vocabulary source in the DB: the catalogue reports `item_count` 0 for them; `freq-<id>-<N>` counts come from `ref.entry_freq` (`_` in a list id becomes `-`). `list` create enqueues the `deck-build` job in the same transaction.
- Reuses internal helpers from `kotobaya.notes` (Errors, Reply, rest, send). Out-of-range limits and priority are `invalid_value` 422. Put/add/remove on a deleted deck is 410. Removal emits `item_removed` for every live key of the item's effective identity (SYNC 8.5).
