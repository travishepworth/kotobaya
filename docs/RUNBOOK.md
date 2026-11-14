# Operator runbook

For the person running the Kotobaya deployment. Commands assume the release name `kotobaya` and the
namespace `kotobaya` (the scripts default to `REL=kotobaya`, `NS=kotobaya`). Check your `kubectl`
context before every command that changes something: no script here switches it.

Related documents: `deploy/backup/README.md` (backup and restore internals, the full restore rules),
`docs/SECURITY.md` (threat model, open findings S-01 to S-14), `contracts/sync/SYNC.md` 6.2-6.3
(log epochs and the backup/restore handshake), `contracts/api/openapi.yaml` (every endpoint).

## 1. Install and upgrade

### Secrets (created out of band, never in values)

Names come from `deploy/helm/kotobaya/values.yaml`. Create every Secret before `helm upgrade`.

| Secret (values key) | Keys | Needed when |
|---|---|---|
| `kotobaya-app` (`secrets.appSecretName`) | `DATABASE_URL` (JDBC form), `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `AUTH_PASSWORD_HASH` (required), `AUTH_TOTP_SECRET` (optional) | always |
| `kotobaya-postgres` (`secrets.postgresSecretName`) | `POSTGRES_PASSWORD` | `postgres.enabled=true` |
| `kotobaya-objectstore` (`secrets.objectStoreSecretName`) | `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `s3.json` | `objectStore.enabled=true` |
| `kotobaya-backup` (`backup.secretName`) | `RESTIC_REPOSITORY`, `RESTIC_PASSWORD`, repository credentials, optional `NOTIFY_URL` | `backup.enabled=true` |
| `kotobaya-ntfy` (`ntfy.secretName`) | optional `NTFY_TOKEN`, `NTFY_AUTH_*` | `ntfy.enabled=true` |
| `kotobaya-jpdb` (`worker.jpdbApiKey.secretName`) | `JPDB_API_KEY` | `worker.jpdbApiKey.enabled=true` |

The backup Secret is created by hand as shown in `deploy/backup/README.md` (Setup).

### External Postgres or S3

With `postgres.enabled=false` or `objectStore.enabled=false` you must list the external services in
`networkPolicy.egress.externalServices` (IP block and port). Rendering fails without it
(`deploy/helm/kotobaya/templates/networkpolicy.yaml`). The database must be created with a UTF-8 ctype
(see the comment on `postgres.*` in values). The database user needs `CREATEDB` when backups are on.

### Install or upgrade

```
helm upgrade --install kotobaya deploy/helm/kotobaya -n kotobaya -f <your-values-file> \
  --set imageRegistry=<registry>/kotobaya \
  --set api.image.tag=<tag> --set worker.image.tag=<tag> --set web.image.tag=<tag>
```

Put the domain, `api.publicUrl`, `ingress`/`httpRoute` settings and any `backup.enabled=true` in
your values file, not on the command line. Image tags are the chart defaults unless you set them.
The backup image is built separately (`docker build -t <registry>/backup:<tag> deploy/backup`) and
set with `backup.image`.

### Migrations

- `migrate.mode: initContainer` (default): each api pod runs `server migrate` as an init container
  before it starts, and retries until Postgres is reachable. This runs on every install and upgrade.
- `migrate.mode: hook`: a Helm `pre-install,pre-upgrade` Job named `kotobaya-migrate` runs the
  migration instead. Use it with an external Postgres that is already reachable.

If a migration fails, the api pods stay in `Init`, because the init container `migrate` exits non-zero
while Postgres is unreachable or the migration fails. Read it with
`kubectl -n kotobaya logs deploy/kotobaya-api -c migrate`.

### Network exposure

The ingress or Gateway must listen only on the VPN side. The chart cannot enforce this; the ingress
controller's Service or the Gateway does (`values.yaml`, "VPN ONLY"). Set `api.trustedProxies` to the
ingress pod CIDR: it is the only place `X-Forwarded-For` is trusted by the login rate limiter
(`values.yaml`).

## 2. Routine checks

