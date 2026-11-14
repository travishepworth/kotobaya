# HANDOFF AND-REVIEW-STALE

STATUS: DONE
BRANCH: t/AND-REVIEW-STALE-refresh
CHECK: `make check MODULE=android` -> green

## Changed
- `ReviewViewModel.refreshIfEmpty()`: only when the session is Empty, re-runs `start()` (one at a time; the queue load already coalesces via LoadGate). A showing card is never touched.
- Triggers: `ReviewBackend.dueChanged` flow (new, default empty), fed by `ReviewDueSignal`, a `DueListener` on the existing DueTrigger (sync pulled ops, app open, alarms); `ON_RESUME` observer in `ReviewRoute`; a timer to the next study-day start while Empty.

## Acceptance -> tests
- Empty -> non-empty after signal, and card in progress not restarted: `ReviewStaleTest`.

## New dependencies
- none

## For the reviewer
- DueTrigger fires after sync only when `pulledOps > 0`, so a sync that pulls nothing does not refresh (nothing new could be due from the server anyway).
- Uses deprecated-but-present `androidx.compose.ui.platform.LocalLifecycleOwner` (no new lifecycle-compose dep).
