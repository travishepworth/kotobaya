# HANDOFF LF-A5

STATUS: DONE (one wiring line outside owned paths, see reviewer)
BRANCH: t/LF-A5
CHECK: `make check MODULE=android` → red only by the known pre-existing failures: RouteTableTest, ParityExpectTest, LocalParityTest (5 cases per flavor; 619 run). No new failures.

## Changed (≤ 6 lines)
- `search/ReplicaCardQuery.kt` (new): `CardQuery` that never returns `Forward`; C-24 forms via L-7 steps 1-2 (package `seq IN` collection over the form PK, then sidecar cache), core's `instr` seq list rewritten to an indexed `IN (CTE)`.
- `search/CardSearch.kt`: `LocalCardSearch` uses it (no cap; smart decks now resolve).
- `browse/QuerySelection.kt` (new) `cardsMatching`; `ListCardsRoute`, `DrillRoute` query branch use it.

## Acceptance → tests
- examples parity, failing Upstream → `SearchNeverForwardsTest.everySearchExampleIsAnsweredOnTheDeviceWithTheServersIds`
- > 50 000 entries, stable pages → `LargeDictionaryTermTest.aTermMatchingMoreThan50000EntriesAnswersQuicklyAndPagesStably`
- missing package → `SearchNeverForwardsTest.withoutADictionaryPackageTheSidecarCacheAloneAnswersLocally`, `withNeitherPackageNorCacheTextTermsMatchNoteTextOnly`
- rewrite guard → `SeqListRewriteTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- NOT OWNED, needed: `shell/ShellModule.kt:175` and `parity/ParityDevice.kt:255` must bind `ReplicaCardQuery(...)` (same args) instead of `LocalCardQuery`; then LF-A4 deletes `LocalCardQuery` + `CardQueryResult.Forward` (actions/). Until then production still forwards.
- Risk: `indexSeqLists` text-matches core's SQL; `SeqListRewriteTest` fails if core changes it.
