# Backups and restore

Contract: `contracts/sync/SYNC.md` 6.2 and 6.3, `contracts/db/SCHEMA.md` 4.4. The tool is restic
(encrypted, off-cluster repository). Everything runs from one image built from `deploy/backup/`
(`docker build -t <registry>/backup:<tag> deploy/backup`; chart value `backup.image`).

## What runs

| Job (chart, `backup.enabled=true`) | Script | When |
|---|---|---|
| `<release>-backup` CronJob | `backup.sh` | nightly (`backup.schedule`) |
| `<release>-backup-verify` CronJob | `verify.sh` | weekly (`backup.verifySchedule`) |
| `<release>-backup-restore` CronJob, suspended | `restore.sh`, started by `restore-k8s.sh` | never on a schedule |

`backup.sh`: pg_dump (custom format) -> restore into a scratch database (C.UTF-8) -> read `E`
(log_epoch), `S` (max change.seq) and the row counts **from that restored dump** -> mirror the blob
bucket **after** the dump (never deletes) and check every `app.blob` row has its file in the mirror ->
one restic snapshot (dump, `counts.json`, blobs), tagged `pending`, `restic check`, retagged `verified`
-> only then, in the live database, `durable_seq = S` (monotonic, and only if the live `log_epoch`
still equals `E`; `durable_seq.sql`). Any failure writes nothing to `durable_seq`, notifies and exits
non-zero. Retention keeps the newest 14 verified snapshots (`backup.keepLast`); unverified ones are
dropped. The mirror comes after the dump, so a restored database never references a blob the
snapshot lacks (blobs are immutable and a row is inserted only after its object is stored).

`verify.sh` restores the newest verified snapshot into a scratch database and compares
`app.change` max seq, `review_log`, `note`, `card`, `ref.dict_entry`, `blob` counts, `log_epoch` and
the V1 Flyway checksum with the values recorded at dump time, and checks the blobs. It never touches
the live database. A mismatch notifies and fails the Job.

## Setup

Create the Secret by hand (never in git); the name is `backup.secretName` (default `kotobaya-backup`):

```
kubectl -n kotobaya create secret generic kotobaya-backup \
  --from-literal=RESTIC_REPOSITORY=<off-cluster restic repository URL> \
  --from-literal=RESTIC_PASSWORD=<long random password; store it somewhere other than the cluster> \
  --from-literal=AWS_ACCESS_KEY_ID=... --from-literal=AWS_SECRET_ACCESS_KEY=...   # for an s3: repository
  [--from-literal=NOTIFY_URL=https://ntfy.example.invalid/<topic>]
```

Then `helm upgrade ... --set backup.enabled=true`. The repository URL is `docs/DECISIONS.md` >
"Off-cluster backup target" (still empty: HUMAN_CHECKS). The Postgres user needs `CREATEDB` (the
bundled one is a superuser; with an external Postgres grant it). Failure notification: `NOTIFY_URL`
(Secret key, preferred) or `backup.notify.url`, an ntfy topic URL; the script POSTs the message and the
Job also fails, so the failure is visible in `kubectl get jobs` either way.

## Restore

```
deploy/backup/restore-k8s.sh      # NS=kotobaya REL=kotobaya
```

1. Scales api and worker to zero. 2. The restore Job restores the newest verified snapshot (drops and
recreates the database, `pg_restore`, copies the blobs back) and sets `restore_pending = '1'`; it
compares the restored counts with `counts.json`. 3. Scales api and worker back; the api finalizes at
startup (new `log_epoch`, `durable_seq = max(change.seq)`, raised HLC) before it reports ready.
If the Job fails the workloads stay down; fix the cause and re-run (it restarts from step 1).
Restoring an older snapshot loses ops devices already purged, so it needs
`RESTORE_SNAPSHOT=<id> RESTORE_CONFIRM_OLDER=yes` and prints a warning.

**Known limit** (FOLLOWUPS T005): after a restore, ops that were not made by devices (REST edits,
job-originated ops such as `media.created`, `*.published`, suggestions) since the last backup are lost;
device ops are re-pushed by the devices (log reset, SYNC.md 6.2). Re-run jobs as needed.

## Restore drill

```
CLUSTER=kotobaya-t112 deploy/k3d/up.sh
CLUSTER=kotobaya-t112 deploy/backup/restore-drill.sh
k3d cluster delete kotobaya-t112
```

It seeds data, runs the backup (asserts `durable_seq = S`), runs `backup-verify` (passes), injects
corrupt counts (the Job must fail and the notification must arrive at an in-cluster sink), checks the
`durable_seq` write rules, mutates the database and deletes a blob object, restores, and asserts:
`log_epoch` changed, `durable_seq = S`, api ready, data equals the backup, post-backup rows gone,
blob restored; then a second backup and verify pass on the new epoch. It prints `DRILL PASS`.
Needs about 3 GB free (see the SeaweedFS note in the T112 handoff: preallocation can take 15 GB).
Offline checks (`make check MODULE=deploy` runs them): `deploy/backup/check.sh`.

## Backups disabled

With no backups there is nothing to restore from, so a device may purge acked ops at once; the server
must then report `durable_seq = high_water_seq` (SYNC.md 6.3). The server does not read a setting yet.
Chosen name: env `BACKUPS_DISABLED=true` on the api (`api.env.BACKUPS_DISABLED`). Until the server
reads it, `backup.enabled=false` leaves `durable_seq` at 0 and devices keep acked ops forever
(safe, just never purged). With `backup.enabled=true` leave it unset.

## HUMAN_CHECKS

- Backup frequency: nightly 03:17, verify Sundays 04:47 (values). Confirm, or change.
- Set the off-cluster restic repository and credentials; confirm the first nightly backup landed
  off-cluster; run the restore drill once; keep `RESTIC_PASSWORD` outside the cluster.
