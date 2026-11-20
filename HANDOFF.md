# HANDOFF LF-A8

STATUS: DONE (partial, as instructed: cards + presets + note types; 14 ops remain)
BRANCH: t/LF-A8 (from origin/t/LF-int)
CHECK: `:android:testDebugUnitTest` → 699 run, 4 fail, all already failing: RouteTableTest.everyLocalOperationIsRegisteredOrPending (now lists only the remaining ops plus resolveSpeech), LocalParityTest x2 (goldens not recorded), ParityExpectTest (only `queue-keep-going-target`, not LF-A8)

## Changed
- `shell/api/{cards,presets,notetypes}/`: 9 local writes through `LocalWrites` with the server's checks (CardMints reused)
- `LocalApi.kt`: 3 route groups added to `LocalRoutes.all`

## Acceptance → tests
- card/preset/note-type parity scenarios, no upstream calls → `*WritesTest.*LocalScenarioPassesWithoutTheUpstream`
- op schema, device's own ops, out-of-order convergence → `mintedCardOps/PresetOps/NoteTypeOpsValidateAndConvergeInAnyOrder`
- idempotency → `createTwice*`, `deleteTwiceMintsOneOp`
- refusals (409 in_use / builtin_immutable, 422, 410, merge survivor, 413) → `refusalsMintNothing`, `tombstones*`, `aMergeLoser*`, `anOpOverMaxOpBytesIs413*`
- offline + read-your-writes, route table → `LocalWritesOfflineTest`

## Remaining
confusables (3), grammar (7), media (4), media pull-apply tests. Not done: replaying ops through `model.py` (the android tests have no harness for it). `UiPathNeverTouchesNetworkTest.STILL_FORWARDS` should drop the 9 (not my path).

## New dependencies
none

## For the reviewer
- Built-in seeded keys come from `InMemoryLearningTables.seeded()`. `updateNoteType` checks body errors after 404/410, as the server does.
