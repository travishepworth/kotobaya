# LOOP-31-API handoff
Status: DONE (server check red only on pre-existing parity goldens, see below)

- `ReviewApi.kt`: parses `keep_going_new_target` (decimal `0..2147483647`, no sign/fraction; `invalid_value` / `out_of_range`, pointer `/keep_going_new_target`, in the one 422). It is validated always but passed only when `keep_going=true`. `QueueLoader.kt` forwards it as `QueueLimits.keepGoingNewTarget`; the batch stays `limit`. Materialization rounds unchanged.
- Test: `ReviewApiTest.keepGoingNewTargetStopsAtTheTarget` (serves up to target; at target goal_reached + keep_going_available true; empty/false when no new cards remain; ignored without keep_going; 422 cases). Passes; all other review tests pass unchanged.
- Clients: `make check MODULE=contracts` green, no drift (nothing to regenerate).
- `make check MODULE=server`: 13 failures, all in `ParityGoldenTest`, none in my paths: unrecorded goldens `queue-keep-going-target` (C-31), `bulk-cards-all-actions-local`, `bulk-schedule-cards-local`, `card-writes-local`, `confusable-writes-local`, `grammar-writes-local`, `note-type-writes-local`, `preset-writes-local`, `resolve-speech-local`, `stats-heatmap-retention-local`, `stats-summary-local`, plus `executed scenarios = scenario files` and `everyGoldenBelongsToAScenario`. I did not record (golden dir is outside my owned paths); needs a RECORD=1 pass by whoever owns parity goldens.
- No new dependencies.
