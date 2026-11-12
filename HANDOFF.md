# HANDOFF T205

STATUS: DONE
BRANCH: t/T205-queue-builder
CHECK: `make check MODULE=core` → green (queue tests run on jvm and js)

## Changed (≤ 6 lines)
- `core/src/commonMain/kotlin/kotobaya/queue/DefaultQueueBuilder.kt`: rules 1–5 as in the contract KDoc (tiers, round-robin, deckless source, add_kanji incl. `decklessAddKanji`)
- `core/src/commonMain/kotlin/kotobaya/queue/DefaultWordStatusRules.kt`: auto status + effective
- `core/src/commonTest/kotlin/kotobaya/queue/`: QueueBuilderTest, QueuePropertyTest, WordStatusRulesTest

## Acceptance → tests
- Rule table (limits, tiers, round-robin, deckless/ADR-006, bury, interleave, session cap, add_kanji, midnight/DST) → `QueueBuilderTest.*` (35 tests)
- Determinism → `QueuePropertyTest.sameInputGivesIdenticalQueue`, `inputOrderNeverChangesTheQueue`, `invariantsHoldOnRandomInputs`
- Auto status → `WordStatusRulesTest.knownOnlyWhenAllStartedCardsAreReviewWithInterval21OrMore`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The contract has no seed, so "different seeds" is tested as shuffled input order, which must leave the queue unchanged.
- Choices the contract leaves open (KDoc): null `dueAt` counts as overdue; memberships naming unknown decks are not enabled; a card sorts before a pending entry at equal key; duplicate `entrySeq` is materialized once.
- Not checked here (caller's job, documented): candidates must be live, and `n:` items and notes must already be resolved through merges.
