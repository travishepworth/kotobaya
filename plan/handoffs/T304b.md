# HANDOFF T304b (attempt 2)

STATUS: DONE
BRANCH: t/T304b-review-ui (rebased on main; syncFeature still last in FeatureModules)
CHECK: `make check MODULE=android` and `MODULE=core` → green

## Changed
- android/.../review/: ReviewSession (mint review.logged/undone, queue rebuild, undo, token idempotency), CardPage (HTML + request policy), ReviewScreen (one JS-off WebView, grading, swipe, audio, summary, sync prompt, sync after session), ReviewPrefs, ReviewBackend, reviewFeature (Koin: real backend over SyncClient store/minter, LocalQueueLoader, BlobStore, SyncBootstrap.ready, SyncTriggers).
- Out-of-path edits: res/values/strings_review.xml; FeatureModules.kt (+1 line); sync/SyncFeature.kt (status refresh now `scope.launch`, off the committing thread); queue/LocalQueueLoader.kt + QueueModule.kt (`awaitReady` gate on load/forecast, blocks on SyncBootstrap.ready); AppStartsTest; core sync/client/apply/AutoStatus.kt (+DeviceOpApplier/StartupReplay hooks) with AutoStatusTest (carried-in auto_status).
- BlobResolver wired to T310 BlobStore (file by hash, `touch` on play).

## Acceptance → tests
- buttons/5-button/cache=replay, undo (4 states, double), refused card/replay, WebView classes + no network → ReviewSessionTest
- kill + reopen DB → ReviewDurabilityTest (SQLite file via SqlDevice)
- auto_status → core AutoStatusTest

## New dependencies
- none

## For the reviewer
- Gate for DueNotifications/widget is covered through LocalQueueLoader (both read via it); no separate edits there.
- Seam still null: DictionaryLookup (T302 on-device dictionary). Server AutoStatusHook is still NoOp, so server vs device status differ until wired.
- WebView not exercised under Robolectric; policy is a pure tested function.
