# HANDOFF SHELL-L8

STATUS: DONE
BRANCH: t/SHELL-L8-local-struggle
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- shell/api/struggle/: Routes.kt (4 local routes), HotSources.kt (server's hot-note rule over the replica: core DefaultHotScorer + HotHistory), StruggleRoutes.kt (listHotWords, listHotKanji, listLeeches), DrillRoute.kt (createDrillSession: core DrillSelector, items via L2 QueueViews, read-only store).
- RouteTableTest: PENDING emptied. No ParityDevice change needed.

## Acceptance → tests
- Parity scenarios, three lists + hot_words drill → StruggleParityTest (shapes, order, 422 codes, cursors, window, undo)
- No ops/no table change → DrillNoOpsTest (all five sources; drill answer leaves card unchanged)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No contracts/api/shell/parity files exist for struggle, so scenarios are written in Kotlin (as L7). Hot kanji uses the installed dictionary's entry kanji (no dict entry: no contribution). `query` source forwards when CardQuery says Forward.
- Leeches / leech candidates scan all card rows (`all(CARD)`); fine for now, no range query exists on LearningTables.