| What | How | Healthy |
|---|---|---|
| Public health (API can reach Postgres) | `GET https://kotobaya.<domain>/api/v1/health` | 200 |
| API liveness / readiness | probes on `kotobaya-api`: `/healthz`, `/readyz` (port 8080) | `/readyz` 200 only after restore finalization and the scheduler check |
| Worker liveness / readiness | probes on `kotobaya-worker`: `/healthz` (port 8081) | 200 |
| Metrics | `GET /metrics` on the api pod (Prometheus) | scrape succeeds |
| Pods | `kubectl -n kotobaya get deploy,sts,cronjob,job` | every Deployment available; no failed Jobs |
| Job queue | `GET /api/v1/jobs?status=queued,running` (bearer `read`, or a session) | short list |

Logs are JSON on stdout (`kubectl -n kotobaya logs deploy/kotobaya-api`).

### How to check health

The API writes one JSON object per line (`level`, `logger_name`, `message`, `stack_trace` when there is
one). Every line written while serving a request carries `request_id` (the caller's `X-Request-Id` when
well formed, else a minted one; echoed in the response header and in problem bodies), and once the
credential is known `token.id`, `token.name` and, for device tokens, `device_id`. A healthy day has no
`ERROR` lines and only a few `WARN`s. Set `L="kubectl -n kotobaya logs deploy/kotobaya-api --since=24h"`.

