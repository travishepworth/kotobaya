# HANDOFF T202b

STATUS: DONE
BRANCH: t/T202b-sync-server
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `server/.../sync/`: `OpApply`/`OpTx` (lock 7033001, `hlc_last` clock, `emit()` → `OpInputInvalid` 422 / `OpRefused`), `OpRules` (5.3 order), `OpApplier` (all 45 ops, 7.1–7.9, 8.2/8.3 apply, media.deleted cascade)
- `PushService`, `PullService`, `SyncModule.handlers`, `AppendOnlyGuard`, `ReplaySets`; hooks: `CardCacheHook`/`AutoStatusHook` (no-op), `MergePolicy` (T202c seam), `SyncEvents`
- NOT wired (Main.kt not mine): add `SyncModule(db, SyncConfig(events = { d, hw, _ -> jobs.bus.syncChangedInTx(d, hw) }))` and its `handlers`

## Acceptance → tests
- 12.1/12.2/12.4, raw-push → `ScenarioTest.workedExample12_*`, `RawPushExamplesTest`, `PushHttpTest.sameOpIdEqualContents…`
- Idempotency/out-of-order → `OpApplyTest.rePushingABatch…`, `anOpBeforeItsCreator…`, `aTombstoneBeforeAnOlderEdit…`, `PullTest.theSameBatchPushedTwiceConcurrently…`
- Append-only → `OpApplyTest.updatingOrDeletingChangeOrReviewLog…`; scope → `PushHttpTest.aReviewOpPushedWithoutTheReviewScope…`
- Pull, `hlc_last` restart → `PullTest.pullReturnsExactly…`, `OpApplyTest.hlcLastIsPersisted…`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Merge scenarios (12.3, 7 files) need 8.1 detection (T202c); without it a colliding write answers 503, batch rolled back.
- U+0000 in a payload and int4 overflows → `invalid_value` (Postgres can't store them; contract gap, not 500).
- No jOOQ codegen (rows via `to_jsonb`/`jsonb_populate_record`); `Kotobaya-Seq` absent on idempotent retry.
