# HANDOFF T203

STATUS: DONE
BRANCH: t/T203-replay
CHECK: `make check MODULE=core` → green (jvm + js)

## Changed (≤ 6 lines)
- `replay/DefaultReplayEngine.kt`: `DefaultReplayEngine` (order, replay, fold, decide; `SCHEDULER_VERSION = "fsrs6-1"`) and `DefaultReviewLogRules` (SYNC 7.9). Both go through FsrsScheduler and DefaultHlcRules.compare only.
- `replay/IncrementalReplay.kt`: an in-memory replay set with a checkpoint per row. Out-of-order inserts and undos recompute from the insertion point.
- Tests in `commonTest/kotobaya/replay/`: a table-driven replica simulator plus a seeded history generator.

## Acceptance → tests
- incremental = full (random arrival, drills, undos, manual rows, params change) → `ReplayPropertyTest.incrementalEqualsFullReplayUnderRandomArrival`, `foldOfLastRowEqualsFullReplay` (1000 cases)
- golden vectors → `ReplayGoldenTest.goldenVectorsThroughReplay`
- drill/cram inert; weights mismatch → `drillAndCramNeverChangeACache`, `ReplayEngineTest.weightsVersionMismatchForcesFullReplayWithNewWeights`, `decideBranches`
- jvm/js identical → `ReplayGoldenTest.replayDigestIsIdenticalOnJvmAndJs` (jsNode ran it and passed)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Follow-up from T005: ordering by `reviewed_at` means a device clock that runs ahead (within tolerance) can put a causally later review first. I implemented the contract as written. The ordering is the same on every replica, so they still converge.
- A replay set with duplicate ids, or a rated row with no rating, throws IAE. T107b minors are outside replay/, so they are not done here.