| Look for | Command |
|---|---|
| Everything at WARN or worse | `$L \| grep -E '"level":"(WARN\|ERROR)"'` |
| Unhandled exceptions (500s, with stack) | `$L \| grep '"message":"unhandled error'` |
| Background loops and coroutines that crashed | `$L \| grep -E '"logger_name":"kotobaya\.(coroutines\|uncaught)"\|runner failed\|poll failed\|prune failed'` |
| 5xx responses (access log, WARN) | `$L \| grep '"logger_name":"kotobaya.request"' \| grep '"level":"WARN"'` |
| Slow requests (over 2 s) | `$L \| grep '"logger_name":"kotobaya.request"' \| jq -c 'select(.duration_ms > 2000) \| {path, status, duration_ms, request_id}'` |
| Everything about one request | `$L \| grep '"request_id":"<id>"'` (the id is in the problem body / `X-Request-Id` header) |
| One device's traffic | `$L \| grep '"device_id":"<device uuid>"'` |
| Sync op rejections (WARN; `reason` = SYNC.md 5.3 code) | `$L \| grep '"logger_name":"kotobaya.sync.ops"' \| jq -c 'select(.level=="WARN") \| {op_id, op_type, reason, reason_message, device_id}'` |
| Sync duplicates (INFO; client retries, normal in small numbers) | `$L \| grep '"message":"op duplicate"' \| wc -l` |
| Sync conflicts (WARN; duplicate notes/cards merged) | `$L \| grep '"message":"op conflict'` |
| Job results (one line per finished job) | `$L \| grep '"logger_name":"kotobaya.jobs.result"' \| jq -c '{level, message, job_kind, job_id}'` |
| Failed jobs (ERROR, with the worker's error) | `$L \| grep '"logger_name":"kotobaya.jobs.result"' \| grep '"level":"ERROR"'` |
| Jobs that dropped rows (WARN with counts: `unmapped_count`, `reviews_skipped`, ...) | `$L \| grep '"message":"job [^"]* dropped rows'` |
| Jobs that produced nothing from a non-empty input (ERROR; e.g. a jpdb import with 0 cards) | `$L \| grep '"message":"job [^"]* produced nothing'` |
| Server runners (media/deck publish, jpdb apply, fsrs publish) | `$L \| grep -E '"logger_name":"kotobaya\.(media\.publish\|decks\.publish\|imports\.jpdb\|optimizer\.publish)"' \| grep -E '"level":"(WARN\|ERROR)"'` |
| Auth failures and rate limits | `$L \| grep -E '"event":"auth\.(login\|pair)"' \| grep -v '"outcome":"ok"'` |
| Client errors reported by web/Android (ERROR/WARN, one line per item; `client`, `app_version`, `kind`, `route`, `client_request_id`, `token.name`) | `$L \| grep '"logger_name":"kotobaya.client_error"' \| jq -c '{level, message, client, app_version, kind, route, client_request_id, "token.name", request_id}'` |
| Clients that dropped reports, or were refused by the rate limit | `$L \| grep -E '"message":"client (dropped error reports\|error reports refused)'` |
| Worker failures | `kubectl -n kotobaya logs deploy/kotobaya-worker --since=24h \| grep -E 'ERROR\|Traceback'` |

Job result lines are written by the API when it sees a job reach a terminal state, so a job that
finished while the API was restarting has no line; check `GET /api/v1/jobs?status=failed` then.
Client-side error reports (web, Android) will appear here once the client-error endpoint (C-23) is
implemented.

### Stuck jobs

A `running` job whose `heartbeat_at` is older than 5 minutes is stuck (the worker heartbeats every
15 s; `worker/src/kotobaya_worker/runner/core.py`, `stale_after`). The worker requeues such a job
itself (`reclaim_stale`) while attempts remain, and fails it otherwise. To look for them directly:

```
kubectl -n kotobaya exec -i kotobaya-postgres-0 -- psql -X -U kotobaya -d kotobaya -c \
  "SELECT id, kind, status, heartbeat_at FROM app.job WHERE status = 'running' AND heartbeat_at < now() - interval '5 minutes'"
```

If a job stays stuck after the requeue, cancel it: `POST /api/v1/jobs/{job_id}/cancel` (bearer `import`).

### Alerts

What actually notifies you:

- Backup, backup-verify and watchdog scripts post to `NOTIFY_URL` (an ntfy topic) on failure. The
  watchdog reports stale or missing verified backups, an unreadable restic repository, backups
  suspended by hand, and backups left suspended by a failed restore (more than 3 h).
- A failed Job stays visible in `kubectl -n kotobaya get jobs`.

What the spec asks for but the chart does **not** render: a "job stuck" alert, a "disk above 80 %"
alert, and any Prometheus rules. Check job state with the queue query above, and disk with your
cluster monitoring or with:

```
kubectl -n kotobaya get pvc
kubectl -n kotobaya exec kotobaya-postgres-0 -- df -h /var/lib/postgresql/data
kubectl -n kotobaya exec kotobaya-objectstore-0 -- df -h /data
```

Only the backup Jobs have `emptyDir` size limits (`backup.workSize`, `backup.tmpSize`); other
emptyDirs have none (SECURITY S-13).

## 3. Backups

What runs, the restic repository and the safety rules are in `deploy/backup/README.md`. Summary:

- `kotobaya-backup` CronJob (nightly, `backup.schedule`, default `17 3 * * *`): dump, verify, mirror
  blobs, one restic snapshot tagged `verified`, then `durable_seq` is raised in the live database.
- `kotobaya-backup-verify` CronJob (weekly, `backup.verifySchedule`): restores the newest verified
  snapshot into a scratch database and compares counts.
- `kotobaya-backup-watchdog` CronJob (daily, `backup.watchdogSchedule`): never suspended by a restore.
- `kotobaya-backup-restore` CronJob: suspended. Only `restore-k8s.sh` starts it.

**Current state:** the chart default is `backup.enabled=false`, and no off-cluster restic repository
is recorded in `docs/DECISIONS.md` yet. Until one is set and the first nightly backup lands off the
cluster, there is no backup to restore from (`docs/SECURITY.md` §2, HUMAN). With backups disabled
the chart sets `BACKUPS_DISABLED=true` on the api, and the server does not read it yet
(`deploy/backup/README.md`, "Backups disabled").

### Confirm the last verified snapshot and durable_seq

1. Last backup log. Find the most recent `kotobaya-backup-<n>` Job (not `-verify`) and read its log. A good run logs
   `backup OK: snapshot <id>, S=<seq>` and `recorded L=... on snapshot <id>`. The `durable_seq` write
   logs `written durable_seq=<S>`, or `skipped: restore pending, or live log_epoch differs ...`.
   ```
   kubectl -n kotobaya get jobs
   kubectl -n kotobaya logs job/<job-name>
   ```
2. Watchdog. Run it by hand; it should print `watchdog OK: backups fresh and CronJobs active`.
   ```
   kubectl -n kotobaya create job --from=cronjob/kotobaya-backup-watchdog kotobaya-watchdog-manual
   kubectl -n kotobaya logs job/kotobaya-watchdog-manual
   ```
3. Live handshake values (SYNC.md 6.3):
   ```
   kubectl -n kotobaya exec -i kotobaya-postgres-0 -- psql -X -At -U kotobaya -d kotobaya -c \
     "SELECT key, value, updated_at FROM app.server_state WHERE key IN ('log_epoch','durable_seq','restore_pending')"
   ```
   `durable_seq` is the highest `seq` in the newest verified backup. `restore_pending` must be absent
   in normal running.
4. Snapshot list. Run `restic snapshots --tag verified` from a shell that has the `kotobaya-backup`
   Secret's variables set and the backup image's restic. Each snapshot carries `epoch:<E>`, `seq:<S>`
   and `L:<n>` tags.

## 4. Restore (real incident)

Use this only when the live database must be replaced by a backup. Read `deploy/backup/README.md`
"Restore" and "Data-loss warning" first.

### Step by step

1. Confirm the kubectl context is the cluster you mean to restore. `restore-k8s.sh` does not switch it.
2. Run the restore with the defaults. Override only what you need:
   ```
   NS=kotobaya REL=kotobaya deploy/backup/restore-k8s.sh
   ```
   - Older snapshot (loses ops devices already purged): `RESTORE_SNAPSHOT=<id> RESTORE_CONFIRM_OLDER=yes`.
   - Data-loss warning: add `--accept-data-loss` only when you accept the number of seqs it names.
   - `TIMEOUT` (default 1800 s) bounds the restore Job.
3. What the script does, in order:
   - **Preflight Job** (`RESTORE_MODE=preflight`). It checks the chosen backup against the live
     database and the backups' metadata before anything is stopped. A refusal exits 2 with nothing
     changed.
   - **Suspends** `kotobaya-backup`, `kotobaya-backup-verify` and `kotobaya-optimize` (if rendered),
     with annotation `kotobaya/suspended-by-restore`, and waits for their running pods.
   - **Scales** `kotobaya-api` and `kotobaya-worker` to 0 and waits for their pods to go. The
     replica counts are kept in an annotation for a re-run.
   - **Restore Job** (`kotobaya-restore-run-<timestamp>`). Restores the database and blobs into a temporary
     database, sets `restore_pending = '1'`, compares counts with `counts.json`, then swaps by renaming.
   - **Scales back** api and worker, then waits up to 600 s for the api rollout (`/readyz`).
   - **Cleanup Job** drops `<db>_pre_restore_<stamp>`, only after the server has finalized.
   - **Resumes** the suspended CronJobs and removes their annotation.
4. What the server does on first start (SYNC.md 6.2 step 2-3, 6.3 startup step 1). Under
   `pg_advisory_xact_lock(7033001)` it finalizes the restore in one transaction: new `log_epoch`
   (UUIDv7), `durable_seq = max(change.seq)`, HLC raised to the greatest of the restored `last`, every
   restored `change.hlc`, and now + 24 h, and `restore_pending` deleted. It logs this at WARN. The
   instance is not ready until this commits.
5. Verify:
   - `/readyz` on the api returns 200 and the `kotobaya-restore-run-*` Job shows `Complete`.
   - `log_epoch` in `app.server_state` differs from the value before the incident. `restore_pending` is absent.
   - `durable_seq` equals the `S` of the backup you restored.
   - The CronJobs are not suspended: `kubectl -n kotobaya get cronjob`.
   - Expect `created_at` and `updated_at` up to 24 h in the future on rows written after the restore
     (SYNC.md 6.2 step 3). This is expected until real time catches up.

### What devices do after an epoch change

Every device compares `log_epoch` on each response (SYNC.md 6.2). On a change it performs a log reset:
acked ops go back to pending, `pull_seq` is set to 0, `rebuild_pending` is set, and the device
re-pushes what it still holds. The server answers `duplicate` for ops that the backup contains and
`accepted` with a new `seq` for the rest. Then the device rebuilds from the new log. Devices need no
action. An op rejected with `unknown_reference` is retried automatically up to 10 times. A device op
that was purged before the backup is lost.

### What is lost

Ops minted by the server for REST callers and by jobs since the backup (REST edits, `media.created`,
`*.published`, suggestions) are lost (SYNC.md 6.3, `deploy/backup/README.md` "Known limit"). Re-run
the jobs you need (see section 6).

### If the restore fails

- Preflight refusal (exit 2): nothing was stopped. Read the warning and decide whether to re-run with
  `--accept-data-loss`.
- Restore Job failed: api and worker stay at 0 and the backup CronJobs stay suspended. The database
  may be half-restored. Fix the cause and re-run `restore-k8s.sh`; it is safe to re-run.
- Api not ready within 600 s: the restore succeeded, but the backup CronJobs stay suspended. Inspect
  `kubectl -n kotobaya logs deploy/kotobaya-api`. Then re-run the script, or resume by hand once the api
  is ready:
  ```
  kubectl -n kotobaya patch cronjob kotobaya-backup -p '{"spec":{"suspend":false}}'
  kubectl -n kotobaya patch cronjob kotobaya-backup-verify -p '{"spec":{"suspend":false}}'
  kubectl -n kotobaya annotate cronjob kotobaya-backup kotobaya/suspended-by-restore-
  kubectl -n kotobaya annotate cronjob kotobaya-backup-verify kotobaya/suspended-by-restore-
  ```
  Also resume `kotobaya-optimize` if it is rendered and was suspended. Drop the pre-restore database
  (`<db>_pre_restore_<stamp>`, named in the script's messages) by hand with
  `DROP DATABASE "<db>_pre_restore_<stamp>"` once the server is healthy.
- The live database is missing (the script died between the two renames): re-run `restore-k8s.sh`.
  `restore.sh` renames the single `<db>_pre_restore_*` copy back and restores again.

## 5. Restore drill (quarterly)

Run the drill on a throwaway k3d cluster, never on the production cluster. It needs about 3 GB free.
The drill script runs `kubectl config use-context k3d-<CLUSTER>`, so switch back to your real context
afterwards.
The script is `deploy/backup/restore-drill.sh`; it seeds data, takes a backup, injects failures,
restores and checks the result (`deploy/backup/README.md`, "Restore drill").

```
CLUSTER=kotobaya-drill deploy/k3d/up.sh
CLUSTER=kotobaya-drill deploy/backup/restore-drill.sh
k3d cluster delete kotobaya-drill
```

Expected output: a series of `ASSERT OK` lines (among them `backup-verify exited 0`, `ntfy sink received
the failure notification`, `backup refused while restore_pending is set`, `watchdog exited 0`), then a
final line of the form

```
DRILL PASS: epoch <E0> -> <E1>, durable_seq=<S>, data == backup (<counts>)
```

A failing assertion prints `ASSERT FAIL` on stderr and the script exits non-zero at once. Any other
ending is a failure. Record each run here:

| Date | Operator | Chart / image tags | Result (`DRILL PASS` or failing assertion) | Notes |
|---|---|---|---|---|
|  |  |  |  |  |
|  |  |  |  |  |

## 6. Manual triggers

All endpoints are under `/api/v1` and need a bearer token with the scope shown, or a session cookie
with the same scope.

- **Dictionary refresh** (`import`): `POST /api/v1/dict/refresh` with an optional body (`{}` by
  default; `freq_lists` selects frequency lists in priority order). It enqueues a `dict-refresh` job
  (dedupe key `dict-refresh`; an already queued or running one is returned). Sources are pinned in
  `worker.dictSourcesJson` (format: the docstring of `worker/src/kotobaya_worker/jobs/dict_refresh.py`).
  Note: the chart renders no CronJob for this; SPEC says monthly, but the refresh is manual today.
  Check the result with `GET /api/v1/jobs?kind=dict-refresh`.
- **FSRS optimizer** (`import`): `POST /api/v1/presets/{preset_id}/optimize`. It enqueues
  `fsrs-optimize:<preset id>`. The worker publishes new weights only when they are strictly better on
  the held-out reviews; the server then emits `fsrs_params.published`. The weekly schedule
  (`optimizer.enabled=true`, `optimizer.schedule`) enqueues the same job for every live preset. The
  optimizer is off by default; the worker needs RAM for it (`worker.resources`).
- **Cancel a job** (`import`): `POST /api/v1/jobs/{job_id}/cancel`.

## 7. Common incidents

### Token leak

1. List credentials: `GET /api/v1/tokens` (admin; `kind` and `active` filters). Find the token id.
2. Revoke it: `DELETE /api/v1/tokens/{token_id}` (admin). It is idempotent and takes effect at once;
   open `/events` streams of that token end.
3. Confirm that the token is listed as revoked (`GET /api/v1/tokens`) and that requests with it are refused.
4. A leaked login password or TOTP secret is a different case: change `AUTH_PASSWORD_HASH` or
   `AUTH_TOTP_SECRET` in the `kotobaya-app` Secret and restart the api
   (`kubectl -n kotobaya rollout restart deploy/kotobaya-api`). Sessions already issued are not ended by
   this; they stay valid until logout or expiry (30 days, `docs/SECURITY.md` S-08).

A `kbp_` token is also used by browser extensions (Yomitan). A leaked `write` key can upload blobs
that the Ingress path serves unsandboxed (SECURITY S-01). Revoke it first.

### Device lost

1. Find the device: `GET /api/v1/devices` (admin).
2. Revoke it: `DELETE /api/v1/devices/{device_id}` (admin). This sets `revoked_at` on the device and all
   its tokens, and ends the device's `/events` streams. Revoking an already revoked device returns 204.
3. Optionally stop push: `PATCH /api/v1/devices/{device_id}` with `push_topic: null`.

The device keeps its local data until it is wiped. Nothing else needs to change.

### Disk above 80 %

See section 2 (Alerts). Free space, or grow the PVC if your storage class allows it.
Do not let Postgres or SeaweedFS fill up: the backup mirror needs room for the dump plus every blob
(`backup.workSize`). Raise `backup.workSize` when the media grows, or the backup Job is evicted.

### VOICEVOX down

Only text-to-speech uses VOICEVOX (`voicevox.enabled`; the NetworkPolicy lets only the worker reach it).
TTS jobs fail; reviews, imports and the rest of the API keep working.

```
kubectl -n kotobaya get deploy kotobaya-voicevox
kubectl -n kotobaya logs deploy/kotobaya-voicevox
kubectl -n kotobaya rollout restart deploy/kotobaya-voicevox
```

### FSRS optimizer published bad weights

The worker publishes only when the new weights beat the current ones on held-out reviews, and the
server applies the result as a `fsrs_params.published` op (FSRS_PUBLISH.md). The contract has **no**
path that resets a preset's weights to the defaults or rolls them back: `PATCH /presets/{id}` changes
options only, and no endpoint writes weights. So the remedies are limited:

1. Stop further publishes: set `optimizer.enabled=false` and `helm upgrade`. Cancel a running
   `fsrs-optimize` job with `POST /api/v1/jobs/{job_id}/cancel`.
2. A later run publishes only if it is strictly better on the held-out set. It cannot undo a publish.
3. A rollback needs a contract ticket (a reset or rollback op). Until then, restoring an older backup
   is the only route, and it has the data-loss consequences of section 4.

Until a contract ticket adds a reset path, treat a bad publish as an incident and keep the optimizer off.

## 8. Known open security items

`docs/SECURITY.md` §4 lists them with severity. Relevant to operations:

- S-01 (HIGH): blob responses are sandboxed only through the chart HTTPRoute. An Ingress deployment, or a
  Gateway without the chart filters, serves uploads unsandboxed. Add the same headers on the gateway, and
  issue `write` tokens only where needed.
- S-03 (MED): the app uses the Postgres superuser. A role split is a HUMAN migration.
- S-12 is fixed: the optimizer CronJob is suspended during a restore.
- S-11 is fixed: the watchdog fails on an unreadable restic repository.
