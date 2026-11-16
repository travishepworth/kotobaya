# LOOP-AND — Android: local QueueRoute + native counts through ReviewLoop

- Phase: P10 · Model: sonnet · Risk: med
- Deps: LOOP-CORE, LOOP-SRV (goldens), SHELL-L2/L3 state as merged
- Branch: t/LOOP-AND-review-loop
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/queue/**, android/src/main/kotlin/kotobaya/android/shell/api/review/QueueRoute.kt, android/src/main/kotlin/kotobaya/android/{widget,notify}/** (count source only), android/src/main/kotlin/kotobaya/android/review/{ReviewSession.kt,ReviewFeature.kt} and home/ui/TodayScreen.kt (compile fixes only), the `LOCAL_API_VERSION` constant, android/src/test/kotlin/kotobaya/android/{queue,shell/api/review,review,widget,notify}/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: yes (Home count vs widget vs web)

## Read
- Design: docs/REVIEW-LOOP.md §4, §6, §8, §9
- Contracts: SHELL.md 4.3, 7.2, 8; openapi `ReviewQueue.loop`; core `ReviewLoop.kt`
- Decisions: ADR-037, ADR-036

## Do
1. `LocalQueueLoader`: `LoadedQueue` carries `LoopResult` (`DefaultReviewLoop.evaluate`); delete `laterLearning()` and the hand-made `QueueCounts`; `newShownThisSession` parameter removed from the loader API (callers pass nothing); entry window `B = max(1, batch)`. Materialization rounds unchanged.
2. `QueueRoute`: render `loop` and the legacy fields from `LoopState` exactly like the server (byte parity after SHELL.md 8.3 normalisation); delete the second "normal" build.
3. Widget and due notification: the number shown is `readyLearning + readyReviews`; text for later today uses `laterToday` / `nextDueAt`. No keep-going state on the native side.
4. Legacy Compose review/Today (until SHELL-Z1): keep compiling against the new loader; no behaviour work.
5. `LOCAL_API_VERSION = 2`.

## Acceptance (each bullet → at least one named test)
- [ ] Shell parity test reproduces all goldens of design §9 including the four `queue-loop-*`
- [ ] `QueueRouteTest.loopUnlimitedLaterLearningServesNew` (A1 on the SQLDelight replica, offline)
- [ ] `WidgetDataTest.countIsReadyNow` (26 later + 9 ready → 9) and the same for `DueNotifications`
- [ ] `KeepGoingTest` updated or removed with the reason in HANDOFF (session cap is gone)
- [ ] `flock /tmp/kotobaya-gradle.lock make check MODULE=android` green

## Out of scope
SPA screens (LOOP-WEB); orientation (PORTRAIT-AND); deleting legacy Compose (SHELL-Z1).

## Notes from previous attempts
