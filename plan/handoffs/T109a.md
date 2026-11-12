# HANDOFF T109a

STATUS: DONE
BRANCH: t/T109a-jobs-server
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `kotobaya/jobs/JobRepo.kt`: `enqueue` (dedupe_key, caller id), `resolveImportRetry`, list/get/cancel, `prune`, retention constants.
- `kotobaya/jobs/Events.kt`: `EventHub`, `EventBus` (pg_notify, channel `kotobaya_events`), `PgListener`, `JobWatcher`.
- `kotobaya/jobs/JobsModule.kt`: handlers `listJobs`, `getJob`, `cancelJob`, `streamEvents`; `start(scope)` runs the listener, watcher, sync.changed tick and daily prune.
- Tests: `JobsRepoTest`, `EventsTest` (SSE over real Netty on port 0).
- NOT wired into `Main.kt` (not my path): `val jobs = JobsModule(db, config.databaseUrl)`, `AppDeps(handlers = jobs.handlers)`, `jobs.start(scope)`.

## Acceptance → tests
- SSE progress/hello/ping/types/slow consumer → `helloIsFirstWith...WithinASecond`, `terminalJobState...`, `pingIsSentWhenIdleAndTypesFilterApplies`, `slowConsumerIsDroppedAboveTheBufferLimit` (+ `syncChangedIsCoalescedToOnePerSecond`, `streamEndsWhenTheCredentialIsNoLongerValid`).
- Dedupe / import_id → `dedupeReturnsTheExisting...`, `importIdRetryReturnsTheExistingJob...`
- Retention → `retentionPrunesOldFinishedJobsButNeverImportRowsOrActiveJobs`
- Cancel → `cancelSemanticsFor...`; list/paging/internal kinds hidden → `listAndGetPage...`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Retention: terminal jobs pruned after 90 days; `media-import`, `jpdb-deck-import`, `jpdb-history-import` kept forever; queued/running never. Daily in-process.
- `job.updated` comes from polling `app.job` every 250 ms (worker needs no NOTIFY; contracts have no trigger); NOTIFY `job.updated` is only a "poll now" hint. Others (`note.created`, `device.*`, `sync.changed`) go through `EventBus.publishInTx` -> NOTIFY. Cancel sets status=cancelled at once; T109b heartbeats must be guarded by `status='running'`.
- Not done: T105b revocation (extension points `credentialValid` and `hub.closeToken(id)`; devices/T105b must call `bus.publish`), ntfy forwarding (no env/config for the ntfy base URL; needs a sink on the hub).
