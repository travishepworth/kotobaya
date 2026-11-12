# HANDOFF C-9

STATUS: DONE
BRANCH: t/C-9-internal-jobs
CHECK: `make check MODULE=contracts` → green (clients regenerated; no server/core/web code uses the changed models)

## Changed (≤ 6 lines)
- `openapi.yaml` `Job`: server-internal jobs are rows whose kind is not a `JobKind`. listJobs skips them; getJob and cancelJob answer 404; SSE and ntfy never send them. They show only through the parent.
- `importJpdbHistory`: "Execution" sets out the 4-step `jpdb-history-apply` protocol, including how the parent's status, progress and result follow the child, plus retry, stale and cancel rules. "Review id collisions" covers the id clashes.
- `JobResultJpdbHistory.reviews_id_collisions` (required, ≥0): a subset of `reviews_already_imported`, so the sums are unchanged. The example is updated.
- `db/SCHEMA.md` 4.4 adds a registry of internal kinds. V1 is untouched: its CHECK is a slug regex, and the checksum is kept.

## Acceptance → tests
- Contract-only; redocly/check_contract/client drift green

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- T207b already matches. Worker follow-up: add step 2 (adopt a child that already `succeeded`). Also, `job_result` must report `reviews_id_collisions`; today it folds collisions into already-imported without counting them.
- Server JobRepo/SSE relay must filter internal kinds (T207b's runner pg_notifies the child id).
- Second look: "not in JobKind" vs a prefix; collisions as a subset; no partial counts when cancelled.
