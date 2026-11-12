# HANDOFF T009b

STATUS: DONE
BRANCH: t/T009b-deploy-check
CHECK: `make check MODULE=deploy` → green

## Changed (≤ 6 lines)
- `Makefile`: `check-deploy` now runs `bash deploy/helm/check.sh` instead of the echo stub.

## Acceptance → tests
- `make check MODULE=deploy` runs check.sh and is green → manual run (helm lint + 3 template renders, "helm check OK", exit 0)
- fails if the chart breaks → manual negative test: appended a `required` on a missing value to `templates/api.yaml`; check went red (exit 2, `nil pointer evaluating` at api.yaml:52). Template restored via git, no change committed.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Helm 4.3.0 is on the host; the script calls `helm` directly, so the target needs helm on PATH (as the other modules need their toolchains).
- Lint reports `Chart.yaml: icon is recommended` as INFO only; not a failure.
