# HANDOFF F-1

STATUS: DONE
BRANCH: t/F-1-decks-fixes
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- DeckItemApi.kt: removed `node!!` (explicit validated check instead).
- DeckApi.kt: KDoc on `create` — an existing `id` with a differing type stays 200 (openapi defines no 409 there).
- DecksApiTest.kt: 5 new tests (limits, refused item writes, deck.updated convergence, `deck:` on smart, id/type retry).

## Acceptance → tests
- Out-of-range limit/items → 422 `out_of_range` (already matched openapi Limit/DeckItemsAdd) → `outOfRangeLimitsAndItemCountsAreOutOfRange422`
- POST/DELETE items on smart/media/list → `addAndRemoveItemsOnSmartMediaAndListDecksAreRefused`
- Two-device deck.updated → `concurrentDeckUpdatesFromTwoDevicesConvergeWhateverTheArrivalOrder`
- `deck:` resolves another smart deck → `aSmartDeckQueryCanReferToAnotherSmartDeckWithDeck`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `SmartDecks.cardIds` is consumed by review `QueueLoader` (deck membership), not by GET /cards; GET /cards uses `CardSearch.compile`, which resolves `deck:` itself.
- The convergence test asserts per-property register merging (newer write wins per property).
