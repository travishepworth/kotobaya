# HANDOFF T112 (attempt 3)

STATUS: DONE
BRANCH: t/T112-backups (rebased on main)
CHECK: `make check MODULE=deploy` → green. Restore drill on k3d `kotobaya-t112`: `DRILL PASS` (cluster and images deleted).

## Changed
- Major: daily `<rel>-backup-watchdog` CronJob (`watchdog.sh`, own ServiceAccount + Role `get` on the two CronJobs; kubectl added to the image). Alerts and fails on: no/stale verified snapshot, backup or backup-verify suspended by hand, or suspended by a restore for more than 3 h (annotation `kotobaya/suspended-by-restore` now holds the epoch time). `restore-k8s.sh` suspends only backup + backup-verify, never the watchdog. Render assertions in `check.sh`.
- Crash safety: `restore.sh` clears `SCRATCH_DB` before the renames (restored copy named `<db>_restoring_*`), keeps the pre-restore DB; `restore-k8s.sh` drops it by a cleanup Job (`RESTORE_MODE=cleanup`) only after the api rolled out (server finalized). On start, a missing live DB with exactly one `<db>_pre_restore_*` is renamed back automatically; several candidates stop with instructions (exit 4). README documents manual cleanup.
- Minor: `check_fresh` parses restic times to epoch seconds (no string comparison).
- Drill additions: watchdog passes when healthy; job killed between the renames (`KILL_BETWEEN_RENAMES=1` drill hook, SIGKILL) leaves live DB missing, both copies kept, CronJobs suspended, watchdog (STUCK_SECONDS=0) fails and alerts; re-run recovers, data == backup, new epoch, CronJobs resumed, no leftover DBs.

## New dependencies
- kubectl (alpine package) in the backup image, for the watchdog.

## For the reviewer
- Unchanged open items: `deploy/helm/check.sh` calls `../backup/check.sh`; build the `backup` image in release.yml; api `BACKUPS_DISABLED` wiring (T202).
- Watchdog alerts on the first day until the first nightly backup exists (no verified snapshot yet). OOMKill path still only covered by staleness logic, not drilled.
