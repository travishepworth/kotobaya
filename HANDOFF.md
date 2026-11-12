# HANDOFF T004

STATUS: DONE
BRANCH: t/T004-openapi
CHECK: `contracts/api/check.sh` → green (`make check MODULE=contracts` is still the Makefile stub; wiring is a later ticket)

## Changed (≤ 6 lines)
- `contracts/api/openapi.yaml`: OpenAPI 3.1 for `/api/v1`, 114 operations, scopes, problem+json errors, cursor pagination, SSE, examples everywhere.
- `contracts/api/SUMMARY.md`: decisions, rejected alternatives, 3 second-look choices, spec→operationId checklist, table↔op↔API cross-check, known gaps.
- `contracts/api/check.sh`, `check_contract.py`, `redocly.yaml`: lint plus contract rules.

## Acceptance → tests
- Every spec endpoint present → `check_contract.py` (SUMMARY checklist covers every operationId).
- Every operation declares a scope; spec validates → Redocly lint (examples validated against schemas) + `check_contract.py`.
- Every learning-data table has an API operation → `check_contract.py` cross-check table.

## New dependencies
- `@redocly/cli@2.77.0` via `npx` in check.sh only — spec lint, as the ticket suggests.

## For the reviewer (≤ 3 lines)
- Sync split is assumed, T005 unseen: push/pull bodies wrap `../sync/batch.schema.json` under `batch`; check.sh used a permissive stand-in because the file is absent. Re-run after T005 merges; the two placeholder batch examples may then fail lint.
- REST-built ops originate from the server device (web reviews are not attributed per browser).
- Undefined here: snapshot archive formats, `confusable.suggested` trigger, pairing-code storage (in-memory).
