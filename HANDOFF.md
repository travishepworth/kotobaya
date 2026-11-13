# HANDOFF T304b

STATUS: DONE (T303c-dependent parts deferred, see reviewer notes)
BRANCH: t/T304b-review-ui
CHECK: `make check MODULE=android` and `MODULE=core` → green

## Changed
- android/.../review/: ReviewSession (mint review.logged/undone via OpMinter, queue rebuild, undo, token idempotency), CardPage (HTML + request policy), ReviewScreen (one reused JS-off WebView, grading bar, swipe, audio, summary, sync prompt), ReviewPrefs, ReviewBackend seam, reviewFeature (one line in di/FeatureModules.kt).
- Out-of-path: res/values/strings_review.xml; AppStartsTest (Review no longer a placeholder); core/.../sync/client/apply/AutoStatus.kt wired into DeviceOpApplier + StartupReplay, with core AutoStatusTest (carried-in auto_status).

## Acceptance → tests
- buttons/5-button/rating/offset/cache=replay → ReviewSessionTest (eachFourButton…, fiveButtonMode…)
- undo all four states, double undo, refused card + refused replay still commits → ReviewSessionTest
- WebView classes/ruby, no network URL → theRenderedPage…, theRequestPolicy…
- auto_status → core AutoStatusTest; core fuzz still converges.

## New dependencies
- none

## For the reviewer
- T303c is not merged: no SQLDelight store/`sync/` package exists, so tests use InMemoryDeviceStore (no "kill + reopen DB" test) and the screen needs a `ReviewBackend` that T303c must register in Koin (else shows "not available"). Not done: sync status refresh off committing thread, readiness gate exposure, awaits in queue/notify/widget (`ReviewBackend.awaitReady` is the seam).
- Seams left null: DictionaryLookup (T302), BlobResolver (T310; no audio until then). Swipe: right=Good, left=Again, only on answer side. Server's AutoStatusHook is still NoOp, so server/device status differ until wired.
- WebView itself is not exercised (Robolectric); policy is a pure function.
