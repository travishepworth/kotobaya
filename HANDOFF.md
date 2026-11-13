# HANDOFF T303e

STATUS: DONE
BRANCH: t/T303e-sync-ui
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `sync/ui/`: SyncUiModel (chip mapping, reason strings, grouping, RemintGate), SyncContent (chip, Sync screen, Rejected list, Re-mint screen), SyncUiFeature (stateful flow in a full-screen Dialog, Koin `RejectedOpsService`, `syncUiFeature`).
- Out-of-path edits: `FeatureModules.kt` (one registration line); `sync/SyncStatusRepository.kt` (`unreachable` field, `onOutcome(…, unreachable)`); `sync/SyncFeature.kt` (passes `unreachable`); `sync/SyncWork.kt` (Syncing notification string); `res/values/strings.xml`.

## Acceptance → tests
- Chip text per state, grouping under parent, confirm disabled until list shown, hidden with reason, 200 items → `SyncUiTest` (6 tests).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No NavRoute used (chip and the Settings section open a Dialog flow with its own back stack) since slots have no NavController.
- `RemintPreview` constructor is internal, so screens take a `RemintView` copy; the real preview handle is kept and passed to `remint()`. "List shown" = user opened the list (not needed when nothing is dropped). Re-mint hidden while phase is ClockWrong.
- Chip precedence: rejected count outranks offline/server states.
