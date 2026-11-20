# LF-A7 HANDOFF — DONE

Branch t/LF-A7 (from t/LF-int).

- `ShellSyncStatus.first_sync {done, applied, total}` now emitted by `SyncStatusData.toJson` (CoreDeviceViews.kt). `FirstSync.of` (SyncStatusRepository.kt): done = `last_sync_at != null` (never cleared), applied = stored pull cursor, total = server head learned from pull progress (null until first sub-page; in-memory only, so null again after restart until the first sub-page of the resumed pull), clamped >= applied, = applied when done. `SyncStatusView.knownHead` added (default null).
- BridgeCore: handlers > 250 ms logged (`KotobayaLoad` tag, "slow handler: <operationId> took N ms", no body) via `onSlow` param (default logs with android.util.Log, runCatching). I did not touch ScreenLog.
- Tests: `sync/FirstSyncStatusTest` (rising/done/never back, interrupted resume), `shell/web/SlowHandlerLogTest`. Both pass.
- "App open triggers a background cycle": not changed here (SyncFeature.kt/SyncWork.kt belong to LF-A1b); I did not verify that wiring.
- No new dependencies.

Remaining android failures (expected, LF-GOLD): RouteTableTest.everyLocalOperationIsRegisteredOrPending, LocalParityTest (2 tests). No new failures. Ran `:android:testDebugUnitTest` only, not `make check`.
