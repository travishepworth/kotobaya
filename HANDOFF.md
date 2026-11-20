# HANDOFF LF-A6

STATUS: DONE
BRANCH: t/LF-A6 (from t/LF-int)
CHECK: `make check MODULE=android` → red only for the expected tests: RouteTableTest.everyLocalOperationIsRegisteredOrPending, ParityExpectTest (queue-keep-going-target), LocalParityTest (unrecorded goldens incl. resolve-speech-local; goldensAndScenariosAreOneToOne). No new failures.

## Changed
- dict/: `GapFillQueue` + `BackgroundGapFillQueue`; `DictLookup.resolveEntry/Kanji` now local, enqueue (urgent) on a real miss; `DictAutoInstall` (+worker, `DictChoice`); koin wiring.
- mining/: no forwarding anywhere (search/kanji words always local, 503 only with no package and empty cache; headwords 200 + `kotobaya-shell-partial: 1`; release 503 without package); new `resolveSpeech` route registered in mining `Routes.kt`.
- media/: `SpeechResolveQueue` (+Background impl, urgent support in `HttpSpeechResolver`), koin wiring.

## Acceptance → tests
- hit/miss, failing Upstream → DictFallthroughTest, DictSearchTest, ResolveSpeechRouteTest
- miss filled, event, second read hits → DictGapFillTest.theLookupNeverAsks…
- deck_item entries resolvable → DictGapFillTest.everyDeckItemEntryIsResolvableAfterGapFill
- auto-install skipped metered / over budget → DictAutoInstallTest

## New dependencies
none

## For the reviewer
- Wiring outside my paths (not done): `CoreDictOps.startInstall` must set `DictChoice.userChoseTop60k = (scope == "top60k")`. No bridge hook for `ShellEventHub.onSyncOutcome` to re-evaluate auto-install; I use an hourly UNMETERED periodic worker armed from `DictWork.ensurePeriodic` instead.
- Search misses are not enqueued (gap fill has no query kind); `shell.dict` fires only when `gap_fill` counts change, so a landed 404 emits no event. `DictLookup.remote` is now unused (kept for ParityDevice constructor).
