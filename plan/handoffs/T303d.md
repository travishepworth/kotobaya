# HANDOFF T303d

STATUS: DONE
BRANCH: t/T303d-remint
CHECK: `make check MODULE=core` → green (jvm + js)

## Changed (≤ 6 lines)
- `sync/client/rejected/`: `RejectedOpsService` (list/grouping, Retry, Discard, discardAll, clock repair, preconditions)
- `sync/client/remint/`: `RemintSelection` (1a/1b/1c, W/C/D/E/R/U, E order, 6a re-key/double removal), `Reminter`, `RemintPreview`, `RemintOutcome`
- Tests: commonTest `remint/`, `rejected/`; jvmTest `RemintScenariosDriftTest` embeds `remint-scenarios/`

## Acceptance → tests
- 4 scenario files (3 remint-scenarios and clock-behind-remint; 13 table cases) → `RemintScenarioTest` (minted ops equal the log byte for byte)
- §12.6/§12.7 → `RemintWorkedExamplesTest`
- Discard list, stale handle, D under parent → `RemintActionsTest`
- Preconditions, clock repair, monotone HLCs → `RemintActionsTest`, `RejectedOpsServiceTest`, `RemintFuzzTest`
- Atomicity, Refused → rollback → `aRefusedMintRollsTheWholeRemintBack`, `anExceptionInside…`
- Fuzz with 110 seeds → `RemintFuzzTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The preview handle is a content fingerprint (outbox + sync_state + undo flags), so an unchanged state stays valid. Re-mint must not run inside an outer tx.
- Finding (T303b): `card.created` after `fsrs_params.published` keeps `weights_version` 0 (lazy marker). The fuzz compares after `replayAll` (SYNC §11.3). T303c must replay before reading.
