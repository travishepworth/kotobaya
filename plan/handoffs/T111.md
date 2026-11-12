# HANDOFF T111

STATUS: DONE (acceptance bullet 2 only partly verified, see below)
BRANCH: t/T111-helm-workloads
CHECK: `make check MODULE=deploy` → green

## Changed
- Chart: api init container runs `/opt/server/bin/server migrate` (default; `migrate.mode=hook` gives a `pre-install,pre-upgrade` Job for an external DB). Probes `/readyz`. Requests/limits. AUTH_PASSWORD_HASH, optional AUTH_TOTP_SECRET, AUTH_TRUSTED_PROXIES (`api.trustedProxies`). Worker command `python -m kotobaya_worker.runner`, `DICT_SOURCES_JSON` from a ConfigMap (`worker.dictSourcesJson`). Postgres `POSTGRES_INITDB_ARGS=--locale=C.UTF-8`. cert-manager issuer annotation; nginx-only events Ingress (no buffering, long timeout). No PDBs.
- `check.sh` asserts rendered output for bundled, external, hook, nginx, traefik, dict toggles.
- `deploy/k3d/up.sh`: fixed `make images` args (VERSION, ARCH), dev argon2id hash secret (password `kotobaya-dev`), Traefik `externalTrafficPolicy: Local` HelmChartConfig, `/api/v1/health` check. New `deploy/k3d/load-dict-fixture.sh`.
- `docker-compose.yml`: initdb locale, api/worker DATABASE_URL, MIGRATE_ON_START, AUTH_*, DICT_SOURCES_JSON.

## Acceptance → tests
- deploy check green → check.sh (bundled/external, hook, nginx).
- k3d (`CLUSTER=kotobaya-t111 HOST_PORT=8099 deploy/k3d/up.sh`): all pods Ready; `/healthz` → `ok`; `/api/v1/health` → `{"status":"ok","version":"0.1.0",...}` through Traefik; migrate init container log "migrate ok: 1 migration(s) applied"; `datctype` = C.UTF-8. `load-dict-fixture.sh` → `LoadResult(release_id=1, entries=2000, ..., kanji=612)`. NOT VERIFIED: `/api/v1/dict/search?q=食べる` returned 501 (handlers wired by W-1, not on this branch); re-run after W-1 merges. Cluster and images deleted.

## New dependencies
none

## For the reviewer
- Chose init-container migrate as default: a pre-install hook cannot reach the bundled Postgres (not yet created). Hook mode exists for external DB.
- Dev hash generated with argon2-cffi (m=65536,t=3,p=4); untested against W-1's server. `DICT_SOURCES_JSON` is `{}` placeholder in up.sh.
- HUMAN_CHECKS: external Postgres needs UTF-8 ctype (`CREATE DATABASE ... TEMPLATE template0 LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8'`; note in values.yaml); set Secret AUTH_PASSWORD_HASH; set `api.trustedProxies`. Compose initdb args apply only to a fresh pgdata volume. No "backups disabled" setting added; T112 can use `api.env`.
