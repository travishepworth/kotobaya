# HANDOFF SHELL-L7

STATUS: DONE
BRANCH: t/SHELL-L7-local-decks
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `shell/api/decks/`: listDecks, getDeck, createDeck, updateDeck, deleteDeck, listDeckItems, addDeckItems, putDeckItem, removeDeckItem. The 422 checks and their order are ported from the server's DeckApi and DeckItemApi.
- Item visibility, identity and removal use core `MergeReaders` (the readers native `DeckMembership` uses). Mint requests are copied into `DeckMints`. Every write goes through `LocalWrites.exclusive`.
- createDeck `list` → Fallthrough (503 offline); a retry with an existing `id` gets 200 locally. `media` → 422 /type.
- Entry checks in add/put use `DictLookup.resolveEntry`, the same as `updateNote`. An authoritative miss → 422; a miss the device cannot decide → 503 shell_dict_missing.
- RouteTableTest: the PENDING list no longer has the deck ops. ParityDevice needed no new services.

## Acceptance → tests
- Parity list/get/items and each write → `DeckParityTest` (server answers written out by hand)
- Ops validate, duplicate add, missing remove, delete then older pulled put, out-of-order convergence → `DeckOpsTest`
- Count relation → `DeckCountsTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `Deck` has no counts field, so `DeckCountsTest` checks `getReviewQueue?deck_id=` and native `DeckRepository` against `getReviewQueue`: they are equal, and deck ≤ queue ≤ sum over decks.
- There are no deck parity scenarios in `contracts/api/shell/parity`. Adding them and recording their goldens needs a contracts plus SHELL-P1 follow-up.
- Keyset cursor for items (the server uses offset). With mixed bad elements, the add can name a different element than the server.
