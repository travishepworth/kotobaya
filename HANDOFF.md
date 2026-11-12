# HANDOFF T202c

STATUS: DONE
BRANCH: t/T202c-sync-merge-restore
CHECK: `make check MODULE=server` → green (210 tests)

## Changed (≤ 6 lines)
- `sync/merge/DuplicateMerge` (8.1 MergePolicy, reserved HLC) and `CoreCardCache` (CardCacheHook on core `ReplayEngine`).
- `sync/restore/`: `DurableSeq` (`BACKUPS_DISABLED`, the name Helm sets in `api.yaml`), `CoreSchedulerReplay` (10.5 startup replay), `SyncWiring`.
- Registration lines in T202b files: `SyncConfig.backupsDisabled`; `logIdentity(…, backupsDisabled)` in OpApply/PushService/PullService.
- Wiring ticket: use `SyncWiring.startup(db)` and `SyncModule(db, SyncWiring.config(events))` in Main.kt. This replaces PLACEHOLDER_SCHEDULER_VERSION. Startup.kt is unchanged.

## Acceptance → tests
- 12.3 and all 16 scenarios, incl. the 7 merge ones → `MergeScenarioTest`
- 12.5 → `RestoreStoryTest`. 12.6 / 12.7 → `RemintStoriesTest`
- Same word on two devices; jpdb kanji vs phone → `MergeTest`
- durable_seq, hlc_last raise → `RestoreTest`. 10.5 and readiness → `StartupReplayTest`
- Fuzz (3 devices, seeds, `KOTOBAYA_FUZZ_RUNS`) → `ConvergenceFuzzTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Engine refusals are logged and the card is skipped; the push or startup still commits. `fsrs_params.published` replays its cards eagerly, under the lock.
- `RefReplica`/`SimDevice` port model.py as a test oracle. Re-mint gaps are device-side: no server change.
- `ScenarioTest.mergeScenariosAreLeftToT202c` (T202b's file) is now stale and can be removed.
