# HANDOFF SYNC-T1

STATUS: DONE
BRANCH: t/SYNC-T1-chunked-pull-tests
CHECK: `make check MODULE=core` → green (jvm + js)

## Changed (≤ 6 lines)
- SyncEngine.applyChunk: every chunk checks stored pull_seq == previous chunk's cursor, later chunks check log_epoch unchanged → Failed(PROTOCOL), chunk applies nothing.
- Server skew recorded on a page's first chunk only.
- DeviceOpApplier.open is @Volatile (kotlin.concurrent), with a single-writer comment; batch KDoc on word_status.updated_at corrected.
- ClientConvergenceFuzzTest: random pullChunkOps 1..5 per device.

## Acceptance → tests (SyncEngineTest, pullChunkOps = 2)
- unsupported op in chunk 2 → chunkedPullUnsupportedOpInChunkTwoCommitsChunkOneAndStopsJustBeforeIt, chunkedPullUnsupportedOpFirstInChunkTwoLeavesPullSeqAtChunkOnesLastSeq
- applier throw in chunk 2 → chunkedPullApplierThrowInChunkTwoKeepsChunkOneAndTheRetryEqualsAOneChunkPull
- no-op-only chunk → chunkedPullANoOpOnlyChunkAdvancesTheCursorAndObservesItsHlcs
- hardening → chunkedPullFailsAsProtocolWhenPullSeqMovesBetweenChunks, chunkedPullFailsAsProtocolWhenTheStoredLogEpochChangesBetweenChunks, chunkedPullRecordsServerSkewOnlyOnThePagesFirstChunk
- fuzz with random chunk size → ClientConvergenceFuzzTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The log_epoch/pull_seq checks are both done (ticket offered either). On a mismatch the cycle fails as PROTOCOL rather than LogChanged; the next cycle starts again from stored state.
