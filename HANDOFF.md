# HANDOFF SHELL-P2

STATUS: DONE
BRANCH: t/SHELL-P2-parity-android
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `android/src/test/kotlin/kotobaya/android/shell/parity/`: Normalizer (port of P1's, on core JsonValue), ParityScenario, ParityRunner (pending/ran, JSON-pointer diff, invariants), ParityDevice (JDBC SqlDelightDeviceStore + production `SyncClient`; seed/apply pulled via `SyncEngine.pullAll` from a scripted log, seq 1..n), ParityDictionary.
- ParityDictionary converts P1's `server/src/test/resources/dict/ref_fixture.sql.gz` into a Dict.sq package of scope `all`, then installs it into a `DictStore`. This is plain JDBC with no Robolectric.
- SyncCoordinatorTest.concurrentTriggersRunOneSync: test-only fix. The triggers now launch UNDISPATCHED, so every one joins before the gate opens. 10/10 reruns passed. There was no production double-sync bug: the late trigger had arrived after the cycle ended.

## Acceptance → tests
- Zero routes all pending, and the gate fails on a differing scenario → LocalParityTest.everyScenarioWithAllOperationsRegisteredMatchesItsGolden, withZeroRegisteredRoutesEveryScenarioIsPendingAndTheGatePasses, ParityHarnessSelfTest.theGateFailsWhenARegisteredScenarioDiffersAndNamesThePointer
- Wrong fake handler gives a JSON-pointer diff → ParityHarnessSelfTest.aDeliberatelyWrongHandlerIsAMismatchWithAJsonPointerDiff
- Seed uses the production path → SeedApplyTest.everySeedIsAppliedThroughThePullPathNotTheOutbox and applyStepsContinueTheSeqsAfterTheSeed. These check that pending_op is 0 in every state, pull_seq equals the number of ops, and every created row exists.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `InstalledDictionary` needs Android `SQLiteDatabase`, so it cannot run on the plain JVM. L5/L9 need a JDBC-capable lookup seam. The last known server release is `ParityDictionary.releaseId`, and no device state holds it yet.
- Handler services are type-keyed in `ParityDevice` (store, SyncClient, LocalWrites, Clock, DictStore). L tickets register any others there.
