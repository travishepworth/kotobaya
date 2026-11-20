# HANDOFF LF-A6

STATUS: DONE
BRANCH: t/LF-A6 (from t/LF-int)
CHECK: rebased on t/LF-int (LF-A5, LOOP-31-AND); `:android:testDebugUnitTest` red only for the expected RouteTableTest.everyLocalOperationIsRegisteredOrPending and LocalParityTest (unrecorded goldens incl. resolve-speech-local). No new failures.

## Changed
- dict/: `GapFillQueue` + `BackgroundGapFillQueue`; `DictLookup.resolveEntry/Kanji` now local, enqueue (urgent) on a real miss; `DictAutoInstall` (+worker, `DictChoice`); koin wiring.
- mining/: no forwarding anywhere (search/kanji words always local, 503 only with no package and empty cache; headwords 200 + `kotobaya-shell-partial: 1`; release 503 without package); new `resolveSpeech` route registered in mining `Routes.kt`.
- media/: `SpeechResolveQueue` (+Background impl, urgent support in `HttpSpeechResolver`), koin wiring.

## Acceptance → tests
- hit/miss, failing Upstream → DictFallthroughTest, DictSearchTest, ResolveSpeechRouteTest
- miss filled, event, second read hits → DictGapFillTest.theLookupNeverAsks…
- deck_item entries resolvable → DictGapFillTest.everyDeckItemEntryIsResolvableAfterGapFill
- auto-install skipped metered / over budget / user chose top60k → DictAutoInstallTest

## New dependencies
none

## For the reviewer
- Approved wiring done: `CoreDictOps.start(scope)` (DeviceSettingsOps.kt) calls `onScopeChosen`, setting `DictChoice.userChoseTop60k = (scope == "top60k")`. Test: DictAutoInstallTest.neverOverridesAUserWhoChoseTop60k. No bridge hook for `ShellEventHub.onSyncOutcome` to re-evaluate auto-install; I use an hourly UNMETERED periodic worker armed from `DictWork.ensurePeriodic` instead.
- Search misses are not enqueued (gap fill has no query kind); `shell.dict` fires only when `gap_fill` counts change, so a landed 404 emits no event. `DictLookup.remote` is now unused (kept for ParityDevice constructor).
