# HANDOFF SHELL-A2

STATUS: DONE
BRANCH: t/SHELL-A2-local-api
CHECK: `make check MODULE=android` → green (3rd run; runs 1–2 hit the existing flaky `SyncCoordinatorTest.concurrentTriggersRunOneSync`, see below)

## Changed (≤ 6 lines)
- `shell/api/`: `ShellHttp` (request/response, `Fallthrough`, query parse), `ApiOperations` (139 ops + matcher), `Problems`, `LocalRoute`/`LocalCall`/`ShellServices`, `LocalApi` (gates, `Upstream`, `OfflineUpstream`), `LocalWrites`/`WritePlan`
- Empty `Routes.kt` in identity, review, actions, mining, browse, decks, struggle, device
- `shell/ShellModule.kt` (Koin wiring) and one line in `di/FeatureModules.kt`

## Acceptance → tests
- route table vs ops.json, PENDING → `RouteTableTest` (everyLocalOperationIsRegisteredOrPending, pendingOnlyShrinks, noRouteIsRegisteredForAProxyOrHiddenOperation)
- 403/503/401/422 → `DispatchTest` (hiddenAnswers403…, proxyWithTheDefaultUpstream…, unpairedAnswers401…, unknownQueryParameterIs422…, unknownBodyPropertyIs422…)
- refused mint / committed write → `LocalWritesTest` (aRefusedMint…, aCommittedWrite…)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- ops.json isn't a bundled resource. That would need `android/build.gradle.kts`, which I don't own. The classes and paths are a Kotlin table instead, and a drift test checks it against ops.json, openapi.yaml and shell.yaml.
- Routes are top-level vals, so handlers get their dependencies through `call.services` (Koin). A local op with no handler yet goes to `Upstream`; an unregistered shell op answers 404. `local_or_proxy` is rebuild-gated before its handler runs.
- `PENDING` is in `RouteTableTest`: each L ticket must be allowed to delete its own line there. Separate problem (FOLLOWUP): `SyncCoordinatorTest.concurrentTriggersRunOneSync` is flaky, failing about 1 run in 3. A trigger launched late on `Dispatchers.Default` runs a second cycle. That file is outside my paths.
