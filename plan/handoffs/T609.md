# HANDOFF T609

STATUS: DONE
BRANCH: t/T609-security-review
CHECK: `make check MODULE=deploy` → green

## Changed (≤ 6 lines)
- `docs/SECURITY.md`: threat model, pass/fail checklist, findings S-01..S-14 with proposed tickets SEC-1..SEC-13
- Chart: read-only root filesystem everywhere (voicevox exempt), no ServiceAccount tokens, migrate limits and labels
- NetworkPolicies: egress default deny with explicit paths; worker internet excludes private ranges (SSRF); tighter postgres/objectstore/edge ingress
- HTTPRoute: security headers, plus a CSP sandbox on `/api/v1/blobs` (S-01)
- ntfy: image name fixed (`binwiederhier`); no base-url with a path. Backups: watchdog alerts when the repo is unreadable; restore suspends the optimizer

## Acceptance → tests
- Every workload hardened → `deploy/helm/check_hardening.py` (run by `check.sh` on all toggle combinations)
- NetworkPolicies, headers, no secrets in values → `deploy/helm/check.sh`
- Watchdog on an unreadable repo → `deploy/backup/check.sh` watchdog block
- Live check: k3d probe matrix, backup/verify/watchdog jobs and the full restore drill all passed

## New dependencies
- PyYAML (system python3), used by `check_hardening.py` in `make check MODULE=deploy`

## For the reviewer (≤ 3 lines)
- Top items: S-01 (HIGH, blob XSS: SEC-1 server fix needed) and S-03 (app runs as the Postgres superuser). HUMAN: backups are off in the live deploy, and its own gateway.yaml needs the header filters.
- External Postgres/S3 now needs `networkPolicy.egress.externalServices`, or rendering fails on purpose.
- The k3d test cluster switched kubectl's current context; it is now `homepage`, so check that is the intended one.
