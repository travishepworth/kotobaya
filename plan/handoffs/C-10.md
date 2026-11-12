# HANDOFF C-10

STATUS: DONE
BRANCH: t/C-10-log-reset-pull-seq
CHECK: `make check MODULE=contracts` green; `core` green; `server` green

## Changed (≤ 6 lines)
- SYNC.md 6.2 log reset: also sets `pull_seq = "0"` in the same transaction (+ rationale); 10.3 step 1 note; 12.5 diagram notes; new 12.5.1 worked example; index row.
- tools/model.py: `_start_log_reset` sets `pull_seq = 0`; `Livelock` guard (>20 resets per sync cycle); `reset_keeps_pull_seq` flag reproduces the old rule.
- tools/validate.py: `restore_below_pull_seq_story`; SUMMARY.md C-10 entry.

## Acceptance → tests
- Restore below pull_seq converges; old rule livelocks → `restore_below_pull_seq_story` (both runs). Also checked with a scratch copy that reverts the model line: check.sh fails with "21 log resets in one sync cycle".

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- T303a must match exactly: one local transaction does acked→pending (`acked_seq=NULL`, `attempts=0`), stores `log_epoch` and `durable_seq`, sets `pull_seq="0"`, sets `rebuild_pending="1"`. Later responses compare `high_water_seq` against 0. Port 12.5.1 as a client test: review acked seq 3, `pull_seq` 3 after one cycle, `rebuild_pending` cleared.
- No schema or example files changed, so there are no embedding regenerations.
