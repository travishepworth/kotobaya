# HANDOFF T009

STATUS: DONE
BRANCH: t/T009-helm
CHECK: `deploy/helm/check.sh` → green (Makefile `check-deploy` is still a stub)

## Changed (≤ 6 lines)
- `deploy/helm/kotobaya/`: chart with api, worker, web, postgres (StatefulSet), objectStore (SeaweedFS 3.80, ADR-003), ingress (`/` web, `/api` + `/healthz` api), pre-upgrade migrate Job placeholder, NetworkPolicies (default-deny ingress + allows), non-root securityContexts, secret refs only.
- `deploy/helm/check.sh`: helm lint + template (default and toggled values). **Makefile `check-deploy` should call `deploy/helm/check.sh`** (I did not edit the Makefile).
- `deploy/k3d/up.sh [up|down]`: creates k3d cluster, builds the T001 Dockerfiles (or `make images` if defined), imports them, creates dev-only secrets, installs the chart, curls `/healthz` through ingress.

## Acceptance → tests
- helm lint/template clean → check.sh passes.
- k3d up → ran with cluster `kotobaya-t009`; all 5 pods Ready, `/healthz` returned `ok` via Traefik ingress. Cluster and images deleted.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Env var names (`DATABASE_URL` as JDBC URL, `S3_*`) are my guess; server/worker don't read them yet. The migrate hook is `true`, and the Postgres/SeaweedFS secrets are expected to exist beforehand (up.sh creates dev ones).
- Web runs as uid 101 with emptyDir for nginx cache and `/run`; NetworkPolicy egress is not restricted.
