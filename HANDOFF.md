# HANDOFF T112 (attempt 2)

STATUS: DONE
BRANCH: t/T112-backups (rebased on main)
CHECK: `make check MODULE=deploy` → green. Restore drill on k3d `kotobaya-t112`: `DRILL PASS` (cluster and images deleted).

## Changed (review fixes)
- Major 1: `restore-k8s.sh` suspends backup/backup-verify CronJobs, waits for their pods, scales down (fails if pods remain), resumes after `rollout status`; `backup.sh` exits 3 with an alert if `restore_pending` is in the live DB or the dump.
- Major 2: `durable_seq.sql` runs in one transaction under `pg_advisory_xact_lock(7033001)`, requires no `restore_pending` and live epoch == E.
- Major 3: jobs run under `tini -g` plus a TERM trap (alert on deadline/delete); `check_fresh` (newest verified snapshot older than `backup.staleHours`=52): `verify.sh` fails, `backup.sh` alerts and continues (so it can repair). Memory 2Gi, `workSize`/`tmpSize` limits documented.
- Minors: restore into temp DB, compare, set restore_pending, rename swap (live untouched on failure); `restic check --read-data-subset=10%` plus dump sha256 read-back before tagging verified; README (external PG must own DB); drill asserts refusal under `restore_pending`, CronJobs resumed, envFrom Secret.
- `objectstore.yaml`: `-master.volumePreallocate=false -volume.max=40` (permanent).

## Acceptance → tests
- Drill (`restore-drill.sh`): all attempt-1 asserts plus restore_pending guards (backup fails + alert; durable_seq.sql skips). `deploy/backup/check.sh`: staleness, SQL shape, tini/STALE_SECONDS/preallocate render.

## New dependencies
- tini in the backup image.

## For the reviewer
- Still needed elsewhere: `deploy/helm/check.sh` calls `../backup/check.sh` (edited); build the `backup` image in release.yml; api.yaml `BACKUPS_DISABLED=true` when backups off (server must read it, T202).
- Not drilled: OOMKill/eviction path (covered only by the unit-tested staleness check) and the race itself (guards asserted, not interleaved).
