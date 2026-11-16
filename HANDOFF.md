# HANDOFF ONE-CARD-SRV (attempt 2)

STATUS: DONE
BRANCH: t/ONE-CARD-SRV (from t/C-28-ONE-CARD-CORE)
CHECK: `make check MODULE=server` → green (491); `make check MODULE=acceptance` → green

## Changed (attempt 2)
- Major: QueueLoader now drops retired candidates (`CardKinds.isRetired`). So `laterLearning` in /review/queue and /stats/summary no longer counts them. Core rule 0 still applies; the builder's output does not change (QueueRetiredCardsTest).
- `/stats/summary` `next_due_at` and `/stats/forecast` now leave out retired cards (`ReadingSetting.notRetiredSql`).
- OpApplier: the re-derive moved into the `settings.set` apply. It runs only when the typed value changes, so a lost or same-value op is skipped.
- Attempt 1 is unchanged: the setting is passed to every generator and queue call, `kind` goes to the hook, createCard returns 422, there is a bool check, and a startup marker.

## Acceptance → tests
- Retired learning card: not counted in queue or summary, no `next_learning_due_at`, not in next_due_at or the forecast; the queue ends "done" before and after its due time → DeckMaterializeTest `aRetiredLearningCardIsNeverCountedAndTheQueueEndsDone`. It fails when the loader filter is removed.
- The skip → AutoStatusHookTest `aLosingSettingsSet…`.

## New dependencies
- none

## For the reviewer
- The core README says "loaders keep passing retired cards". Dropping them in the loader gives the same builder result, so the README sentence should be relaxed.
