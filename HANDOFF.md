# HANDOFF T303a

STATUS: DONE
BRANCH: t/T303a-sync-client-protocol
CHECK: `make check MODULE=core` → green (jvmTest + jsTest)

## Changed (≤ 6 lines)
- `sync/client/store/`: `DeviceStore<L>`/`StoreTransaction` (outbox, sync_state, learning `L`), `InMemoryDeviceStore`, `OpApplier`, `Clock`, `OpIdSource`/`UuidV7Source`, `SyncStateKeys` + `SyncBookkeeping`
- `sync/client/mint/OpMinter`: guards, server skew, rebuild, emitter, `decodeTree`+`envelopeErrors`, `max_op_bytes`; apply+insert+`hlc_last` in one tx; `mintAll` is all-or-nothing
- `sync/client/protocol/`: `SyncEngine` (`cycle`/`pushAll`/`pullAll`/`purge`), `SyncTransport`, `SyncLimits`, `CycleResult`, `SyncStatus`, `SyncBackoff`
- Tests: embedded scenarios `ClientScenarios.kt`, kept current by the jvm `ClientScenariosDriftTest`

## Acceptance → tests
- 12.1–12.4, raw-push → `WorkedExamplesTest.s12_1…s12_4`, `RawPushTest`
- Idempotency → `idempotencyPushingTheSameOutbox…`, `aReplayedPullPageAppliesNothingTwice`
- Crash → `crashBetweenApplying…LeavesNeither`, `killMidPush`
- Purge → `ackedRowSurvivesUntil…`, `durableSeq…NotMixedWithTheOld`
- Limits/upgrade → `maxOpBytesBelowOneMiB…`, `unknownPulledOp…`, `invalidPulledOp…`, `aPullPageLargerThanMaxPushBytesIsAccepted`
- Fuzz → `ClientConvergenceFuzzTest` (120 seeds, 3–4 devices, faults)

## New dependencies
- none. Tests use a stdlib `startCoroutine` runner instead of kotlinx-coroutines.

## For the reviewer (≤ 3 lines)
- Device-local keys `meta.max_op_bytes`/`meta.max_push_bytes`/`client.server_skew_ms` are not contract keys. T301b's `MetaLimitsStore` must use the same `meta.*` names.
- Results are matched by `op_id`, not by position. Rows with no result stay pending (+1 attempt). A `LogChanged` on push keeps the recorded acks; T303b's reset stores the new epoch.
- Kotobaya-Seq follow-ups don't apply to sync endpoints, so nothing was done for them.
