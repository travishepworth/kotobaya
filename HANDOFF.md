# HANDOFF LF-A5

STATUS: BLOCKED (one test line outside owned paths)
BRANCH: t/LF-A5 (rebased on origin/t/LF-int)
CHECK: `make check MODULE=android` → red. 664 run, 5 failed: the known RouteTableTest (1), ParityExpectTest (1), LocalParityTest (2, missing goldens/pending ops), plus NEW `actions/BulkQueryWiringTest` (1). Gradle stopped after the ankidroid flavor.

## Changed (≤ 6 lines)
- `search/ReplicaCardQuery.kt` (new): `CardQuery` that never forwards. Dictionary forms come from L-7 steps 1-2 (package `seq IN` collection over the form PK, then the sidecar cache). Core's `instr` seq list is rewritten to an indexed `IN (CTE)`.
- `search/CardSearch.kt`: `LocalCardSearch` uses it (no cap; smart decks now resolve).
- `browse/QuerySelection.kt` `cardsMatching`; `ListCardsRoute` and the `DrillRoute` query branch use it.
- Approved: `ShellModule.kt:175` and `ParityDevice.kt:255` bind `ReplicaCardQuery`.

## Acceptance → tests
- examples parity, failing Upstream → `SearchNeverForwardsTest.everySearchExampleIsAnsweredOnTheDeviceWithTheServersIds`
- > 50 000 entries, stable pages → `LargeDictionaryTermTest.aTermMatchingMoreThan50000EntriesAnswersQuicklyAndPagesStably`
- missing package → `SearchNeverForwardsTest.withoutADictionaryPackageTheSidecarCacheAloneAnswersLocally`, `…NeitherPackageNorCache…`
- rewrite guard → `SeqListRewriteTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Risk: `indexSeqLists` matches core's exact SQL text; `SeqListRewriteTest` fails if core changes it.

## If BLOCKED
QUESTION: `android/src/test/kotlin/kotobaya/android/shell/api/actions/BulkQueryWiringTest.kt:65` asserts `koin.get<CardQuery>() is LocalCardQuery`, which fails now that the real binding changed. May I change it to `is ReplicaCardQuery` (plus its import)? Or should LF-A4 make that change?
