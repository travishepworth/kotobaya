# HANDOFF T411c

STATUS: DONE
BRANCH: t/T411c-media-publish-runner
CHECK: `make check MODULE=server` → red, not from T411c: 331/333; targeted `kotobaya.media.publish.*` 23/23 green. The 2 failures are pre-existing after the C-11 merge: `ServerTest.flywayAppliesV1…` (expects 1 migration, V2 exists) and `ConformanceTest` (117 vs 120 ops). (Run with a 2g test heap via an init script outside the repo, F-11.)

## Changed (≤ 6 lines)
- `server/.../media/publish/`: `MediaPublishRunner` (claim, stale requeue, 3.3 transaction, 5.2), `SnapshotInstall`, `MediaParseReconciler` (4), `MediaPublishPayload` (closed schema), `SnapshotReader` (streaming, core JSON + zstd only)
- Tests: `SnapshotReaderTest`, `MediaPublishRunnerTest` (Postgres)

## Acceptance → tests
- Idempotency / crash / parent cancelled → `runningTheSameChildTwiceYieldsOneSetOfOpsAndIdenticalRows`, `aCrashMidTransactionLeavesNoRowsAndNoOps`, `parentCancelledNothingIsApplied`, `aLostClaimRollsBack…`, `aChildLeftRunningByADeadServer…`
- Order, v1 vs v2, parse_status → `firstPublishMints…`, `secondPublishMints…ParseStatusFollowsC11`, `theReconcilerEndsAStuckParsingStatus`
- Ids kept, old rows gone, manual spans → `sentenceIdsAreKeptOldRowsAreGoneAndManualSpansUntouched`
- Mapping queue → `jpdbPublishPopulatesTheMappingQueueAndNeverTouchesResolutions`

## New dependencies
- com.github.luben:zstd-jni:1.5.6-4 (server) — SNAPSHOT.md 2.8 reader; same artifact Android uses.

## For the reviewer (≤ 3 lines)
- `OpApplier.mediaCreated` (not owned) still emits a default `deck.created` follow-up, contradicting MEDIA_PIPELINE 3.3 step 6; runner detects it and adds one `deck.updated` (options). Follow-up: make mediaCreated return none().
- Re-claimed child with identical archives already published → succeeds, no ops (ticket), instead of `stale_base`.
