# HANDOFF C-23

STATUS: DONE
BRANCH: t/C-23-client-errors
CHECK: `make check MODULE=contracts` → green (no client drift after regen)

## Changed (≤ 6 lines)
- `contracts/api/openapi.yaml`: `POST /client-errors` (`reportClientErrors`, tag meta), schemas `ClientErrorReport`, `ClientErrorItem`, `ClientErrorReportAccepted`; 413/429 error-table rows; empty-scope rule documented.
- Normative server log format (logger `kotobaya.client_error`, escaping, query cut) and client queue/retry/dedupe rules in the operation description.
- `check_contract.py`: `reportClientErrors` joins `ANY_CREDENTIAL`; `SUMMARY.md` updated; ADR-024.
- `clients/**` regenerated.

## Acceptance → tests
- make check contracts green → `contracts/api/check.sh` + clients drift check

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Server: `WiringAuditTest` will FAIL (new op answers 501, allowlist empty) until OBS-1s adds the handler; merge with OBS-1s or add `reportClientErrors` to the allowlist there.
- Decided beyond ticket: `security: bearerAuth: [] / sessionCookie: []` (any credential), optional `http_method/http_path/http_status`, `count`, `dropped`; >20 items is 413 (not 422) though schema says maxItems 20.
- Limits: 1 MiB body, 12 req + 120 items/min per token, in memory.
