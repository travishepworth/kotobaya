# HANDOFF LF-A5

STATUS: DONE
BRANCH: t/LF-A5 (rebased on origin/t/LF-int)
CHECK: `testDebugUnitTest` (ankidroid) and `testOwnDebugUnitTest`: 664 tests each, 4 failed in each, all known: RouteTableTest (1), ParityExpectTest (1), LocalParityTest (2). No new failures.

## Changed (≤ 6 lines)
- `search/ReplicaCardQuery.kt` (new): a `CardQuery` that never forwards. Dictionary forms come from L-7 steps 1-2: the package (`seq IN` the collection's entries, via the form table's primary key), then the sidecar cache. Core's `instr` seq list is rewritten to an indexed `IN (CTE)`.
- `search/CardSearch.kt`: `LocalCardSearch` uses it (no cap; smart decks now resolve).
- `browse/QuerySelection.kt` adds `cardsMatching`; `ListCardsRoute` and the `DrillRoute` query branch use it.
- Approved: `ShellModule.kt:175` and `ParityDevice.kt:255` bind `ReplicaCardQuery`; `BulkQueryWiringTest.kt:65` asserts it.

## Acceptance → tests
- examples parity, failing Upstream → `SearchNeverForwardsTest.everySearchExampleIsAnsweredOnTheDeviceWithTheServersIds`
- > 50 000 entries, stable pages → `LargeDictionaryTermTest.aTermMatchingMoreThan50000EntriesAnswersQuicklyAndPagesStably`
- missing package → `SearchNeverForwardsTest.withoutADictionaryPackageTheSidecarCacheAloneAnswersLocally`, `…NeitherPackageNorCache…`
- rewrite guard → `SeqListRewriteTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `LocalCardQuery` and `CardQueryResult.Forward` are no longer used in production (left in place for LF-A4 to delete).
- Risk: `indexSeqLists` matches core's exact SQL text; `SeqListRewriteTest` fails if core changes it.
