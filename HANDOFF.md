# HANDOFF T112

STATUS: DONE
BRANCH: t/T112-backups
CHECK: `make check MODULE=deploy` → green. Restore drill run on k3d `kotobaya-t112`: `DRILL PASS` (cluster and images deleted).

## Changed
- `deploy/backup/`: image (alpine, restic, rclone, pg17), `backup.sh`, `verify.sh`, `restore.sh`, `durable_seq.sql`, `restore-k8s.sh`, `restore-drill.sh`, `check.sh`, README.
- `templates/backup.yaml`: nightly backup, weekly backup-verify, suspended restore CronJob. `values.yaml`: `backup:` block only (default `enabled: false`).
- Order: dump → scratch-restore (E, S, counts read from the dump) → blob mirror after dump + every `app.blob` row checked → restic snapshot `pending` → `restic check` → `verified` → `durable_seq` (monotonic, only if live epoch == E).

## Acceptance → tests
- Drill passes → `restore-drill.sh` asserts: durable_seq = S, verify passes, injected corrupt counts fail the Job and reach the ntfy sink, mismatched-epoch/lower-S writes nothing, after restore log_epoch changed, durable_seq = S, api ready, data == backup, post-backup rows gone, blob restored.
- Templates render with/without `backup.enabled`, no secrets → `deploy/backup/check.sh` (also JDBC conversion, count compare).

## New dependencies
- Image only: restic, rclone, postgresql17-client, jq, curl (alpine 3.21).

## For the reviewer
- Edited outside owned paths (needed): `deploy/helm/check.sh` (one line calling `../backup/check.sh`). Needs from others: build/push a `backup` image in `deploy/build/release.yml`; wire `api.yaml` to set `BACKUPS_DISABLED=true` when `backup.enabled=false`.
- "Backups disabled": server does not read any setting. Chosen env `BACKUPS_DISABLED=true` (api); T202 must read it and report durable_seq = high_water_seq. Until then durable_seq stays 0 (safe).
- T111 BUG: SeaweedFS in `objectstore.yaml` preallocates 1 GiB per volume (7 volumes per collection): first upload consumed ~15 GB real disk and filled `/`. Add `-master.volumePreallocate=false -volume.max=40` (verified in drill).
- Restic repo target in DECISIONS is empty; drill uses an in-cluster S3 bucket. Backup frequency is a HUMAN_CHECKS item (README). K8s netpolicy refuses brand-new pods for ~seconds, so scripts `wait_net`.
