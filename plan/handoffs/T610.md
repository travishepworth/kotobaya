# HANDOFF T610

STATUS: DONE
BRANCH: t/T610-runbook
CHECK: not run (docs only; ticket says no `make check` needed). Every cited script, path, values key, API route and symbol was grepped.

## Changed (≤ 6 lines)
- `docs/RUNBOOK.md` (new): install/secrets, routine checks, stuck jobs, alerts, backups, restore (restore-k8s.sh, SYNC 6.2-6.3), restore drill table, manual triggers, incidents.

## Acceptance → tests
- every cited command/script exists → grep pass (scripts, values keys, openapi paths, code symbols)
- restore and drill sections match `deploy/backup/` and SYNC 6.2-6.3 → manual cross-read

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Gaps stated in the runbook, not fixed: no FSRS weight reset/rollback path exists in the contract; the chart renders no job-stuck or disk alert; no dict-refresh CronJob; backups are disabled by default and no restic target is set.
- restore-drill.sh switches kubectl context (k3d-<CLUSTER>); the runbook says to switch back.

## If BLOCKED
QUESTION: none
