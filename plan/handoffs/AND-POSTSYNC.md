# HANDOFF AND-POSTSYNC

STATUS: DONE
BRANCH: t/AND-POSTSYNC
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- Root cause: `LocalQueue.sq` `todayReviews.introduced_new` was SQL NULL for an ordinary second learning answer today (`NULL = 'reset'`). The generated `getBoolean(3)!!` threw an NPE, so every queue load (Home, Decks, Review, widget, due notification) failed once today's web reviews were pulled. Fixed with `IS 'reset'`.
- `StateViewModel.launchIo`: failures are logged as `KotobayaLoad` and passed to `onFailure`; they no longer crash the app (this was the unlogged crash).
- `DecksViewModel`: `loadFailed` banner. Sync reloads skip progress-only changes, are conflated, run one at a time, ≥1.5 s apart.
- `KotobayaSync` log lines on gate open and every sync outcome.

## Acceptance → tests
- Fails before fix (NPE) → `LocalQueueLoaderTest.aSecondLearningAnswerTodayIsNotIntroducedNewAndDoesNotBreakTheLoad`, `PostSyncLoadTest.afterALargeChunkedPullTheGateOpensAndTheScreensLoad`
- Progress while busy → existing sync chip; failure shows a banner, not a skeleton

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `launchIo` now swallows failures in all VMs. Review stays on Loading if its load fails, because review/ui belongs to AND-INSETS.
- Hypotheses 1–3 not supported: 98 pulls / 5 metas fits one chunked full pull.
