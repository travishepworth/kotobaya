# HANDOFF T111b

STATUS: DONE
BRANCH: t/T111b-deploy-fixes
CHECK: `make check MODULE=deploy` → green (k3d drill not run, per instruction)

## Changed (≤ 6 lines)
- api.yaml: BACKUPS_DISABLED=true when backup.enabled=false (skipped if api.env sets it). Makefile images/push + release.yml comment: backup image.
- restore-k8s.sh: refresh suspension timestamp on retry; per-run RESTORE_STAMP and DRILL passed to Job; next-steps message on rollout timeout.
- restore.sh: KILL_BETWEEN_RENAMES needs DRILL=1; pre-restore DB named with RESTORE_STAMP; cleanup drops only that DB.
- restore-drill.sh passes DRILL=1; README notes watchdog removed when disabled; check.sh grep/render assertions for items 2-7.

## Acceptance → tests
- items 2-7 → deploy/backup/check.sh (render + grep assertions)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Cleanup with no RESTORE_STAMP drops nothing (safe default). Check.sh covers 4-6 by grep, not by execution; the k3d drill is the real test and was not run.
