# HANDOFF F-4

STATUS: DONE
BRANCH: t/F-4-backup-l
CHECK: `make check MODULE=deploy` → green (k3d restore drill not run here; extended, `bash -n` only)

## Changed
- `backup.sh`: after the durable_seq write, tags the verified snapshot `L:<n|null>` (L = value written in the lock-7033001 transaction; `epoch:`/`seq:` tags already existed). Lock, watchdog, exclusion, rename safety untouched.
- `lib.sh`: `data_loss_check` (live readable, same epoch, durable_seq > S → durable_seq−S; else max L of same-epoch retained backups > S → L−S).
- `restore.sh`: `choose_snapshot` refuses (exit 2, names seqs) without `--accept-data-loss` / `RESTORE_ACCEPT_DATA_LOSS=yes`; new `RESTORE_MODE=preflight`.
- `restore-k8s.sh`: preflight Job before scaling anything down (a refusal leaves api/CronJobs untouched); `--accept-data-loss` flag. README updated.

## Acceptance → tests
- Both warnings → `deploy/backup/check.sh` (fake restic/psql, 17 cases) and new steps in `restore-drill.sh`.

## New dependencies
- none

## For the reviewer
- L is a restic tag, not a file in the snapshot (restic tag rewrites id; found via epoch/seq tags). Older snapshots without an L tag count as no info. Snapshots lacking epoch/seq tags are refused.
- Older-snapshot choice still needs RESTORE_CONFIRM_OLDER in addition.
