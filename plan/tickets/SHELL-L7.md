# SHELL-L7 — Local decks: read, items, create/update/delete

- Phase: SHELL · Model: opus · Risk: med
- Deps: SHELL-L1, SHELL-P2
- Branch: t/SHELL-L7-local-decks
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/decks/**, android/src/test/kotlin/kotobaya/android/shell/api/decks/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 5
- Contracts: openapi.yaml (listDecks, getDeck, listDeckItems, addDeckItems, putDeckItem, removeDeckItem, createDeck, updateDeck, deleteDeck), contracts/sync/OPS.md (deck.*), contracts/db/VOCAB_LISTS.md
- Decisions: ADR-032, 036

## Do
Reads from `DeckRepository` / `DeckMembership` (per-deck counts come from the same `LocalQueueLoader` build as `getReviewQueue`, so Today and
Decks agree). Writes: reuse the mint requests of `DeckWordOps`, `SearchDeckOps` and those currently inside `DecksViewModel` (copy them into
this package; do not edit the ViewModel). Smart decks and `listDeckLists` stay proxied. Item keys `e:<seq>` / `n:<uuid>` validated as the contract.

## Acceptance (each bullet → at least one named test)
- [ ] Parity scenarios for list/get/items and each write
- [ ] `DeckOpsTest`: ops validate; add same item twice → one op / no duplicate row; remove missing item → success, nothing minted; delete deck then pulled older item-add → item stays gone (tombstone rule)
- [ ] `DeckCountsTest`: sum relation between `listDecks` counts and `getReviewQueue.counts` holds on the parity seed

## Out of scope
Coverage, deck lists (proxy).

## Notes from previous attempts

## Orchestrator note (from SHELL-A2)
Also owned: delete your ops' lines from PENDING in the android RouteTableTest (shell/api tests). Routes are top-level vals in shell/api/<group>/Routes.kt; get deps via call.services.
